package com.therealnox.gbflash.save

import com.therealnox.gbflash.cart.DmgCartIo
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.DmgMapper
import com.therealnox.gbflash.cart.Mbc3Rtc
import com.therealnox.gbflash.cart.RtcSnapshot
import com.therealnox.gbflash.cart.SaveInfo
import com.therealnox.gbflash.protocol.ProtocolException
import com.therealnox.gbflash.protocol.ReadTimeoutException
import java.io.IOException
import java.util.zip.CRC32

/** What a backup of the inserted cartridge would involve, or why it cannot be done. */
internal sealed interface BackupPlan {
    class Ready(
        val mapper: DmgMapper,
        val saveSize: Int,
        val saveLabel: String,
        val mapperName: String,
        /** Header cart type says TIMER (0x0F / 0x10 / MBC30). */
        val rtcDeclared: Boolean,
    ) : BackupPlan

    class Unavailable(val reason: String) : BackupPlan

    companion object {
        private const val MAX_SAVE = 0x20000

        fun forHeader(h: DmgHeader): BackupPlan {
            if (!h.logoValid) {
                return Unavailable("The cartridge logo is not valid: check the cartridge contacts and the selected type.")
            }
            val mapper = DmgMapper.forMapperRaw(h.mapperRaw)
                ?: return Unavailable("Backup is not supported yet for ${h.mapperName} (cart type 0x%02X).".format(h.cartTypeByte))
            val size = h.save.sizeBytes
            return when {
                h.save.status == SaveInfo.Status.UNKNOWN || size == null ->
                    Unavailable("Header RAM size code 0x%02X is not understood; not supported yet.".format(h.ramSizeCode))
                size == 0 -> Unavailable("The header declares no cartridge RAM, so there is no save to back up.")
                size > MAX_SAVE -> Unavailable("Unexpected save size $size bytes; not supported yet.")
                (mapper == DmgMapper.MBC2) != (h.save.typeId == 0x100) ->
                    Unavailable("Cart type and RAM size are inconsistent for ${h.mapperName}; not supported yet.")
                else -> Ready(mapper, size, h.save.label, h.mapperName, h.hasRtcDeclared && mapper == DmgMapper.MBC3)
            }
        }
    }
}

/** A completely read and double-checked save, still only in memory. */
class DmgBackupResult internal constructor(
    /** Raw SRAM bytes only (MBC2: low nibbles). Never contains the RTC footer. */
    val data: ByteArray,
    /** Latched RTC registers, or null if none were read. */
    val rtc: RtcSnapshot?,
    /** Result of the FlashGBX RTC probe; null when the cart is not an MBC3. */
    val rtcProbePresent: Boolean?,
    val rtcDeclared: Boolean,
    val warnings: List<String>,
) {
    val crc32: Long get() = CRC32().also { it.update(data) }.value
}

class BackupCancelledException : RuntimeException("Backup cancelled")

/** Two reads of a chunk kept disagreeing (or kept coming back short). */
class VerificationFailedException(message: String) : ProtocolException(message)

/**
 * DMG save backup (spec 6.1 - 6.5, 6.8 "verify read"): everything is read into memory, each chunk
 * twice, and nothing is returned unless every chunk matched. Cartridge SRAM is never written; all
 * cartridge access goes through [DmgCartIo].
 */
internal class DmgSaveBackup(
    private val io: DmgCartIo,
    private val prepare: () -> Unit,
    private val releaseBus: () -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val log: (String) -> Unit = {},
) {
    fun run(
        plan: BackupPlan.Ready,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
    ): DmgBackupResult {
        prepare()
        val warnings = mutableListOf<String>()
        val result = try {
            collect(plan, isCancelled, onProgress, warnings)
        } catch (t: Throwable) {
            cleanup(plan.mapper, t)?.let { t.addSuppressed(it) }
            throw t
        }
        cleanup(plan.mapper, null)?.let {
            warnings += "Cartridge cleanup (RAM disable) failed: ${it.message}"
        }
        return DmgBackupResult(result.data, result.rtc, result.probe, plan.rtcDeclared, warnings)
    }

    private class Collected(val data: ByteArray, val rtc: RtcSnapshot?, val probe: Boolean?)

    private fun collect(
        plan: BackupPlan.Ready,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        warnings: MutableList<String>,
    ): Collected {
        val mapper = plan.mapper
        val out = ByteArray(plan.saveSize)
        val total = plan.saveSize.toLong() * 2
        var done = 0L
        onProgress(0, total)
        mapper.enableRam(io)
        val banks = (plan.saveSize + BANK - 1) / BANK
        for (bank in 0 until banks) {
            mapper.selectRamBank(io, bank)
            val bankEnd = minOf(plan.saveSize - bank * BANK, BANK)
            for (pos in 0 until bankEnd step CHUNK) {
                val len = minOf(CHUNK, bankEnd - pos)
                val chunk = readVerified(mapper, bank, pos, len, done, total, isCancelled, onProgress)
                chunk.copyInto(out, bank * BANK + pos)
                done += len * 2L
                onProgress(done, total)
            }
        }
        var rtc: RtcSnapshot? = null
        var probe: Boolean? = null
        if (mapper == DmgMapper.MBC3) {
            if (isCancelled()) throw BackupCancelledException()
            probe = Mbc3Rtc.probePresent(io)
            log("RTC probe: ${if (probe) "present" else "absent"}, header declares timer: ${plan.rtcDeclared}")
            if (probe || plan.rtcDeclared) {
                rtc = try {
                    Mbc3Rtc.read(io, nowSeconds())
                } catch (e: ReadTimeoutException) {
                    warnings += "The RTC registers could not be read (${e.message}); the backup has no RTC."
                    null
                }
            }
        }
        return Collected(out, rtc, probe)
    }

    /** Reads one chunk twice with full setup each time; retries a few times, then fails (spec 8.5 fixed). */
    private fun readVerified(
        mapper: DmgMapper,
        bank: Int,
        pos: Int,
        len: Int,
        doneBefore: Long,
        total: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): ByteArray {
        var problem = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            if (isCancelled()) throw BackupCancelledException()
            try {
                val first = normalise(mapper, io.ramRead(pos, len))
                onProgress(doneBefore + len, total)
                val second = normalise(mapper, io.ramRead(pos, len))
                onProgress(doneBefore + len * 2L, total)
                if (first.contentEquals(second)) return first
                problem = "two reads of the same data differed"
            } catch (e: ReadTimeoutException) {
                problem = "short or missing reply"
            }
            log("Chunk bank $bank +0x%X: $problem (attempt ${attempt + 1}/$MAX_ATTEMPTS)".format(pos))
        }
        throw VerificationFailedException(
            "Verification failed at RAM bank $bank offset 0x%04X after $MAX_ATTEMPTS attempts: $problem. Nothing was saved. Check the cartridge contacts and try again."
                .format(pos),
        )
    }

    private fun normalise(mapper: DmgMapper, data: ByteArray): ByteArray {
        if (!mapper.nibbleOnly) return data
        return ByteArray(data.size) { (data[it].toInt() and 0x0F).toByte() } // upper nibble is open bus
    }

    /** Spec 6.1.5: bank 0, RAM off, read pulse off, address lines to inputs. Returns the first failure. */
    private fun cleanup(mapper: DmgMapper, primary: Throwable?): Throwable? {
        // Plain IOException (not a protocol error) means the link itself is gone; every step would hang.
        if (primary is IOException && primary !is ProtocolException) return null
        var failure: Throwable? = null
        fun step(block: () -> Unit): Boolean = try {
            block()
            true
        } catch (e: ProtocolException) {
            if (failure == null) failure = e
            false
        }
        val bankOk = step { mapper.selectRamBank(io, 0) }
        val disabled = step { mapper.disableRam(io) }
        // RAM disable is what protects the save; give up on the rest if the device is not answering.
        if (bankOk || disabled) step { releaseBus() }
        return failure
    }

    companion object {
        const val BANK = 0x2000
        const val CHUNK = 0x200
        const val MAX_ATTEMPTS = 4
    }
}

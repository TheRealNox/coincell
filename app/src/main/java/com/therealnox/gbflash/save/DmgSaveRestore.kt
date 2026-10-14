package com.therealnox.gbflash.save

import com.therealnox.gbflash.cart.DmgCartIo
import com.therealnox.gbflash.cart.DmgCartWriter
import com.therealnox.gbflash.cart.DmgMapper
import com.therealnox.gbflash.cart.Mbc3Rtc
import com.therealnox.gbflash.cart.Mbc3RtcWrite
import com.therealnox.gbflash.cart.RtcAdvance
import com.therealnox.gbflash.cart.RtcSnapshot
import com.therealnox.gbflash.cart.RtcTime
import com.therealnox.gbflash.protocol.ProtocolException
import java.io.IOException
import java.util.zip.CRC32

/** The three phases a restore shows: the safety copy, the write and the read-back. */
enum class RestorePhase { SAFETY_COPY, WRITE, VERIFY }

/** How the cartridge part of a restore ended. Nothing here is "success" unless it is [Verified]. */
internal sealed interface RestoreOutcome {
    val warnings: List<String>

    /** The read-back matched the intended bytes exactly. */
    class Verified(
        val crc32: Long,
        /** The clock values written, or null if the clock was left alone. */
        val rtcWritten: RtcTime?,
        /** Set if the clock write was requested but failed (the save itself was verified). */
        val rtcError: String?,
        val rtcReadBack: RtcSnapshot?,
        override val warnings: List<String>,
    ) : RestoreOutcome

    /** The read-back differs. [bank] / [offset] locate the first differing byte. */
    class Mismatch(
        val bank: Int,
        val offset: Int,
        val expected: Int,
        val actual: Int,
        val differingBytes: Int,
        val readBackCrc32: Long,
        override val warnings: List<String>,
    ) : RestoreOutcome

    /** An error. If [wroteData] the cartridge may hold a partly written save. */
    class Failed(
        val message: String,
        val wroteData: Boolean,
        val bank: Int,
        val offset: Int,
        override val warnings: List<String>,
    ) : RestoreOutcome

    /** Stopped on request. If [wroteData] the save on the cartridge is partly overwritten. */
    class Cancelled(val wroteData: Boolean, override val warnings: List<String>) : RestoreOutcome
}

/**
 * DMG save restore (spec 6.1, 6.1.4, 6.2 - 6.5, 6.7, 6.8): prelude, enable RAM, per bank select and
 * write in 256-byte `B3` requests, optional MBC3 clock write, then a full verified read-back (the
 * same double-read as the backup) and a byte-for-byte comparison. Cleanup always runs.
 *
 * This is the only class that is handed a [DmgCartWriter]. It does not decide whether a restore is
 * allowed (see the restore coordinator); it assumes the caller has done the matching, safety copy and
 * header checks.
 */
internal class DmgSaveRestore(
    private val io: DmgCartIo,
    private val writer: DmgCartWriter,
    private val prepare: () -> Unit,
    private val releaseBus: () -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val log: (String) -> Unit = {},
) {
    private class Cancel : RuntimeException("Restore cancelled")

    private class Progress {
        var wrote = false
        var bank = -1
        var offset = -1
    }

    /**
     * Writes [data] (exactly the save size, MBC2: written as stored, only the low nibbles count) and
     * verifies it. [clockFooter] is the 48-byte RTC footer to restore, or null to leave the clock alone
     * (MBC3 only). Cancellation is polled before every write request; the read-back cannot be cancelled.
     */
    fun run(
        plan: BackupPlan.Ready,
        data: ByteArray,
        clockFooter: ByteArray?,
        adjustClock: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (RestorePhase, done: Long, total: Long) -> Unit,
    ): RestoreOutcome {
        require(data.size == plan.saveSize) { "Restore data is ${data.size} bytes, the cartridge save is ${plan.saveSize}" }
        require(clockFooter == null || plan.mapper == DmgMapper.MBC3) { "Only MBC3 cartridges have a clock to restore" }
        val warnings = mutableListOf<String>()
        val progress = Progress()
        var failure: Throwable? = null
        val outcome: RestoreOutcome = try {
            writeAndVerify(plan, data, clockFooter, adjustClock, isCancelled, onProgress, progress, warnings)
        } catch (_: Cancel) {
            RestoreOutcome.Cancelled(progress.wrote, warnings)
        } catch (e: Throwable) {
            failure = e
            log("Restore failed: $e")
            val where = if (e is VerificationFailedException && e.bank >= 0) e.bank to e.offset else progress.bank to progress.offset
            RestoreOutcome.Failed(e.message ?: e.toString(), progress.wrote, where.first, where.second, warnings)
        }
        cleanup(plan.mapper, failure)?.let { warnings += "Cartridge cleanup (RAM disable) failed: ${it.message}" }
        return outcome
    }

    private fun writeAndVerify(
        plan: BackupPlan.Ready,
        data: ByteArray,
        clockFooter: ByteArray?,
        adjustClock: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (RestorePhase, Long, Long) -> Unit,
        state: Progress,
        warnings: MutableList<String>,
    ): RestoreOutcome {
        val mapper = plan.mapper
        val size = plan.saveSize
        prepare()
        mapper.enableRam(io)
        onProgress(RestorePhase.WRITE, 0, size.toLong())
        var done = 0L
        val banks = (size + BANK - 1) / BANK
        for (bank in 0 until banks) {
            if (isCancelled()) throw Cancel()
            state.bank = bank
            mapper.selectRamBank(io, bank)
            val bankEnd = minOf(size - bank * BANK, BANK)
            for (pos in 0 until bankEnd step REQUEST) {
                if (isCancelled()) throw Cancel()
                val len = minOf(REQUEST, bankEnd - pos)
                state.offset = pos
                state.wrote = true // from the first request on the cartridge may be partly written
                writer.writeRam(pos, data.copyOfRange(bank * BANK + pos, bank * BANK + pos + len))
                done += len
                onProgress(RestorePhase.WRITE, done, size.toLong())
            }
            // One summary line per bank: the raw B3 traffic scrolls out of the bounded debug log.
            log("Wrote RAM bank $bank: $bankEnd bytes in ${(bankEnd + REQUEST - 1) / REQUEST} requests, all acknowledged")
        }
        var rtcWritten: RtcTime? = null
        var rtcError: String? = null
        if (clockFooter != null) {
            if (isCancelled()) throw Cancel()
            val time = RtcAdvance.compute(clockFooter, nowSeconds(), advance = adjustClock)
            try {
                Mbc3RtcWrite.write(io, writer, time)
                rtcWritten = time
                log("Clock written: ${time.describe()}")
            } catch (e: ProtocolException) {
                rtcError = "The clock could not be written: ${e.message}"
                log(rtcError)
                try {
                    writer.release() // the failed request may have left the write pulse on
                } catch (_: ProtocolException) {
                    // the verification below will show whether the link still works
                }
            }
        }

        // Verification (spec 6.8): bank 0, repeat the prelude, enable RAM, read everything twice.
        state.bank = -1
        state.offset = -1
        mapper.selectRamBank(io, 0)
        prepare()
        mapper.enableRam(io)
        val readBack = VerifiedRamReader(io, log, "The cartridge contents are uncertain.")
            .readSave(mapper, size, { false }) { d, t -> onProgress(RestorePhase.VERIFY, d, t) }
        val intended = VerifiedRamReader.normalise(mapper, data)
        val diff = firstDifference(intended, readBack)
        if (diff >= 0) {
            val count = intended.indices.count { intended[it] != readBack[it] }
            return RestoreOutcome.Mismatch(
                bank = diff / BANK,
                offset = diff % BANK,
                expected = intended[diff].toInt() and 0xFF,
                actual = readBack[diff].toInt() and 0xFF,
                differingBytes = count,
                readBackCrc32 = crc(readBack),
                warnings = warnings,
            )
        }
        var rtcReadBack: RtcSnapshot? = null
        if (rtcWritten != null) {
            rtcReadBack = try {
                Mbc3Rtc.read(io, nowSeconds())
            } catch (e: ProtocolException) {
                warnings += "The clock was written but could not be read back: ${e.message}"
                null
            }
        }
        return RestoreOutcome.Verified(crc(readBack), rtcWritten, rtcError, rtcReadBack, warnings)
    }

    private fun firstDifference(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) if (a[i] != b[i]) return i
        return -1
    }

    private fun crc(data: ByteArray): Long = CRC32().also { it.update(data) }.value

    /** Spec 6.1.5, plus the write pulse and address reset: bank 0, RAM off, read pulse off, address lines to inputs. */
    private fun cleanup(mapper: DmgMapper, primary: Throwable?): Throwable? {
        if (primary is IOException && primary !is ProtocolException) return null // link gone, every step would hang
        var failure: Throwable? = null
        fun step(block: () -> Unit): Boolean = try {
            block()
            true
        } catch (e: ProtocolException) {
            if (failure == null) failure = e
            false
        }
        val writeOff = step { writer.release() }
        val bankOk = step { mapper.selectRamBank(io, 0) }
        val disabled = step { mapper.disableRam(io) }
        if (writeOff || bankOk || disabled) step { releaseBus() }
        return failure
    }

    companion object {
        const val BANK = VerifiedRamReader.BANK
        const val REQUEST = DmgCartWriter.MAX_REQUEST
    }
}

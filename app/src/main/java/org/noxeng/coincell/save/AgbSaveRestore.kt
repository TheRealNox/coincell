package org.noxeng.coincell.save

import org.noxeng.coincell.cart.AgbCartIo
import org.noxeng.coincell.cart.AgbCartWriter
import org.noxeng.coincell.cart.AgbFlashChip
import org.noxeng.coincell.cart.AgbRtcAdvance
import org.noxeng.coincell.cart.AgbRtcRead
import org.noxeng.coincell.cart.AgbRtcWrite
import org.noxeng.coincell.cart.AgbSaveTypes
import org.noxeng.coincell.cart.ClockValues
import org.noxeng.coincell.protocol.ProtocolException
import java.time.ZoneId
import java.util.zip.CRC32

/**
 * GBA save restore (spec 6.1.2, 6.6, 6.7, 6.8, LK:3700-3760, 3906-3971): prelude, Flash chip identification, per bank
 * (bank select) and chunk the writes, the optional clock write, then a full verified read-back (the same double
 * read as the backup) and a byte-for-byte comparison.
 *
 * Phases: there is no separate erase phase. FlashGBX erases one sector and programs it, then moves to the next
 * (LK:3719-3751), and so does this engine; the progress callback reports it all as [RestorePhase.WRITE] and the UI
 * words it for Flash ("erasing and writing sector by sector").
 *
 * This is the only class that is handed an [AgbCartWriter]. It does not decide whether a restore is allowed (see the
 * restore coordinator); it assumes the caller has done the matching, safety copy and header checks.
 */
internal class AgbSaveRestore(
    private val io: AgbCartIo,
    private val writer: AgbCartWriter,
    private val prepare: () -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val log: (String) -> Unit = {},
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private class Cancel : RuntimeException("Restore cancelled")

    private class Progress {
        var wrote = false
        var bank = -1
        var offset = -1
    }

    /**
     * Writes [data] (exactly the save size) and verifies it. [clockFooter] is the 16 byte GBA clock footer to
     * restore, or null to leave the clock alone. Cancellation is polled before every chunk; the read-back cannot be
     * cancelled.
     */
    fun run(
        plan: AgbBackupPlan.Ready,
        data: ByteArray,
        clockFooter: ByteArray?,
        adjustClock: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (RestorePhase, done: Long, total: Long) -> Unit,
    ): RestoreOutcome {
        require(data.size == plan.saveSize) { "Restore data is ${data.size} bytes, the cartridge save is ${plan.saveSize}" }
        val warnings = mutableListOf<String>()
        val progress = Progress()
        return try {
            writeAndVerify(plan, data, clockFooter, adjustClock, isCancelled, onProgress, progress, warnings)
        } catch (_: Cancel) {
            RestoreOutcome.Cancelled(progress.wrote, warnings)
        } catch (e: Throwable) {
            log("Restore failed: $e")
            val where = if (e is VerificationFailedException && e.bank >= 0) e.bank to e.offset else progress.bank to progress.offset
            RestoreOutcome.Failed(e.message ?: e.toString(), progress.wrote, where.first, where.second, warnings)
        }
    }

    private fun writeAndVerify(
        plan: AgbBackupPlan.Ready,
        data: ByteArray,
        clockFooter: ByteArray?,
        adjustClock: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (RestorePhase, Long, Long) -> Unit,
        state: Progress,
        warnings: MutableList<String>,
    ): RestoreOutcome {
        prepare()
        val chip = chipFor(plan)
        if (chip != null) checkRestorable(chip)
        val layout = AgbSaveLayout(plan.saveType, chip)
        val size = layout.saveSize
        val len = layout.bufferLen(restore = true)
        onProgress(RestorePhase.WRITE, 0, size.toLong())
        var done = 0L
        for (bank in 0 until layout.banks) {
            if (isCancelled()) throw Cancel()
            state.bank = bank
            selectBank(layout, bank)
            val end = layout.bankLength(bank)
            for (pos in 0 until end step len) {
                if (isCancelled()) throw Cancel()
                val n = minOf(len, end - pos)
                state.offset = pos
                val chunk = data.copyOfRange(bank * AgbSaveLayout.BANK_SIZE + pos, bank * AgbSaveLayout.BANK_SIZE + pos + n)
                writeChunk(layout, pos, chunk, state)
                done += n
                onProgress(RestorePhase.WRITE, done, size.toLong())
            }
            log("Wrote save bank $bank: ${layout.bankLength(bank)} bytes")
        }

        var rtcWritten: org.noxeng.coincell.cart.AgbRtcTime? = null
        var rtcError: String? = null
        if (clockFooter != null) {
            if (isCancelled()) throw Cancel()
            val time = AgbRtcAdvance.compute(clockFooter, nowSeconds(), adjustClock, zone)
            try {
                AgbRtcWrite.write(writer, time)
                rtcWritten = time
                log("Clock written: ${time.describe()}")
            } catch (e: ProtocolException) {
                rtcError = "The clock could not be written: ${e.message}"
                log(rtcError)
            }
        }

        // Verification (spec 6.8): repeat the prelude (including the Flash ID), read everything twice.
        state.bank = -1
        state.offset = -1
        prepare()
        val chip2 = chipFor(plan)
        val readLayout = AgbSaveLayout(plan.saveType, chip2)
        val readBack = AgbVerifiedReader(io, log, "The cartridge contents are uncertain.")
            .readSave(readLayout, verify = true, { false }) { d, t -> onProgress(RestorePhase.VERIFY, d, t) }
        val diff = firstDifference(data, readBack)
        if (diff >= 0) {
            return RestoreOutcome.Mismatch(
                bank = diff / AgbSaveLayout.BANK_SIZE,
                offset = diff % AgbSaveLayout.BANK_SIZE,
                expected = data[diff].toInt() and 0xFF,
                actual = readBack[diff].toInt() and 0xFF,
                differingBytes = data.indices.count { data[it] != readBack[it] },
                readBackCrc32 = crc(readBack),
                warnings = warnings,
            )
        }
        var rtcReadBack: ClockValues? = null
        if (rtcWritten != null) {
            rtcReadBack = try {
                (AgbRtcRead.probe(io, log) as? AgbRtcRead.Probe.Present)?.let { AgbRtcRead.snapshot(io, it, nowSeconds()) }
            } catch (e: ProtocolException) {
                warnings += "The clock was written but could not be read back: ${e.message}"
                null
            }
        }
        return RestoreOutcome.Verified(crc(readBack), rtcWritten, rtcError, rtcReadBack, warnings)
    }

    private fun chipFor(plan: AgbBackupPlan.Ready): AgbFlashChip? =
        if (AgbSaveTypes.isFlash(plan.saveType)) AgbFlashId.requireChip(io, plan.saveSize, log) else null

    /** Chips this engine will not erase and program: unknown IDs, and the bootlegs whose erase FlashGBX does not cover. */
    private fun checkRestorable(chip: AgbFlashChip) {
        if (!chip.known) {
            throw AgbSaveException("The Flash chip (${chip.label}) is not one this app knows how to erase and program. Backing up works, restoring does not. Nothing was written.")
        }
        if (chip.isBootleg) {
            throw AgbSaveException("The Flash chip (${chip.label}) is an unlicensed chip whose erase FlashGBX itself marks unverified. Backing up works, restoring is not supported. Nothing was written.")
        }
    }

    private fun selectBank(layout: AgbSaveLayout, bank: Int) {
        when (layout.bankSwitch) {
            AgbBankSwitch.NONE -> Unit
            AgbBankSwitch.BOOTLEG -> io.selectBootlegBank(bank)
            AgbBankSwitch.FLASH -> io.selectFlashBank(bank)
        }
    }

    private fun writeChunk(layout: AgbSaveLayout, pos: Int, chunk: ByteArray, state: Progress) {
        val requests = AgbCartWriter.MAX_REQUEST
        when {
            layout.isEeprom -> {
                state.wrote = true
                writer.writeEeprom(layout.eepromType, pos / 8, chunk)
            }
            layout.isFlash -> {
                val chip = layout.flashChip!!
                if (chip.isAtmel) {
                    // No erase: the chip replaces whole 128 byte pages (LK:3717-3718).
                    state.wrote = true
                    for (off in chunk.indices step requests) writer.writeFlash(pos + off, chunk.copyOfRange(off, minOf(off + requests, chunk.size)), atmelPages = true)
                } else {
                    state.wrote = true
                    eraseSector(pos % AgbSaveLayout.BANK_SIZE)
                    if (!chunk.all { it == 0xFF.toByte() }) {
                        for (off in chunk.indices step requests) writer.writeFlash(pos + off, chunk.copyOfRange(off, minOf(off + requests, chunk.size)), atmelPages = false)
                    }
                }
            }
            else -> {
                state.wrote = true
                for (off in chunk.indices step requests) writer.writeSram(pos + off, chunk.copyOfRange(off, minOf(off + requests, chunk.size)))
            }
        }
    }

    /** Erase command, then up to 50 polls of 10 ms until the first two bytes read 0xFFFF (LK:3733-3750). */
    private fun eraseSector(sector: Int) {
        writer.eraseFlashSector(sector)
        var status = 0
        repeat(ERASE_POLLS) {
            io.sleep(ERASE_POLL_MS)
            val raw = io.saveRead(sector, 2)
            status = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            if (status == 0xFFFF) return
        }
        // FlashGBX prints an error and carries on; writing into a sector that did not erase can only corrupt it, so stop.
        throw AgbSaveException("The Flash sector at 0x%04X did not erase (status 0x%04X).".format(sector, status))
    }

    private fun firstDifference(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) if (a[i] != b[i]) return i
        return -1
    }

    private fun crc(data: ByteArray): Long = CRC32().also { it.update(data) }.value

    companion object {
        const val ERASE_POLLS = 50
        const val ERASE_POLL_MS = 10L
    }
}

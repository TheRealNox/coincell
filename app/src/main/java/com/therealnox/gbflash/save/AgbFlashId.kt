package com.therealnox.gbflash.save

import com.therealnox.gbflash.cart.AgbCartIo
import com.therealnox.gbflash.cart.AgbFlashChip
import com.therealnox.gbflash.protocol.ProtocolException

/**
 * `ReadFlashSaveID` (LK:1616-1673, spec 5.7): tells a Flash save chip from SRAM / FRAM and reads the chip ID.
 *
 * The only writes are the ones named in [AgbCartIo]: a one-byte test at save offset 4 that is put straight back, the
 * ID-mode enter / exit sequences and, for a chip that is not in the table, the restore of the three bytes the ID
 * sequence may have hit. Two deliberate differences from FlashGBX: the original byte and ID mode are also put back
 * when a read in between fails, and the failure is reported instead of silently leaving the chip in ID mode.
 */
internal object AgbFlashId {
    /** Bytes read to look at save offset 4 (LK:1621). */
    private const val PROBE_LEN = 16

    sealed interface Result {
        class Flash(val chip: AgbFlashChip) : Result

        /** The byte written to offset 4 stuck: this is SRAM / FRAM, not Flash (LK:1624-1632). */
        data object LooksLikeSram : Result
    }

    fun read(io: AgbCartIo, log: (String) -> Unit = {}): Result {
        val before = io.saveRead(0, PROBE_LEN)
        if (before.size < 5) throw AgbSaveException("The save memory did not answer")
        val t1 = before[4].toInt() and 0xFF
        io.flashIdProbeFlip(t1)
        val t2 = try {
            val after = io.saveRead(0, PROBE_LEN)
            if (after.size < 5) throw AgbSaveException("The save memory did not answer")
            after[4].toInt() and 0xFF
        } catch (e: ProtocolException) {
            restoreQuietly(io, t1, e)
            throw e
        }
        io.flashIdProbeRestore(t1)
        if (t1 != t2) {
            log("Save offset 4 changed from 0x%02X to 0x%02X: SRAM / FRAM, not Flash".format(t1, t2))
            return Result.LooksLikeSram
        }

        val b5555 = io.saveRead(0x5555, 1)[0].toInt() and 0xFF
        val b2AAA = io.saveRead(0x2AAA, 1)[0].toInt() and 0xFF
        val b0000 = io.saveRead(0x0000, 1)[0].toInt() and 0xFF

        io.flashIdEnter()
        val id = try {
            val raw = io.saveRead(0, 2)
            if (raw.size < 2) throw AgbSaveException("The Flash chip did not answer in ID mode")
            ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        } catch (e: ProtocolException) {
            try {
                io.flashIdExit()
            } catch (suppressed: ProtocolException) {
                e.addSuppressed(suppressed)
            }
            throw e
        }
        io.flashIdExit()

        if (!AgbFlashChip.isKnown(id)) io.flashIdRestoreBytes(b5555, b2AAA, b0000)
        val chip = AgbFlashChip.lookup(id)
        log("Flash save chip ID 0x%04X: %s".format(id, chip.label))
        return Result.Flash(chip)
    }

    private fun restoreQuietly(io: AgbCartIo, original: Int, primary: Throwable) {
        try {
            io.flashIdProbeRestore(original)
        } catch (e: ProtocolException) {
            primary.addSuppressed(e)
        }
    }

    /**
     * The chip of a cartridge whose save type is Flash, or an [AgbSaveException] saying what is wrong (LK:3464-3468).
     * [saveSize] is the size of the chosen save type; a chip smaller than that cannot hold it.
     */
    fun requireChip(io: AgbCartIo, saveSize: Int, log: (String) -> Unit = {}): AgbFlashChip {
        return when (val r = read(io, log)) {
            Result.LooksLikeSram -> throw AgbSaveException(
                "The save memory of this cartridge behaves like SRAM / FRAM, not like Flash, so the chosen Flash save type is wrong. Nothing was written.",
            )
            is Result.Flash -> {
                if (r.chip.known && r.chip.size < saveSize) {
                    throw AgbSaveException(
                        "The Flash chip (${r.chip.label}, ${r.chip.size / 1024} KiB) is smaller than the chosen save type (${saveSize / 1024} KiB).",
                    )
                }
                r.chip
            }
        }
    }
}

package org.noxeng.coincell.save

import org.noxeng.coincell.cart.AgbRtcSnapshot
import org.noxeng.coincell.cart.RtcSnapshot

/**
 * Interprets the bytes of a `.sav` file chosen by the user (PKHeX, an emulator, FlashGBX) for the
 * cartridge in the reader (spec 7). Pure; nothing here touches the cartridge or the library.
 */
object SaveImport {
    /** BGB appends a different, 44-byte clock footer (spec 7). It is recognised only to give a useful message. */
    private const val BGB_FOOTER = 44

    sealed interface Result {
        /** [save] is exactly the cartridge save size; [rtcFooter] is the footer split off the end (48 bytes GB / GBC, 16 GBA), or null. */
        class Ok(val save: ByteArray, val rtcFooter: ByteArray?) : Result

        class Rejected(val reason: String) : Result
    }

    /** The largest file worth reading: the biggest supported save plus a footer. */
    fun maxFileSize(saveSize: Int, footerSize: Int = RtcSnapshot.FOOTER_SIZE): Int = saveSize + footerSize

    /**
     * [saveSize] is the save size from the cartridge header; [clockCart] is true when the cartridge is
     * an MBC3 (the only supported mapper with a clock footer).
     */
    fun parse(bytes: ByteArray, saveSize: Int, clockCart: Boolean): Result {
        val n = bytes.size
        return when {
            n == 0 -> Result.Rejected("The file is empty.")
            n == saveSize -> Result.Ok(bytes, null)
            n == saveSize + RtcSnapshot.FOOTER_SIZE && clockCart ->
                Result.Ok(bytes.copyOf(saveSize), bytes.copyOfRange(saveSize, n))
            n == saveSize + RtcSnapshot.FOOTER_SIZE ->
                Result.Rejected(
                    "The file is $n bytes: a $saveSize byte save plus a ${RtcSnapshot.FOOTER_SIZE} byte clock footer, " +
                        "but this cartridge has no clock (it is not an MBC3). Remove the footer or read the right cartridge.",
                )
            n == saveSize + BGB_FOOTER && clockCart ->
                Result.Rejected(
                    "The file is $n bytes: a $saveSize byte save plus a $BGB_FOOTER byte footer. That looks like BGB's " +
                        "clock format, which is not supported. Save without the clock footer (or with a ${RtcSnapshot.FOOTER_SIZE} byte one).",
                )
            else -> Result.Rejected(
                "The file is $n bytes but this cartridge's save is $saveSize bytes" +
                    (if (clockCart) " (or ${saveSize + RtcSnapshot.FOOTER_SIZE} with a clock footer)" else "") +
                    ". It is not a save for this game's cartridge type.",
            )
        }
    }

    /**
     * GBA: the file is the raw save of the chosen save type ([saveSize] bytes), or that plus FlashGBX's 16 byte clock
     * footer (`save_size_includes_rtc`, Mapper.py:52-66: save size + 0x10). Whether the cartridge really has a clock
     * is only checked when the backup is restored. Smaller files are rejected; FlashGBX repeats them to fill the save
     * (LK:3568-3574), which is not a safe guess for a stored backup.
     */
    fun parseAgb(bytes: ByteArray, saveSize: Int): Result {
        val n = bytes.size
        val footer = AgbRtcSnapshot.FOOTER_SIZE
        return when {
            n == 0 -> Result.Rejected("The file is empty.")
            n == saveSize -> Result.Ok(bytes, null)
            n == saveSize + footer -> Result.Ok(bytes.copyOf(saveSize), bytes.copyOfRange(saveSize, n))
            n < saveSize && saveSize % n == 0 -> Result.Rejected(
                "The file is $n bytes but this save type needs $saveSize bytes. FlashGBX would repeat the data to fill it; " +
                    "choose the save type that matches the file, or provide a full-size save.",
            )
            else -> Result.Rejected(
                "The file is $n bytes but this cartridge's save is $saveSize bytes (or ${saveSize + footer} with a $footer byte clock footer). " +
                    "It is not a save for this save type. Check the save type on the Device tab.",
            )
        }
    }

    /** GB / GBC cartridge save sizes: MBC2 (512 nibbles), 2, 8, 32, 64 and 128 KiB. */
    val DMG_SIZES: List<Int> = listOf(512, 0x800, 0x2000, 0x8000, 0x10000, 0x20000)

    /** GBA save sizes: 4K / 64K EEPROM, 256K SRAM, 512K and 1M Flash (or unlicensed SRAM of those sizes). */
    val AGB_SIZES: List<Int> = listOf(512, 0x2000, 0x8000, 0x10000, 0x20000)

    /** The largest file worth reading when there is no cartridge to say what to expect. */
    val MAX_LOOSE_FILE: Int = 0x20000 + RtcSnapshot.FOOTER_SIZE

    /**
     * A `.sav` imported without its cartridge: the save size is whichever supported size [bytes] has, optionally
     * followed by the platform's clock footer (48 bytes GB / GBC, 16 GBA). [gba] picks the platform.
     */
    fun parseLoose(bytes: ByteArray, gba: Boolean): Result {
        val n = bytes.size
        val sizes = if (gba) AGB_SIZES else DMG_SIZES
        val footer = if (gba) AgbRtcSnapshot.FOOTER_SIZE else RtcSnapshot.FOOTER_SIZE
        return when {
            n == 0 -> Result.Rejected("The file is empty.")
            n in sizes -> Result.Ok(bytes, null)
            (n - footer) in sizes -> Result.Ok(bytes.copyOf(n - footer), bytes.copyOfRange(n - footer, n))
            !gba && (n - BGB_FOOTER) in sizes -> Result.Rejected(
                "The file is $n bytes: a ${n - BGB_FOOTER} byte save plus a $BGB_FOOTER byte footer. That looks like BGB's clock format, " +
                    "which is not supported. Save without the clock footer (or with a ${RtcSnapshot.FOOTER_SIZE} byte one).",
            )
            else -> Result.Rejected(
                "The file is $n bytes, which is not a ${if (gba) "GBA" else "GB / GBC"} save size (" +
                    sizes.joinToString { if (it >= 1024) "${it / 1024} KiB" else "$it bytes" } +
                    ", optionally followed by a $footer byte clock footer).",
            )
        }
    }
}

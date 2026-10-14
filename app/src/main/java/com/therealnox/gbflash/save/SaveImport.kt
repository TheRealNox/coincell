package com.therealnox.gbflash.save

import com.therealnox.gbflash.cart.RtcSnapshot

/**
 * Interprets the bytes of a `.sav` file chosen by the user (PKHeX, an emulator, FlashGBX) for the
 * cartridge in the reader (spec 7). Pure; nothing here touches the cartridge or the library.
 */
object SaveImport {
    /** BGB appends a different, 44-byte clock footer (spec 7). It is recognised only to give a useful message. */
    private const val BGB_FOOTER = 44

    sealed interface Result {
        /** [save] is exactly the cartridge save size; [rtcFooter] is the 48-byte footer split off the end, or null. */
        class Ok(val save: ByteArray, val rtcFooter: ByteArray?) : Result

        class Rejected(val reason: String) : Result
    }

    /** The largest file worth reading: the biggest supported save plus a footer. */
    fun maxFileSize(saveSize: Int): Int = saveSize + RtcSnapshot.FOOTER_SIZE

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
}

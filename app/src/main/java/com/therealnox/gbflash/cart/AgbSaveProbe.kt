package com.therealnox.gbflash.cart

/** `_find_repeating_size` (LK:2748-2756): smallest power-of-two prefix that tiles [data]. */
fun findRepeatingSize(data: ByteArray, minSize: Int = 0x20): Int {
    var offset = data.size
    while (offset >= minSize) {
        offset /= 2
        if (offset == 0) break
        val same = (0 until offset).all { data[it] == data[offset + it] }
        if (!same) {
            offset *= 2
            break
        }
    }
    return offset
}

/** Result of the read-only look at the 64 KiB GBA save window (C3 reads, no writes). */
data class AgbSaveHint(val summary: String, val suggestedType: Int?)

/**
 * Interprets a read of the GBA cartridge save window. This cannot separate SRAM from Flash nor
 * detect EEPROM (both need writes, spec 5.7 / 5.8 step 3d), so it only reports what is visible.
 */
fun analyzeSaveWindow(data: ByteArray): AgbSaveHint {
    val allFF = data.all { it == 0xFF.toByte() }
    val all00 = data.all { it.toInt() == 0 }
    if (allFF || all00) {
        return AgbSaveHint(
            "Window reads as all 0x${if (allFF) "FF" else "00"}: no SRAM/FRAM/Flash here. " +
                "Either no save, an EEPROM cart (needs EEPROM probe, not implemented) or blank memory.",
            null,
        )
    }
    val rep = findRepeatingSize(data)
    return when {
        rep <= 0x100 -> AgbSaveHint(
            "Tiny repeating pattern (period $rep): not convincing save memory; likely no SRAM/Flash.",
            null,
        )
        rep == 0x8000 -> AgbSaveHint(
            "32 KiB mirrored across the window: 256K SRAM/FRAM (save type 3) is likely.",
            3,
        )
        rep >= data.size -> AgbSaveHint(
            "Non-repeating ${data.size / 1024} KiB: 512K SRAM (7), 512K Flash (4) or 1M Flash (5, first bank). " +
                "Telling these apart needs the Flash ID probe, which writes to the cart (not implemented).",
            null,
        )
        else -> AgbSaveHint("Data repeats every $rep bytes; no standard save size matches.", null)
    }
}

package com.therealnox.gbflash.cart

/**
 * GBA save types as FlashGBX numbers them (`AgbSaveTypes.SAVE_TYPES`, CartridgeTypes.py:90-101). The index is the
 * `save_type` everywhere: in the database, in `backup.json` and in the engines.
 */
internal object AgbSaveTypes {
    const val NONE = 0
    const val EEPROM_4K = 1
    const val EEPROM_64K = 2
    const val SRAM_256K = 3
    const val FLASH_512K = 4
    const val FLASH_1M = 5
    const val DACS_8M = 6
    const val SRAM_512K = 7
    const val SRAM_1M = 8
    const val BATTERYLESS = 9

    /** The types a backup / restore is implemented for (not DACS 8M, not batteryless SRAM, not "none"). */
    val SUPPORTED: List<Int> = listOf(EEPROM_4K, EEPROM_64K, SRAM_256K, FLASH_512K, FLASH_1M, SRAM_512K, SRAM_1M)

    fun isSupported(type: Int): Boolean = type in SUPPORTED

    fun size(type: Int): Int = AgbHeader.SAVE_TYPES[type]?.first ?: 0

    fun name(type: Int): String = AgbHeader.SAVE_TYPES[type]?.second ?: "Unknown"

    fun isEeprom(type: Int): Boolean = type == EEPROM_4K || type == EEPROM_64K

    fun isFlash(type: Int): Boolean = type == FLASH_512K || type == FLASH_1M

    fun isSram(type: Int): Boolean = type == SRAM_256K || type == SRAM_512K || type == SRAM_1M

    /** "1M FLASH (128 KiB)", "4K EEPROM (512 bytes)". */
    fun label(type: Int): String {
        val n = name(type)
        val s = size(type)
        return when {
            type == NONE -> "None"
            s == 0 -> n
            s >= 1024 -> "$n (${s / 1024} KiB)"
            else -> "$n ($s bytes)"
        }
    }
}

/**
 * The Flash save chips FlashGBX knows (`AGB_FLASH_SAVE_CHIPS`, CartridgeTypes.py:124-135). The key is the
 * `(manufacturer << 8) | device` value that [AgbFlashId] reads in ID mode.
 */
internal class AgbFlashChip(val id: Int, val name: String, val size: Int, val known: Boolean) {
    /** Atmel AT29LV512: no erase command, written in 128 byte pages with `C7 02` (LK:3526, 3717). */
    val isAtmel: Boolean get() = id == ATMEL

    /** SST bootlegs: 1M cartridges with a bank register at ROM address 0x1000000 (LK:3612). */
    val isBootleg: Boolean get() = id in BOOTLEGS

    /** Bytes per chunk (LK:3470-3474). */
    val bufferLen: Int
        get() = when (id) {
            0xBF4B, 0xBF5B, 0xFFFF -> 0x800
            0xBF6D -> 0x8000
            else -> 0x1000
        }

    val label: String get() = "$name (0x%04X)".format(id)

    companion object {
        const val ATMEL = 0x1F3D
        val BOOTLEGS = setOf(0xBF4B, 0xBF5B, 0xFFFF, 0xBF6D)

        private val TABLE: Map<Int, AgbFlashChip> = listOf(
            AgbFlashChip(0xBFD4, "SST 39VF512", 0x10000, true),
            AgbFlashChip(0x1F3D, "Atmel AT29LV512", 0x10000, true),
            AgbFlashChip(0xC21C, "Macronix MX29L512", 0x10000, true),
            AgbFlashChip(0x321B, "Panasonic MN63F805MNP", 0x10000, true),
            AgbFlashChip(0xC209, "Macronix MX29L010", 0x20000, true),
            AgbFlashChip(0x6213, "SANYO LE26FV10N1TS", 0x20000, true),
            AgbFlashChip(0xBF4B, "Unlicensed SST25VF064C", 0x20000, true),
            AgbFlashChip(0xBF5B, "Unlicensed SST49LF080A", 0x20000, true),
            AgbFlashChip(0xBF6D, "Unlicensed SST39VF6401B", 0x20000, true),
            AgbFlashChip(0xFFFF, "Unlicensed 0xFFFF", 0x20000, true),
        ).associateBy { it.id }

        /** The table entry for [id], or an "unknown chip" placeholder (FlashGBX continues with it, LK:1660-1668). */
        fun lookup(id: Int): AgbFlashChip = TABLE[id] ?: AgbFlashChip(id, "Unknown flash chip", 0, false)

        fun isKnown(id: Int): Boolean = id in TABLE
    }
}

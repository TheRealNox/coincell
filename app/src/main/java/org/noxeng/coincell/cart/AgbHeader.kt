package org.noxeng.coincell.cart

/** Parsed GBA cartridge header (spec 5.5), with the save-type decision that needs no cart writes. */
data class AgbHeader(
    override val raw: ByteArray,
    override val title: String,
    val gameCode: String,
    val makerCode: String,
    val fixed96Valid: Boolean,
    val version: Int,
    val headerChecksumStored: Int,
    val headerChecksumComputed: Int,
    override val logoValid: Boolean,
    val emptyLogoRegion: Boolean,
    val emptyNoCart: Boolean,
    /** Lowercase SHA-1 used as key of FlashGBX's db_AGB.json (not bundled yet). */
    val dbKey: String,
    override val save: SaveInfo,
) : CartHeader {
    override val headerChecksumValid: Boolean get() = headerChecksumStored == headerChecksumComputed
    override val likelyNoCart: Boolean get() = emptyLogoRegion || emptyNoCart

    override fun equals(other: Any?): Boolean = other is AgbHeader && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    companion object {
        const val HEADER_SIZE = 0x180
        const val LOGO_SHA1 = "17daa0fec02fc33c0f6abb549a8b80b6613b48ee"

        private val EMPTY_FULL_SHA1 = setOf(
            "4fe93eeebc5593fe2e231a3986ce86c95c1100dd",
            "a503a1b5f5ddbefc87c79b1359f7e1a5cfe0ac9f",
            "4686e381b24a2db07de83d452fa31e8a044b3a50",
        )
        private val EMPTY_MID_SHA1 = setOf(
            "2bdc7def6c481fbfeeb880b1d0fdf6575d6a39be",
            "09b90e535e8550f890a4f477137e4559a5c0a445",
        )

        /** AgbSaveTypes.SAVE_TYPES (CT:97-108): index -> (size, name). */
        val SAVE_TYPES: Map<Int, Pair<Int, String>> = mapOf(
            0 to (0 to "None"),
            1 to (512 to "4K EEPROM"),
            2 to (8192 to "64K EEPROM"),
            3 to (32768 to "256K SRAM/FRAM"),
            4 to (65536 to "512K FLASH"),
            5 to (131072 to "1M FLASH"),
            6 to (1048576 to "8M DACS"),
            7 to (65536 to "Unlicensed 512K SRAM"),
            8 to (131072 to "Unlicensed 1M SRAM"),
            9 to (0 to "Unlicensed batteryless SRAM"),
        )

        fun parse(buffer: ByteArray): AgbHeader {
            require(buffer.size >= HEADER_SIZE) { "AGB header needs $HEADER_SIZE bytes, got ${buffer.size}" }
            val b = buffer.copyOf(HEADER_SIZE)

            val emptyNoCart = sha1Hex(b, 0, 0x180) in EMPTY_FULL_SHA1 || sha1Hex(b, 0x10, 0x50) in EMPTY_MID_SHA1
            val emptyLogo = (0x04 until 0xA0).all { b[it] == b[0x04] }
            val logoValid = sha1Hex(b, 0x04, 0xA0) == LOGO_SHA1

            val title = cleanTitle(b.copyOfRange(0xA0, 0xAC)).replace("\n", "")
            val gameCode = cleanTitle(b.copyOfRange(0xAC, 0xB0)).replace("_", "")
            val maker = cleanTitle(b.copyOfRange(0xB0, 0xB2)).replace("_", "")

            var sum = 0
            for (i in 0xA0..0xBC) sum += b.u8(i)
            val checksum = (-sum - 0x19) and 0xFF

            val dbKey = if (gameCode.length == 4 && gameCode[0] == 'M') sha1Hex(b, 0, 0x100) else sha1Hex(b, 0, 0x180)

            return AgbHeader(
                raw = b,
                title = title,
                gameCode = gameCode,
                makerCode = maker,
                fixed96Valid = b.u8(0xB2) == 0x96,
                version = b.u8(0xBC),
                headerChecksumStored = b.u8(0xBD),
                headerChecksumComputed = checksum,
                logoValid = logoValid,
                emptyLogoRegion = emptyLogo,
                emptyNoCart = emptyNoCart,
                dbKey = dbKey,
                save = resolveSaveFromHeader(b),
            )
        }

        /**
         * `GetSaveTypeFromHeaderMarker` (RFA:52-111): Lesserkuma patcher marker 'L' at 0xBE, flags at 0xBF.
         * Returns (save type index, size) or null if there is no valid marker.
         */
        fun saveTypeFromMarker(b: ByteArray): Pair<Int, Int>? {
            if (b.u8(0xBE) != 0x4C) return null
            val f = b.u8(0xBF)
            val size = when (f and 7) {
                0 -> 0
                1 -> 512
                2 -> 8192
                3 -> 32768
                4 -> 65536
                5 -> 131072
                else -> return null
            }
            val medium = (f shr 3) and 3
            val batteryless = f and 0x20 != 0
            return when (medium) {
                0 -> if (size == 0 && !batteryless) 0 to 0 else null
                1 -> when {
                    batteryless -> if (size != 0) 9 to size else null
                    size <= 32768 -> 3 to size
                    size == 65536 -> 7 to size
                    size == 131072 -> 8 to size
                    else -> null
                }
                2 -> when {
                    batteryless -> null
                    size == 0 -> 1 to 0 // 4K vs 64K unresolved
                    size == 512 -> 1 to 512
                    size == 8192 -> 2 to 8192
                    else -> null
                }
                else -> if (batteryless) null else if (size == 131072) 5 to size else 4 to size
            }
        }

        private fun resolveSaveFromHeader(b: ByteArray): SaveInfo {
            val marker = saveTypeFromMarker(b)
            if (marker != null) {
                val (type, size) = marker
                val name = SAVE_TYPES[type]?.second ?: "type $type"
                val note = if (type == 1 && size == 0) "Marker says EEPROM but not 4K/64K; needs probing." else null
                return SaveInfo(SaveInfo.Status.FROM_HEADER, type, name, size, note)
            }
            return SaveInfo(
                SaveInfo.Status.UNRESOLVED, null, "Unknown", null,
                "No patch marker in header. The GBA save type is not stored in the ROM header; " +
                    "the FlashGBX database lookup is not bundled yet and Flash/EEPROM probing needs cart writes " +
                    "(not implemented). Use the read-only SRAM probe for a hint.",
            )
        }
    }
}

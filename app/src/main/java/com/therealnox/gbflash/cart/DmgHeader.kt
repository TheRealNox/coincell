package com.therealnox.gbflash.cart

/** Parsed DMG/CGB cartridge header (spec 5.2 / 5.3). Pure function of the 0x180 header bytes. */
data class DmgHeader(
    override val raw: ByteArray,
    override val title: String,
    val gameCode: String?,
    val cgbFlag: Int,
    val sgbFlag: Int,
    /** Raw cart type byte at 0x147. */
    val cartTypeByte: Int,
    /** FlashGBX "mapper_raw": cart type with synthetic ids (e.g. 0x110 = MBC30). */
    val mapperRaw: Int,
    val mapperName: String,
    val romSizeCode: Int,
    val romSizeBytes: Long?,
    val ramSizeCode: Int,
    val version: Int,
    val headerChecksumStored: Int,
    val headerChecksumComputed: Int,
    val globalChecksumStored: Int,
    val globalChecksumComputed: Int,
    override val logoValid: Boolean,
    val emptyLogoRegion: Boolean,
    val allZero: Boolean,
    val supportedMapper: Boolean,
    val hasRtcDeclared: Boolean,
    override val save: SaveInfo,
) : CartHeader {
    override val headerChecksumValid: Boolean get() = headerChecksumStored == headerChecksumComputed
    override val likelyNoCart: Boolean get() = emptyLogoRegion || allZero

    override fun equals(other: Any?): Boolean = other is DmgHeader && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    companion object {
        const val HEADER_SIZE = 0x180
        const val LOGO_SHA1 = "0745fdef34132d1b3d488cfbdf0379a39fd54b4c"

        private val MAPPER_NAMES = mapOf(
            0x00 to "ROM only", 0x01 to "MBC1", 0x02 to "MBC1+RAM", 0x03 to "MBC1+RAM+BATTERY",
            0x05 to "MBC2", 0x06 to "MBC2+BATTERY", 0x08 to "ROM+RAM", 0x09 to "ROM+RAM+BATTERY",
            0x0B to "MMM01", 0x0C to "MMM01+RAM", 0x0D to "MMM01+RAM+BATTERY",
            0x0F to "MBC3+TIMER+BATTERY", 0x10 to "MBC3+TIMER+RAM+BATTERY", 0x11 to "MBC3",
            0x12 to "MBC3+RAM", 0x13 to "MBC3+RAM+BATTERY",
            0x19 to "MBC5", 0x1A to "MBC5+RAM", 0x1B to "MBC5+RAM+BATTERY",
            0x1C to "MBC5+RUMBLE", 0x1D to "MBC5+RUMBLE+RAM", 0x1E to "MBC5+RUMBLE+RAM+BATTERY",
            0x20 to "MBC6", 0x22 to "MBC7+SENSOR+RUMBLE+RAM+BATTERY",
            0xFC to "POCKET CAMERA", 0xFD to "BANDAI TAMA5", 0xFE to "HuC3", 0xFF to "HuC1+RAM+BATTERY",
            0x110 to "MBC30+TIMER+RAM+BATTERY",
        )

        // IsSupportedMbc whitelist (LK:277-278), restricted to ids this parser can produce.
        private val SUPPORTED = setOf(
            0x00, 0x01, 0x02, 0x03, 0x05, 0x06, 0x08, 0x09, 0x0B, 0x0D, 0x0F, 0x10, 0x11, 0x12, 0x13,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x20, 0x22, 0xFC, 0xFD, 0xFE, 0xFF, 0x110,
        )

        private val RTC_MAPPERS = setOf(0x0F, 0x10, 0x110, 0xFD, 0xFE)

        /** save_type -> (size, label), CT:239-255. */
        private val RAM_TYPES = mapOf(
            0x00 to (0 to "None"),
            0x01 to (0x800 to "16K SRAM (2 KiB)"),
            0x02 to (0x2000 to "64K SRAM (8 KiB)"),
            0x03 to (0x8000 to "256K SRAM (32 KiB)"),
            0x05 to (0x10000 to "512K SRAM (64 KiB)"),
            0x04 to (0x20000 to "1M SRAM (128 KiB)"),
            0x100 to (0x200 to "4K SRAM (512 x 4 bit, MBC2)"),
            0x101 to (0x100 to "MBC7 2K EEPROM"),
            0x102 to (0x200 to "MBC7 4K EEPROM"),
            0x103 to (0x20 to "TAMA5"),
            0x104 to (0x108000 to "MBC6 SRAM+FLASH"),
        )

        fun parse(buffer: ByteArray): DmgHeader {
            require(buffer.size >= HEADER_SIZE) { "DMG header needs $HEADER_SIZE bytes, got ${buffer.size}" }
            val b = buffer.copyOf(HEADER_SIZE)

            val logoValid = sha1Hex(b, 0x104, 0x134) == LOGO_SHA1
            val emptyLogo = (0x104 until 0x134).all { b[it] == b[0x104] }
            val allZero = b.all { it.toInt() == 0 }

            val cgbFlag = b.u8(0x143)
            val cgb = cgbFlag == 0x80 || cgbFlag == 0xC0
            val rawTitle = b.copyOfRange(0x134, if (cgb) 0x143 else 0x144)
            var title = cleanTitle(rawTitle)
            var gameCode: String? = null
            // 15-byte CGB titles carry a 4 character manufacturer code in the last bytes (RFD:114-119).
            if (cgb && rawTitle.dropLastWhile { it.toInt() == 0 }.size == 15) {
                val code = cleanTitle(b.copyOfRange(0x13F, 0x143))
                if (code.length == 4 && code[0] in "ABHKV" && code[3] in "ABDEFIJKPSUXY") {
                    gameCode = code
                    title = cleanTitle(b.copyOfRange(0x134, 0x13F)).trimEnd('_')
                }
            }

            val cartType = b.u8(0x147)
            val romCode = b.u8(0x148)
            val ramCode = b.u8(0x149)

            // Synthetic ids / RAM keys (RFD:150-171, CLI:1257-1278).
            var mapperRaw = cartType
            var ramRaw = ramCode
            when {
                cartType == 0x06 -> ramRaw = 0x100
                cartType == 0x10 && ramCode == 0x05 -> mapperRaw = 0x110
                cartType == 0x20 -> ramRaw = 0x104
                cartType == 0x22 && (title == "KORO2 KIRBY" || title == "KIRBY TNT") -> ramRaw = 0x101
                cartType == 0x22 && title == "CMASTER" -> ramRaw = 0x102
                cartType == 0xFD -> ramRaw = 0x103
            }

            var sum = 0
            for (i in 0x134..0x14C) sum = (sum - b.u8(i) - 1) and 0xFF
            var global = 0
            for (i in b.indices) if (i != 0x14E && i != 0x14F) global += b.u8(i)

            val saveEntry = RAM_TYPES[ramRaw]
            val save = when {
                saveEntry == null -> SaveInfo(
                    SaveInfo.Status.UNKNOWN, ramRaw, "Unknown RAM size code 0x%02X".format(ramRaw), null,
                )
                ramRaw == 0 -> SaveInfo(
                    SaveInfo.Status.NONE, 0, "None", 0,
                    "Header declares no RAM; if the cart type has BATTERY the save type must be chosen manually.",
                )
                else -> SaveInfo(SaveInfo.Status.FROM_HEADER, ramRaw, saveEntry.second, saveEntry.first)
            }

            return DmgHeader(
                raw = b,
                title = title,
                gameCode = gameCode,
                cgbFlag = cgbFlag,
                sgbFlag = b.u8(0x146),
                cartTypeByte = cartType,
                mapperRaw = mapperRaw,
                mapperName = MAPPER_NAMES[mapperRaw] ?: "Unknown (0x%02X)".format(cartType),
                romSizeCode = romCode,
                romSizeBytes = if (romCode in 0..12) 0x8000L shl romCode else null,
                ramSizeCode = ramCode,
                version = b.u8(0x14C),
                headerChecksumStored = b.u8(0x14D),
                headerChecksumComputed = sum,
                globalChecksumStored = (b.u8(0x14E) shl 8) or b.u8(0x14F),
                globalChecksumComputed = global and 0xFFFF,
                logoValid = logoValid,
                emptyLogoRegion = emptyLogo,
                allZero = allZero,
                supportedMapper = mapperRaw in SUPPORTED,
                hasRtcDeclared = mapperRaw in RTC_MAPPERS,
                save = save,
            )
        }
    }
}

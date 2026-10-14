package com.therealnox.gbflash.library

import com.therealnox.gbflash.cart.DmgHeader

/**
 * Identity and descriptive metadata of a game in the library. Platform-neutral on purpose: a GBA
 * game would use platform "AGB" with title + game code + header checksum (not implemented yet).
 */
data class GameInfo(
    /** "DMG" (GB / GBC) or, later, "AGB". */
    val platform: String,
    val title: String,
    /** Directory name and identity: `<platform>_<TITLE>_<checksum hex>`, e.g. `DMG_POKEMON-BLUE_56A4`. */
    val key: String,
    val cartType: String,
    val romSize: Long?,
    val globalChecksum: Int,
    val headerChecksum: Int,
    val gameCode: String?,
) {
    companion object {
        const val MAX_TITLE_IN_KEY = 32

        /** Upper case, runs of anything but A-Z/0-9 become one `-`; safe as a path component. */
        fun sanitizeTitle(title: String): String {
            val s = title.uppercase().replace(Regex("[^A-Z0-9]+"), "-").trim('-').take(MAX_TITLE_IN_KEY).trim('-')
            return s.ifEmpty { "UNTITLED" }
        }

        fun makeKey(platform: String, title: String, checksum: Int): String =
            "${platform}_${sanitizeTitle(title)}_%04X".format(checksum and 0xFFFF)

        /** Identity of a GB / GBC cartridge: platform + cleaned title + stored global checksum (0x14E). */
        fun fromDmg(h: DmgHeader): GameInfo = GameInfo(
            platform = "DMG",
            title = h.title,
            key = makeKey("DMG", h.title, h.globalChecksumStored),
            cartType = "0x%02X %s".format(h.cartTypeByte, h.mapperName),
            romSize = h.romSizeBytes,
            globalChecksum = h.globalChecksumStored,
            headerChecksum = h.headerChecksumStored,
            gameCode = h.gameCode,
        )
    }
}

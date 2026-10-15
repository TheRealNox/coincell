package org.noxeng.coincell.library

import org.noxeng.coincell.cart.AgbHeader
import org.noxeng.coincell.cart.DmgHeader

/**
 * Identity and descriptive metadata of a game in the library. Platform-neutral: a GB / GBC game is platform "DMG"
 * (title + global checksum), a GBA game is platform "AGB" (title + game code + header checksum).
 */
data class GameInfo(
    /** "DMG" (GB / GBC) or "AGB" (GBA). */
    val platform: String,
    val title: String,
    /** Directory name and identity: `<platform>_<TITLE>_<checksum hex>`, e.g. `DMG_POKEMON-BLUE_56A4`. */
    val key: String,
    val cartType: String,
    val romSize: Long?,
    val globalChecksum: Int,
    val headerChecksum: Int,
    val gameCode: String?,
    /**
     * Optional friendly name chosen by the user (`displayName` in `game.json`). Purely cosmetic: never part of the
     * key, the folder name or any restore matching rule.
     */
    val displayName: String? = null,
    /** The FlashGBX database name (GB/GBC or GBA) seen when a backup was stored (`databaseName` in `game.json`), also usable offline. */
    val databaseName: String? = null,
    /**
     * Set for a game created by importing a `.sav` without its cartridge (`importedPlatform` in `game.json`): the
     * platform the user picked. Such a game has no header identity (checksums are 0, [title] is the name the user
     * gave) until it is linked to a cartridge, which moves its backups into that cartridge's game.
     */
    val importedPlatform: DisplayPlatform? = null,
) {
    /** Created from a file and not linked to a cartridge yet. */
    val isFromFile: Boolean get() = importedPlatform != null

    /** The label without a custom name: the FlashGBX database name when known, otherwise the header title. */
    val defaultLabel: String
        get() = databaseName?.trim()?.ifEmpty { null } ?: title.ifBlank { "(untitled)" }

    /** The one label shown everywhere: custom name, else [defaultLabel]. */
    val label: String get() = displayName?.trim()?.ifEmpty { null } ?: defaultLabel

    /** The cartridge header title when it differs from [label], to show as secondary text; null otherwise. */
    val secondaryTitle: String? get() = title.trim().takeIf { it.isNotEmpty() && it != label }

    /** What identifies the cartridge besides the title: global checksum for GB / GBC, game code + header checksum for GBA. */
    val identityText: String
        get() = if (isFromFile) {
            "Imported from a file, not linked to a cartridge"
        } else if (platform == "AGB") {
            (gameCode?.let { "Game code $it  ·  " } ?: "") + "Header checksum 0x%02X".format(headerChecksum and 0xFF)
        } else {
            "Checksum 0x%04X".format(globalChecksum and 0xFFFF)
        }

    /** This (cartridge) identity labelled with the custom name stored in the library, if [stored] has one. */
    fun withNameFrom(stored: GameInfo?): GameInfo =
        if (stored == null) this else copy(displayName = stored.displayName, databaseName = databaseName ?: stored.databaseName)

    companion object {
        const val MAX_TITLE_IN_KEY = 32

        /** Longest custom name kept. */
        const val NAME_MAX_LENGTH = 60

        /** Trimmed and capped; null when nothing is left (meaning "not set"). */
        fun normalizeName(name: String?): String? = name?.trim()?.take(NAME_MAX_LENGTH)?.trim()?.ifEmpty { null }

        /** `AGB_<TITLE>_<GAMECODE>_<header checksum hex>`, e.g. `AGB_POKEMON-SAPP_AXPF_5C`. */
        fun makeAgbKey(title: String, gameCode: String, headerChecksum: Int): String {
            val code = gameCode.uppercase().replace(Regex("[^A-Z0-9]"), "").ifEmpty { "NOCODE" }
            return "AGB_${sanitizeTitle(title)}_${code}_%02X".format(headerChecksum and 0xFF)
        }

        /** Upper case, runs of anything but A-Z/0-9 become one `-`; safe as a path component. */
        fun sanitizeTitle(title: String): String {
            val s = title.uppercase().replace(Regex("[^A-Z0-9]+"), "-").trim('-').take(MAX_TITLE_IN_KEY).trim('-')
            return s.ifEmpty { "UNTITLED" }
        }

        /**
         * A game made from an imported file: `<platform>_<NAME>_FILE-<suffix>`. The `FILE-` part can never appear in
         * a cartridge key (those end in hex checksums), so the two kinds never collide.
         */
        fun fromFile(platform: DisplayPlatform, name: String, suffix: String): GameInfo {
            val p = if (platform == DisplayPlatform.GBA) "AGB" else "DMG"
            val title = normalizeName(name) ?: "Imported save"
            return GameInfo(
                platform = p,
                title = title,
                key = "${p}_${sanitizeTitle(title)}_FILE-${suffix.uppercase().replace(Regex("[^A-Z0-9]"), "")}",
                cartType = "Imported file",
                romSize = null,
                globalChecksum = 0,
                headerChecksum = 0,
                gameCode = null,
                importedPlatform = platform,
            )
        }

        fun makeKey(platform: String, title: String, checksum: Int): String =
            "${platform}_${sanitizeTitle(title)}_%04X".format(checksum and 0xFFFF)

        /** Identity of a GB / GBC cartridge: platform + cleaned title + stored global checksum (0x14E). */
        fun fromDmg(h: DmgHeader, databaseName: String? = null): GameInfo = GameInfo(
            platform = "DMG",
            title = h.title,
            key = makeKey("DMG", h.title, h.globalChecksumStored),
            cartType = "0x%02X %s".format(h.cartTypeByte, h.mapperName),
            romSize = h.romSizeBytes,
            globalChecksum = h.globalChecksumStored,
            headerChecksum = h.headerChecksumStored,
            gameCode = h.gameCode,
            databaseName = normalizeName(databaseName),
        )

        /**
         * Identity of a GBA cartridge: platform + cleaned title + game code + header checksum (0xBD). The header
         * checksum covers the version byte, so another revision is another key. `globalChecksum` is unused (0).
         */
        fun fromAgb(h: AgbHeader, romSize: Long? = null, databaseName: String? = null): GameInfo = GameInfo(
            platform = "AGB",
            title = h.title,
            key = makeAgbKey(h.title, h.gameCode, h.headerChecksumStored),
            cartType = "GBA",
            romSize = romSize,
            globalChecksum = 0,
            headerChecksum = h.headerChecksumStored,
            gameCode = h.gameCode.ifEmpty { null },
            databaseName = normalizeName(databaseName),
        )
    }
}

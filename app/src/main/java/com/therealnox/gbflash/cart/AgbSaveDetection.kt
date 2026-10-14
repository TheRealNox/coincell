package com.therealnox.gbflash.cart

import java.io.BufferedReader
import java.io.InputStream

/** Where the save type of a GBA cartridge came from, in the order the app tries them. */
enum class SaveTypeSource(val label: String, val json: String) {
    /** The Lesserkuma patcher marker `L` in the ROM header (RFA:52-111). */
    HEADER_MARKER("the patch marker in the ROM header", "marker"),

    /** FlashGBX's `db_AGB.json`, keyed by the header SHA-1. */
    DATABASE("the FlashGBX database", "database"),

    /** Flash ID, SRAM and EEPROM probing of the cartridge. */
    PROBE("a probe of the cartridge", "probe"),

    /** Picked by the user from the list. */
    USER("your choice", "user"),
    ;

    companion object {
        fun fromJson(value: String?): SaveTypeSource? = entries.firstOrNull { it.json == value }
    }
}

/** One row of the bundled FlashGBX database. */
class AgbDbEntry(val saveType: Int, val gameCode: String, val name: String, val video: Boolean) {
    val saveSize: Int get() = AgbSaveTypes.size(saveType)
}

/**
 * FlashGBX's GBA database (`config/db_AGB.json`), reduced to what the app needs and bundled as
 * `assets/db_agb.tsv` (GPL-3.0, see About). The key is the lowercase SHA-1 FlashGBX calls `header_sha1`
 * (RFA:227-230): the first 0x180 header bytes, or only the first 0x100 for game codes starting with 'M'.
 * [AgbHeader.dbKey] is exactly that key. The database holds no clock information; the RTC is probed.
 */
class AgbDatabase(private val entries: Map<String, AgbDbEntry>) {
    val size: Int get() = entries.size

    fun lookup(header: AgbHeader): AgbDbEntry? = entries[header.dbKey]

    companion object {
        val EMPTY = AgbDatabase(emptyMap())

        /** Parses the TSV: `sha1 TAB save type TAB flags TAB game code TAB name`; `#` lines are comments. */
        fun parse(input: InputStream): AgbDatabase {
            val map = HashMap<String, AgbDbEntry>(4096)
            input.bufferedReader(Charsets.UTF_8).use { reader: BufferedReader ->
                reader.forEachLine { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEachLine
                    val f = line.split('\t', limit = 5)
                    if (f.size < 5) return@forEachLine
                    val st = f[1].toIntOrNull() ?: return@forEachLine
                    map[f[0]] = AgbDbEntry(st, f[3], f[4], video = f[2].contains('V'))
                }
            }
            return AgbDatabase(map)
        }
    }
}

/** What is known about the save memory of the inserted GBA cartridge. */
class AgbSaveDetection(
    /** FlashGBX save type index, or null while unknown. */
    val typeId: Int?,
    val source: SaveTypeSource?,
    /** Shown beside the type: the database title, "EEPROM size unresolved", ... */
    val note: String? = null,
    /** The database title, when the entry was found there. */
    val dbName: String? = null,
    /** The Flash chip found by the probe, as text. */
    val chipLabel: String? = null,
) {
    /** True when the type is not trustworthy enough to hide the chooser: it is not from the marker or the database. */
    val uncertain: Boolean get() = typeId == null || source == SaveTypeSource.PROBE

    val label: String get() = typeId?.let { AgbSaveTypes.label(it) } ?: "Unknown"

    companion object {
        val UNKNOWN = AgbSaveDetection(null, null)

        /**
         * Steps (a) and (b) of the detection order, which need no access to the cartridge: the patcher marker, then
         * the FlashGBX database. A marker that only says "EEPROM" without a size leaves 4K / 64K for the probe.
         */
        fun fromHeader(header: AgbHeader, db: AgbDatabase): AgbSaveDetection {
            val marker = AgbHeader.saveTypeFromMarker(header.raw)
            if (marker != null) {
                val (type, size) = marker
                if (type == AgbSaveTypes.EEPROM_4K && size == 0) {
                    return AgbSaveDetection(null, null, "The patch marker says EEPROM but not 4K or 64K: it needs a probe or your choice.")
                }
                return AgbSaveDetection(type, SaveTypeSource.HEADER_MARKER)
            }
            val entry = db.lookup(header)
            if (entry != null) {
                val note = when {
                    entry.video -> "GBA Video cartridge (no save)."
                    entry.saveType !in AgbHeader.SAVE_TYPES -> "The database lists save type ${entry.saveType}, which this app does not know."
                    else -> null
                }
                val type = if (entry.video) AgbSaveTypes.NONE else entry.saveType.takeIf { it in AgbHeader.SAVE_TYPES }
                return AgbSaveDetection(type, if (type != null) SaveTypeSource.DATABASE else null, note, dbName = entry.name)
            }
            return AgbSaveDetection(null, null, "Not in the FlashGBX database.")
        }
    }
}

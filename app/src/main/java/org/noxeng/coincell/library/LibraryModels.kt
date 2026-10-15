package org.noxeng.coincell.library

/** Platform shown on badges and filters. The library stores only "DMG" / "AGB". */
enum class DisplayPlatform(val label: String) {
    GB("GB"),
    GBC("GBC"),
    GBA("GBA"),
}

/**
 * GB vs GBC is not stored in `game.json`. A GBC cartridge with the 4 character manufacturer code in its
 * header (every game from 1998 on) has [GameInfo.gameCode] set; older dual-mode titles without a code are
 * shown as GB. The cartridge itself is handled identically by the reader, so nothing depends on this. A game imported
 * from a file shows the platform the user picked.
 */
val GameInfo.displayPlatform: DisplayPlatform
    get() = when {
        importedPlatform != null -> importedPlatform
        platform == "AGB" -> DisplayPlatform.GBA
        gameCode != null -> DisplayPlatform.GBC
        else -> DisplayPlatform.GB
    }

/** One game of the library with its readable backups, newest first (never empty). */
data class GameSummary(val game: GameInfo, val backups: List<BackupEntry>) {
    val key: String get() = game.key
    /** The label shown everywhere ([GameInfo.label]). */
    val title: String get() = game.label
    val count: Int get() = backups.size
    val latestMillis: Long get() = backups.maxOf { it.timestampMillis }
    val hasClock: Boolean get() = backups.any { it.rtcIncluded }
    val hasSuspect: Boolean get() = backups.any { it.suspect }
    val platform: DisplayPlatform get() = game.displayPlatform
}

sealed interface LibraryState {
    data object Loading : LibraryState

    data class Loaded(val games: List<GameSummary>) : LibraryState

    data class Failed(val message: String) : LibraryState
}

enum class LibrarySort(val label: String) {
    LAST_BACKUP("Last backup"),
    TITLE("Title"),
}

enum class PlatformFilter(val label: String) {
    ALL("All"),
    GB("GB"),
    GBC("GBC"),
    GBA("GBA"),
}

data class LibraryQuery(
    val search: String = "",
    val sort: LibrarySort = LibrarySort.LAST_BACKUP,
    val filter: PlatformFilter = PlatformFilter.ALL,
) {
    val isFiltering: Boolean get() = search.isNotBlank() || filter != PlatformFilter.ALL
}

/** Friendly name, database name, header title or game code contains [needle] (case-insensitive). */
fun GameInfo.matches(needle: String): Boolean =
    listOfNotNull(displayName, databaseName, title, gameCode).any { it.contains(needle, ignoreCase = true) }

/** Search (friendly name, database name, title, game code, notes), platform filter and sort. Pure, so it is unit tested. */
fun List<GameSummary>.applyQuery(query: LibraryQuery): List<GameSummary> {
    val needle = query.search.trim()
    val filtered = filter { g ->
        val platformOk = when (query.filter) {
            PlatformFilter.ALL -> true
            PlatformFilter.GB -> g.platform == DisplayPlatform.GB
            PlatformFilter.GBC -> g.platform == DisplayPlatform.GBC
            PlatformFilter.GBA -> g.platform == DisplayPlatform.GBA
        }
        platformOk && (
            needle.isEmpty() ||
                g.game.matches(needle) ||
                g.backups.any { it.note.contains(needle, ignoreCase = true) }
            )
    }
    return when (query.sort) {
        LibrarySort.LAST_BACKUP -> filtered.sortedWith(compareByDescending<GameSummary> { it.latestMillis }.thenBy { it.title.lowercase() })
        LibrarySort.TITLE -> filtered.sortedWith(compareBy<GameSummary> { it.title.lowercase() }.thenByDescending { it.latestMillis })
    }
}

/**
 * The game whose cartridge is currently read, or null. Identity is the library key (platform + cleaned
 * title + stored global checksum), the same rule the restore review uses for "same game".
 */
fun List<GameSummary>.inReader(cartKey: String?): GameSummary? = cartKey?.let { k -> firstOrNull { it.key == k } }

package org.noxeng.coincell.library

import org.noxeng.coincell.editor.PokemonSaveKind
import org.noxeng.coincell.editor.PokemonSaveProbe
import org.noxeng.coincell.save.SaveCheck
import org.noxeng.coincell.save.SaveImport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * App-wide, offline view of the [SaveLibrary]: the list of games for every screen, soft delete (hide now,
 * remove the files when the Undo window ends) and note edits. No reader involved.
 */
class LibraryStore(
    private val library: SaveLibrary,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val raw = MutableStateFlow<LibraryState>(LibraryState.Loading)

    /** Entries deleted from the user's point of view but still on disk (keys `gameKey/id`). */
    private val pendingDelete = MutableStateFlow<Set<String>>(emptySet())
    private var reloadJob: Job? = null

    /** What the screens show: [raw] minus the pending deletions. */
    val state: StateFlow<LibraryState> = combine(raw, pendingDelete) { r, hidden ->
        if (r is LibraryState.Loaded && hidden.isNotEmpty()) {
            LibraryState.Loaded(
                r.games.mapNotNull { g ->
                    val kept = g.backups.filterNot { token(it) in hidden }
                    if (kept.isEmpty()) null else g.copy(backups = kept)
                },
            )
        } else {
            r
        }
    }.stateIn(scope, SharingStarted.Eagerly, LibraryState.Loading)

    init {
        reload()
    }

    /** Re-reads the disk. Cheap (a few small JSON files); the previous list stays visible meanwhile. */
    fun reload() {
        reloadJob?.cancel()
        reloadJob = scope.launch {
            try {
                raw.value = LibraryState.Loaded(withContext(io) { readAll() })
            } catch (e: Exception) {
                if (raw.value !is LibraryState.Loaded) raw.value = LibraryState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun readAll(): List<GameSummary> =
        library.games().mapNotNull { it.optString("key").ifEmpty { null } }.mapNotNull { key ->
            val game = library.readGame(key) ?: return@mapNotNull null
            val backups = library.list(key)
            if (backups.isEmpty()) null else GameSummary(game, backups)
        }

    /** Hides [entry] immediately. Call [commitDelete] when the Undo window is over, or [undoDelete]. */
    fun softDelete(entry: BackupEntry) {
        pendingDelete.update { it + token(entry) }
    }

    fun undoDelete(entry: BackupEntry) {
        pendingDelete.update { it - token(entry) }
    }

    /** Removes the files of a soft-deleted [entry]. On failure the entry comes back and the error is rethrown. */
    suspend fun commitDelete(entry: BackupEntry) {
        try {
            withContext(io) { library.delete(entry) }
        } finally {
            pendingDelete.update { it - token(entry) }
            reload()
        }
    }

    /** Stores [note] (trimmed, at most [NOTE_MAX_LENGTH] characters) and returns the updated entry. */
    suspend fun updateNote(entry: BackupEntry, note: String): BackupEntry {
        val clean = note.trim().take(NOTE_MAX_LENGTH)
        val updated = withContext(io) { library.updateNote(entry, clean) }
        reload()
        return updated
    }

    /** Sets or (blank [name]) clears the friendly name of [gameKey]; returns the updated game. */
    suspend fun renameGame(gameKey: String, name: String?): GameInfo {
        val updated = withContext(io) { library.setDisplayName(gameKey, name) }
        reload()
        return updated
    }

    /**
     * Delete with Undo (docs/UI_DESIGN.md 3.3): hides [entry] at once, then asks [askUndo] (the snackbar,
     * about 8 seconds). If it returns true the entry is back; otherwise, or if the caller goes away, the
     * files are removed.
     */
    suspend fun deleteWithUndo(entry: BackupEntry, askUndo: suspend () -> Boolean) {
        softDelete(entry)
        var undo = false
        try {
            undo = askUndo()
        } finally {
            if (undo) undoDelete(entry) else withContext(NonCancellable) { commitDelete(entry) }
        }
    }

    /**
     * Stores an edited save as a new `edited` entry next to [parent] (see [SaveLibrary.storeEdited]); the parent is
     * never modified. Returns the new entry once the list has been refreshed.
     */
    suspend fun storeEdited(parent: BackupEntry, save: ByteArray, note: String, appVersion: String): BackupEntry {
        val stored = withContext(io) { library.storeEdited(parent, save, note.trim().take(NOTE_MAX_LENGTH), appVersion) }
        reload()
        return stored
    }

    /**
     * Imports [bytes] (the file [fileName]) as the first backup of a new game made from a file, on [platform], named
     * [name]. Throws [IOException] with a message for the user when the file is not a save of that platform.
     */
    suspend fun importNewGame(platform: DisplayPlatform, name: String, fileName: String, bytes: ByteArray, appVersion: String): BackupEntry {
        val gba = platform == DisplayPlatform.GBA
        val r = SaveImport.parseLoose(bytes, gba)
        if (r is SaveImport.Result.Rejected) throw IOException(r.reason)
        r as SaveImport.Result.Ok
        val stored = withContext(io) {
            library.storeInNewFileGame(platform, name, importedBackup(r, fileName, appVersion, rtcDeclared = !gba && r.rtcFooter != null))
        }
        reload()
        return stored
    }

    /**
     * Imports [bytes] into an existing [game]: the file must be the size of the game's newest backup (plus a clock
     * footer when the game has a clock; GBA files may always carry FlashGBX's footer). GBA entries keep the save
     * type of that backup, so a later restore checks it as usual.
     */
    suspend fun importIntoGame(game: GameSummary, fileName: String, bytes: ByteArray, appVersion: String): BackupEntry {
        val newest = game.backups.first()
        val gba = game.game.platform == "AGB"
        val clock = !gba && game.backups.any { it.rtcDeclared || it.rtcIncluded }
        val r = if (gba) SaveImport.parseAgb(bytes, newest.size) else SaveImport.parse(bytes, newest.size, clockCart = clock)
        if (r is SaveImport.Result.Rejected) throw IOException(r.reason)
        r as SaveImport.Result.Ok
        val typed = game.backups.firstOrNull { it.saveType != null }
        val backup = importedBackup(r, fileName, appVersion, rtcDeclared = clock, saveType = typed?.saveType, saveTypeSource = typed?.saveTypeSource)
        val stored = withContext(io) { library.store(game.game, backup) }
        reload()
        return stored
    }

    private fun importedBackup(
        r: SaveImport.Result.Ok,
        fileName: String,
        appVersion: String,
        rtcDeclared: Boolean,
        saveType: Int? = null,
        saveTypeSource: org.noxeng.coincell.cart.SaveTypeSource? = null,
    ) = NewBackup(
        save = r.save,
        rtcFooter = r.rtcFooter,
        rtcProbePresent = null,
        rtcDeclared = rtcDeclared,
        firmware = "",
        appVersion = appVersion,
        verified = false,
        suspectReason = SaveCheck.suspectReason(r.save),
        note = "Imported from $fileName",
        kind = BackupKind.IMPORTED,
        originalFileName = fileName,
        saveType = saveType,
        saveTypeSource = saveTypeSource,
    )

    /** Moves the backups of the game made from a file [fileGameKey] into [cart]'s game; see [SaveLibrary.linkToCartridge]. */
    suspend fun linkToCartridge(fileGameKey: String, cart: GameInfo, cartSaveSize: Int): GameInfo {
        val linked = withContext(io) { library.linkToCartridge(fileGameKey, cart, cartSaveSize) }
        reload()
        return linked
    }

    /** The raw `.sav` of [entry] (CRC checked). */
    suspend fun readSave(entry: BackupEntry): ByteArray = withContext(io) { library.readSave(entry) }

    /**
     * Whether [entry] of [game] opens in the Pokemon editor. Cheap (one parse of a 32 to 128 KiB save) and cached per
     * backup, so screens can call it freely.
     */
    suspend fun probePokemon(game: GameInfo, entry: BackupEntry): PokemonSaveKind {
        val cacheKey = "${entry.gameKey}/${entry.id}/${entry.crc32}"
        probeCache[cacheKey]?.let { return it }
        val result = withContext(io) {
            val quick = PokemonSaveProbe.byGame(game)
            if (quick != null) quick else PokemonSaveProbe.probe(game, runCatching { library.readSave(entry) }.getOrNull())
        }
        probeCache[cacheKey] = result
        return result
    }

    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, PokemonSaveKind>()

    /** Reads the `.sav` bytes for export. */
    suspend fun exportBytes(entry: BackupEntry, includeRtc: Boolean): ByteArray = withContext(io) { library.exportBytes(entry, includeRtc) }

    private fun token(e: BackupEntry) = "${e.gameKey}/${e.id}"

    companion object {
        const val NOTE_MAX_LENGTH = 140
    }
}

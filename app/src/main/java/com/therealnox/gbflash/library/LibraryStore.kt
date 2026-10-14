package com.therealnox.gbflash.library

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

    /** Reads the `.sav` bytes for export. */
    suspend fun exportBytes(entry: BackupEntry, includeRtc: Boolean): ByteArray = withContext(io) { library.exportBytes(entry, includeRtc) }

    private fun token(e: BackupEntry) = "${e.gameKey}/${e.id}"

    companion object {
        const val NOTE_MAX_LENGTH = 140
    }
}

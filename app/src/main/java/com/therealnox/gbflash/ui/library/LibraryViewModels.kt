package com.therealnox.gbflash.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.therealnox.gbflash.device.ConnectionStatus
import com.therealnox.gbflash.device.DeviceState
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameSummary
import com.therealnox.gbflash.library.LibraryQuery
import com.therealnox.gbflash.library.LibrarySort
import com.therealnox.gbflash.library.LibraryState
import com.therealnox.gbflash.library.LibraryStore
import com.therealnox.gbflash.library.PlatformFilter
import com.therealnox.gbflash.library.applyQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Key of the game whose cartridge is read and still powered in the reader, or null. This drives the "In reader"
 * chip and highlight; it disappears as soon as the cartridge is powered off or the reader is gone.
 */
fun inReaderKey(d: DeviceState): String? =
    if (d.status == ConnectionStatus.CONNECTED && d.cartPowered && d.header != null) d.cartGame?.key else null

data class LibraryUiState(
    val state: LibraryState = LibraryState.Loading,
    val query: LibraryQuery = LibraryQuery(),
    /** The games to show: search, filter and sort applied. */
    val items: List<GameSummary> = emptyList(),
    /** Games in the library before any filter. */
    val total: Int = 0,
    val inReaderKey: String? = null,
)

/** The Library tab: query state (search, sort, platform filter), the filtered list and "In reader" matching. */
class LibraryViewModel(
    private val store: LibraryStore,
    deviceState: StateFlow<DeviceState>,
) : ViewModel() {
    private val query = MutableStateFlow(LibraryQuery())

    val ui: StateFlow<LibraryUiState> = combine(store.state, query, deviceState) { library, q, device ->
        val games = (library as? LibraryState.Loaded)?.games.orEmpty()
        LibraryUiState(
            state = library,
            query = q,
            items = games.applyQuery(q),
            total = games.size,
            inReaderKey = inReaderKey(device),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun setSearch(text: String) = query.update { it.copy(search = text) }

    fun setSort(sort: LibrarySort) = query.update { it.copy(sort = sort) }

    fun setFilter(filter: PlatformFilter) = query.update { it.copy(filter = filter) }

    /** "Clear filters": search and platform filter, keeps the sort. */
    fun clearFilters() = query.update { it.copy(search = "", filter = PlatformFilter.ALL) }

    fun retry() = store.reload()
}

data class GameDetailUi(
    val loading: Boolean = true,
    /** Null when the game does not exist (any more). */
    val game: GameSummary? = null,
    val inReader: Boolean = false,
    /** "Back up this cart" is possible: this game's cartridge is read, powered and backup-capable. */
    val canBackupCart: Boolean = false,
)

class GameDetailViewModel(
    private val gameKey: String,
    store: LibraryStore,
    deviceState: StateFlow<DeviceState>,
) : ViewModel() {
    val ui: StateFlow<GameDetailUi> = combine(store.state, deviceState) { library, device ->
        val game = (library as? LibraryState.Loaded)?.games?.firstOrNull { it.key == gameKey }
        val here = inReaderKey(device) == gameKey
        GameDetailUi(
            loading = library is LibraryState.Loading,
            game = game,
            inReader = here,
            canBackupCart = here && device.backupAvailable && !device.busy && device.restore == null && !device.backupRunning,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GameDetailUi())
}

data class BackupDetailUi(
    val loading: Boolean = true,
    val game: GameSummary? = null,
    /** Null when the backup does not exist (any more). */
    val entry: BackupEntry? = null,
    val restoreAvailable: Boolean = false,
    /** Why the restore button is disabled; null when it is enabled. */
    val restoreReason: String? = null,
    /** The cartridge in the reader is a different game than this backup (restore will need a typed title). */
    val otherGameInReader: Boolean = false,
)

class BackupDetailViewModel(
    private val gameKey: String,
    private val backupId: String,
    private val store: LibraryStore,
    deviceState: StateFlow<DeviceState>,
) : ViewModel() {
    val ui: StateFlow<BackupDetailUi> = combine(store.state, deviceState) { library, device ->
        val game = (library as? LibraryState.Loaded)?.games?.firstOrNull { it.key == gameKey }
        val readerKey = inReaderKey(device)
        BackupDetailUi(
            loading = library is LibraryState.Loading,
            game = game,
            entry = game?.backups?.firstOrNull { it.id == backupId },
            restoreAvailable = device.restoreAvailable,
            restoreReason = device.restoreUnavailableReason,
            otherGameInReader = readerKey != null && readerKey != gameKey,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BackupDetailUi())

    /** Saves the note (trimmed to 140 characters). Throws on I/O errors; the caller reports them. */
    suspend fun saveNote(entry: BackupEntry, note: String): BackupEntry = store.updateNote(entry, note)
}

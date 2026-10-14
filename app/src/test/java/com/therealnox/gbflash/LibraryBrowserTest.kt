package com.therealnox.gbflash

import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.device.ConnectionStatus
import com.therealnox.gbflash.device.DeviceState
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.DisplayPlatform
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.GameSummary
import com.therealnox.gbflash.library.LibraryQuery
import com.therealnox.gbflash.library.LibrarySort
import com.therealnox.gbflash.library.LibraryState
import com.therealnox.gbflash.library.LibraryStore
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.PlatformFilter
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.library.applyQuery
import com.therealnox.gbflash.library.displayPlatform
import com.therealnox.gbflash.library.inReader
import com.therealnox.gbflash.ui.library.LibraryViewModel
import com.therealnox.gbflash.ui.library.inReaderKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBrowserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val blue = GameInfo("DMG", "POKEMON BLUE", "DMG_POKEMON-BLUE_56A4", "0x1B MBC5", 0x100000L, 0x56A4, 0x9C, null)
    private val crystal = GameInfo("DMG", "PM_CRYSTAL", "DMG_PM-CRYSTAL_F2E2", "0x10 MBC3", 0x200000L, 0xF2E2, 0x26, "BYTF")
    private val emerald = GameInfo("AGB", "EMERALD", "AGB_EMERALD_0001", "", null, 1, 1, "BPEE")

    private fun entry(game: GameInfo, id: String, time: Long, note: String = "") = BackupEntry(
        game.key, id, time, 32768, 1, null, null, false, "", "", true, null, note,
    )

    private fun summary(game: GameInfo, time: Long, note: String = "") = GameSummary(game, listOf(entry(game, "a", time, note)))

    private val games = listOf(
        summary(blue, 100, note = "before the elite four"),
        summary(crystal, 300),
        summary(emerald, 200),
    )

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    // ---- search, sort, filter ------------------------------------------------------------------

    @Test
    fun sort_lastBackupNewestFirst_orTitleAlphabetical() {
        assertEquals(listOf("PM_CRYSTAL", "EMERALD", "POKEMON BLUE"), games.applyQuery(LibraryQuery()).map { it.title })
        assertEquals(
            listOf("EMERALD", "PM_CRYSTAL", "POKEMON BLUE"),
            games.applyQuery(LibraryQuery(sort = LibrarySort.TITLE)).map { it.title },
        )
    }

    @Test
    fun search_matchesTitleCodeAndNote_caseInsensitive() {
        assertEquals(listOf("POKEMON BLUE"), games.applyQuery(LibraryQuery(search = "blue")).map { it.title })
        assertEquals(listOf("PM_CRYSTAL"), games.applyQuery(LibraryQuery(search = "byt")).map { it.title })
        assertEquals(listOf("POKEMON BLUE"), games.applyQuery(LibraryQuery(search = "ELITE")).map { it.title })
        assertTrue(games.applyQuery(LibraryQuery(search = "zelda")).isEmpty())
    }

    @Test
    fun platformFilter_separatesGbGbcGba() {
        assertEquals(DisplayPlatform.GB, blue.displayPlatform)
        assertEquals(DisplayPlatform.GBC, crystal.displayPlatform)
        assertEquals(DisplayPlatform.GBA, emerald.displayPlatform)
        assertEquals(listOf("POKEMON BLUE"), games.applyQuery(LibraryQuery(filter = PlatformFilter.GB)).map { it.title })
        assertEquals(listOf("PM_CRYSTAL"), games.applyQuery(LibraryQuery(filter = PlatformFilter.GBC)).map { it.title })
        assertEquals(listOf("EMERALD"), games.applyQuery(LibraryQuery(filter = PlatformFilter.GBA)).map { it.title })
        assertEquals(3, games.applyQuery(LibraryQuery(filter = PlatformFilter.ALL)).size)
    }

    @Test
    fun searchAndFilterCombine() {
        assertTrue(games.applyQuery(LibraryQuery(search = "blue", filter = PlatformFilter.GBC)).isEmpty())
    }

    // ---- "In reader" ---------------------------------------------------------------------------

    @Test
    fun inReader_matchesByLibraryKey() {
        assertEquals("POKEMON BLUE", games.inReader(blue.key)?.title)
        assertNull(games.inReader("DMG_OTHER_0000"))
        assertNull(games.inReader(null))
    }

    @Test
    fun inReaderKey_onlyWhileCartIsPoweredAndRead() {
        val header = DmgHeader.parse(dmgHeader())
        val powered = DeviceState(status = ConnectionStatus.CONNECTED, cartPowered = true, header = header, cartGame = blue)
        assertEquals(blue.key, inReaderKey(powered))
        assertNull(inReaderKey(powered.copy(cartPowered = false)))
        assertNull(inReaderKey(powered.copy(status = ConnectionStatus.NO_DEVICE)))
        assertNull(inReaderKey(powered.copy(header = null)))
    }

    @Test
    fun viewModel_combinesQueryAndInReader() = runTest {
        val lib = SaveLibrary(tmp.newFolder("saves")) { 1_700_000_000_000L }
        val save = ByteArray(0x8000) { it.toByte() }
        lib.store(blue, NewBackup(save, null, null, false, "", "", true, null))
        lib.store(crystal, NewBackup(save, null, null, false, "", "", true, null))
        val d = UnconfinedTestDispatcher(testScheduler)
        val store = LibraryStore(lib, CoroutineScope(d), d)
        val device = MutableStateFlow(DeviceState())
        val vm = LibraryViewModel(store, device)
        assertEquals(2, vm.ui.value.items.size)
        vm.setFilter(PlatformFilter.GBC)
        assertEquals(listOf("PM_CRYSTAL"), vm.ui.value.items.map { it.title })
        assertEquals(2, vm.ui.value.total)
        vm.clearFilters()
        vm.setSearch("blue")
        assertEquals(listOf("POKEMON BLUE"), vm.ui.value.items.map { it.title })
        assertNull(vm.ui.value.inReaderKey)
        val header = DmgHeader.parse(dmgHeader())
        device.value = DeviceState(status = ConnectionStatus.CONNECTED, cartPowered = true, header = header, cartGame = blue)
        assertEquals(blue.key, vm.ui.value.inReaderKey)
    }

    // ---- delete with undo ----------------------------------------------------------------------

    private fun storeWithOneBackup(scheduler: TestCoroutineScheduler): Triple<LibraryStore, SaveLibrary, BackupEntry> {
        val lib = SaveLibrary(tmp.newFolder("saves2")) { 1_700_000_000_000L }
        val e = lib.store(blue, NewBackup(ByteArray(0x8000) { 5 }, null, null, false, "", "", true, null))
        val d = UnconfinedTestDispatcher(scheduler)
        return Triple(LibraryStore(lib, CoroutineScope(d), d), lib, e)
    }

    @Test
    fun delete_hidesAtOnce_removesFilesWhenUndoWindowEnds() = runTest {
        val (store, lib, e) = storeWithOneBackup(testScheduler)
        var visibleDuringWindow: LibraryState? = null
        var filesDuringWindow = false
        store.deleteWithUndo(e) {
            visibleDuringWindow = store.state.value
            filesDuringWindow = lib.list(blue.key).isNotEmpty()
            false
        }
        assertEquals(0, (visibleDuringWindow as LibraryState.Loaded).games.size)
        assertTrue("files stay until the window ends", filesDuringWindow)
        assertTrue(lib.list(blue.key).isEmpty())
        assertEquals(0, (store.state.value as LibraryState.Loaded).games.size)
    }

    @Test
    fun undo_bringsTheBackupBack_andKeepsTheFiles() = runTest {
        val (store, lib, e) = storeWithOneBackup(testScheduler)
        store.deleteWithUndo(e) { true }
        assertEquals(1, lib.list(blue.key).size)
        assertEquals(1, (store.state.value as LibraryState.Loaded).games.size)
    }

    // ---- notes ---------------------------------------------------------------------------------

    @Test
    fun note_isPersistedAtomically_andLeavesTheSaveAlone() = runTest {
        val (store, lib, e) = storeWithOneBackup(testScheduler)
        val updated = store.updateNote(e, "  Before the Elite Four  ")
        assertEquals("Before the Elite Four", updated.note)
        // A fresh library instance reads it back from disk.
        val again = SaveLibrary(File(tmp.root, "saves2")).list(blue.key).single()
        assertEquals("Before the Elite Four", again.note)
        assertEquals(e.crc32, again.crc32)
        assertEquals(e.id, again.id)
        assertTrue(lib.readSave(again).all { it == 5.toByte() })
        val dir = File(File(tmp.root, "saves2"), "${blue.key}/${e.id}")
        assertTrue(dir.list()!!.none { it.startsWith(".tmp") })
        assertEquals("Before the Elite Four", (store.state.value as LibraryState.Loaded).games.single().backups.single().note)
    }

    @Test
    fun note_isLimitedTo140Characters_andCanBeCleared() = runTest {
        val (store, _, e) = storeWithOneBackup(testScheduler)
        assertEquals(140, store.updateNote(e, "x".repeat(300)).note.length)
        assertEquals("", store.updateNote(e, "").note)
    }

    @Test
    fun emptyLibrary_isLoadedWithNoGames() = runTest {
        val lib = SaveLibrary(tmp.newFolder("empty"))
        val d = UnconfinedTestDispatcher(testScheduler)
        val store = LibraryStore(lib, CoroutineScope(d), d)
        assertFalse(store.state.value is LibraryState.Loading)
        assertEquals(0, (store.state.value as LibraryState.Loaded).games.size)
    }
}

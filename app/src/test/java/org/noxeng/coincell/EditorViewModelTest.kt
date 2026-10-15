package org.noxeng.coincell

import org.noxeng.coincell.EditorTestSaves.blueGame
import org.noxeng.coincell.EditorTestSaves.crystalGame
import org.noxeng.coincell.cart.RtcSnapshot
import org.noxeng.coincell.editor.PokemonEdits
import org.noxeng.coincell.library.BackupEntry
import org.noxeng.coincell.library.BackupKind
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.library.NewBackup
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.ui.editor.EditorPhase
import org.noxeng.coincell.ui.editor.EditorViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Pokemon editor's state holder: load, edit, change summary, "save as a NEW backup" (history rule of
 * docs/UI_DESIGN.md 6.3) and the unsaved-changes guard. Synthetic saves always run; the user's own saves run when present.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000L
    private lateinit var lib: SaveLibrary
    private lateinit var dispatcher: kotlinx.coroutines.test.TestDispatcher

    @Before
    fun setUp() {
        dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        lib = SaveLibrary(tmp.newFolder("saves")) { clock.also { clock += 60_000 } }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun footer(days: Int = 12): ByteArray = RtcSnapshot(5, 4, 3, days, 0, 1_700_000_000L).toFooter()

    private fun store(game: GameInfo, save: ByteArray, rtc: ByteArray? = null, note: String = ""): BackupEntry =
        lib.store(game, NewBackup(save, rtc, null, rtc != null, "L15", "test", true, null, note))

    private fun vmFor(entry: BackupEntry): Pair<EditorViewModel, LibraryStore> {
        val libraryStore = LibraryStore(lib, CoroutineScope(dispatcher), dispatcher)
        return EditorViewModel(entry.gameKey, entry.id, libraryStore, "0.test", dispatcher) to libraryStore
    }

    // ---- load -----------------------------------------------------------------------------------------------------

    @Test
    fun load_opensTheBackupAsAWorkingCopy() = runTest {
        val parent = store(crystalGame, EditorTestSaves.crystal())
        val (vm, _) = vmFor(parent)
        val ui = vm.ui.value
        assertEquals(EditorPhase.Ready, ui.phase)
        assertEquals(2, ui.info!!.generation)
        assertEquals("GOLD", ui.snapshot!!.trainer.name)
        assertEquals(listOf("TYPHLOSION", "PIDGEY"), ui.snapshot.party.map { it.nickname })
        assertEquals(1, ui.snapshot.boxes[0].pokemon.size)
        assertFalse(ui.hasChanges)
        assertEquals("", ui.noteSuggestion)
        assertEquals(parent.id, ui.parent!!.id)
    }

    @Test
    fun load_missingBackupOrBrokenSave_failsWithAMessage() = runTest {
        val libraryStore = LibraryStore(lib, CoroutineScope(dispatcher), dispatcher)
        val gone = EditorViewModel("DMG_NOPE_0000", "20200101-000000", libraryStore, "t", dispatcher)
        assertTrue(gone.ui.value.phase is EditorPhase.Failed)

        val garbage = store(crystalGame, ByteArray(0x8000) { 0x11 })
        val (vm, _) = vmFor(garbage)
        val phase = vm.ui.value.phase
        assertTrue(phase is EditorPhase.Failed)
        assertTrue((phase as EditorPhase.Failed).message.contains("could not be read"))
    }

    // ---- edit and diff note -------------------------------------------------------------------------------------------

    @Test
    fun edits_updateTheSnapshotAndTheChangeNote() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        assertNull(vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) })
        assertNull(vm.updateTrainer(vm.ui.value.snapshot!!.trainer.copy(money = 999_999)))
        val ui = vm.ui.value
        assertTrue(ui.hasChanges)
        assertEquals(50, ui.snapshot!!.party[0].level)
        assertEquals("Edited: Typhlosion L37→L50; money 3000→999999", ui.noteSuggestion)
        // Party stats follow the level (what the game shows in the party menu).
        assertTrue(ui.snapshot.party[0].storedStats.hp > 0)
    }

    @Test
    fun refusedEdit_returnsTheMessageAndChangesNothing() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        val error = vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 101) }
        assertEquals("The level must be between 1 and 100.", error)
        assertFalse(vm.ui.value.hasChanges)
        // A refused move also leaves everything as it was.
        val badMove = vm.movePokemon(SlotRef.Party(5), SlotRef.Party(0))
        assertNotNull(badMove)
        assertFalse(vm.ui.value.hasChanges)
    }

    @Test
    fun slotOperations_addMoveRemove_andThePartyKeepsOnePokemon() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        // move party 1 to the box (end of box 2), then add one into the party
        assertNull(vm.movePokemon(SlotRef.Party(1), SlotRef.Box(1, 7)))
        assertEquals(1, vm.ui.value.snapshot!!.party.size)
        assertEquals("PIDGEY", vm.ui.value.snapshot!!.boxes[1].pokemon[0].nickname)
        // the last party Pokemon can neither be removed nor moved out
        assertNotNull(vm.removePokemon(SlotRef.Party(0)))
        assertNotNull(vm.movePokemon(SlotRef.Party(0), SlotRef.Box(0, 5)))
        assertEquals(1, vm.ui.value.snapshot!!.party.size)

        var placed: SlotRef? = null
        assertNull(vm.addPokemon(SlotRef.Party(3), 25) { placed = it })
        assertEquals(SlotRef.Party(1), placed)
        val added = vm.ui.value.snapshot!!.party[1]
        assertEquals(25, added.species)
        assertEquals(5, added.level)
        assertEquals("GOLD", added.otName)
        assertEquals(54321, added.trainerId)
        assertEquals(listOf(33), added.moves.map { it.id }.filter { it != 0 })

        assertNull(vm.removePokemon(SlotRef.Box(0, 0)))
        assertTrue(vm.ui.value.snapshot!!.boxes[0].pokemon.isEmpty())
        val changes = vm.ui.value.changes.map { it.text }
        assertTrue(changes.toString(), changes.any { it.startsWith("added Pikachu") })
        assertTrue(changes.toString(), changes.any { it.startsWith("removed Rattata") })
        assertTrue(changes.toString(), changes.any { it.startsWith("moved Pidgey party") })
    }

    @Test
    fun bag_setPocket_isSummarisedAndValidated() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        val items = vm.ui.value.snapshot!!.inventory.pocket(PocketType.ITEMS)!!
        assertNull(vm.setPocket(PocketType.ITEMS, items.items + org.noxeng.coincell.pkcore.ItemStack(1, 3)))
        assertTrue(vm.ui.value.changes.any { it.text == "Items: +Master Ball x3" })
        // more stacks than the pocket holds is refused by :pkcore
        val tooMany = List(items.capacity + 1) { org.noxeng.coincell.pkcore.ItemStack(it + 1, 1) }
        assertNotNull(vm.setPocket(PocketType.ITEMS, tooMany))
    }

    @Test
    fun revertAll_returnsToTheLoadedState() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 99) }
        vm.removePokemon(SlotRef.Box(0, 0))
        assertTrue(vm.ui.value.hasChanges)
        vm.revertAll()
        assertFalse(vm.ui.value.hasChanges)
        assertEquals(37, vm.ui.value.snapshot!!.party[0].level)
        assertEquals(1, vm.ui.value.snapshot!!.boxes[0].pokemon.size)
    }

    // ---- save as a new backup (history rule) ----------------------------------------------------------------------------

    @Test
    fun saveAsNew_createsAnEditedEntry_parentUntouched_clockCopied() = runTest {
        val original = EditorTestSaves.crystal()
        val clockFooter = footer()
        val parent = store(crystalGame, original, rtc = clockFooter, note = "my note")
        val (vm, libraryStore) = vmFor(parent)
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) }
        vm.openSaveDialog()
        assertTrue(vm.ui.value.saveDialog)

        vm.saveAsNew(vm.ui.value.noteSuggestion + " (test)")
        val ui = vm.ui.value
        assertFalse(ui.saving)
        assertNull(ui.saveError)
        val saved = ui.saved!!
        assertEquals(BackupKind.EDITED, saved.kind)
        assertEquals(parent.id, saved.parentId)
        assertEquals("Edited: Typhlosion L37→L50 (test)", saved.note)
        assertEquals(crystalGame.key, saved.gameKey)
        assertNotEquals(parent.id, saved.id)

        // The library now holds both; the parent is exactly as it was.
        val all = lib.list(crystalGame.key)
        assertEquals(2, all.size)
        val parentAfter = all.single { it.id == parent.id }
        assertEquals(parent.crc32, parentAfter.crc32)
        assertEquals(BackupKind.BACKUP, parentAfter.kind)
        assertEquals("my note", parentAfter.note)
        assertArrayEquals(original, lib.readSave(parentAfter))
        assertNull(parentAfter.parentId)

        // The clock of the parent is carried over byte for byte.
        assertArrayEquals(clockFooter, lib.readRtcFooter(saved))
        assertTrue(saved.rtcIncluded)

        // The stored save is a proper save with the edit in it and valid checksums.
        val reloaded = SaveFile.load(lib.readSave(saved), 2)
        assertTrue(reloaded.checksumsValid)
        assertEquals(50, reloaded.party[0].level)
        assertEquals(0x8000, saved.size)

        // The editor now measures against the saved state and parents the next save on the new entry.
        assertFalse(ui.hasChanges)
        assertEquals(saved.id, ui.parent!!.id)
        vm.dismissSaved()
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 51) }
        assertEquals("Edited: Typhlosion L50→L51", vm.ui.value.noteSuggestion)
        vm.saveAsNew("second")
        val second = vm.ui.value.saved!!
        assertEquals(saved.id, second.parentId)
        assertEquals(3, lib.list(crystalGame.key).size)
        // the store's list was refreshed as well
        assertEquals(3, (libraryStore.state.value as org.noxeng.coincell.library.LibraryState.Loaded).games.single().count)
    }

    @Test
    fun saveAsNew_withoutClockInParent_storesNoClock() = runTest {
        val parent = store(blueGame, EditorTestSaves.blue())
        val (vm, _) = vmFor(parent)
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 40) }
        vm.saveAsNew("x")
        val saved = vm.ui.value.saved!!
        assertNull(lib.readRtcFooter(saved))
        assertFalse(saved.rtcIncluded)
        assertEquals(BackupKind.EDITED, saved.kind)
        assertEquals(40, SaveFile.load(lib.readSave(saved)).party[0].level)
    }

    @Test
    fun saveAsNew_cannotOverwrite_idsAreAlwaysNew() = runTest {
        val parent = store(crystalGame, EditorTestSaves.crystal())
        val (vm, _) = vmFor(parent)
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) }
        vm.saveAsNew("a")
        val first = vm.ui.value.saved!!
        vm.dismissSaved()
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 60) }
        vm.saveAsNew("b")
        val second = vm.ui.value.saved!!
        assertEquals(setOf(parent.id, first.id, second.id).size, 3)
        assertEquals(50, SaveFile.load(lib.readSave(lib.list(crystalGame.key).single { it.id == first.id })).party[0].level)
    }

    @Test
    fun savedEntryReadsBackWithOldFieldsIntact_andOldEntriesStayReadable() = runTest {
        val old = store(crystalGame, EditorTestSaves.crystal())
        // an entry written before milestone 6 has neither "edited" nor "parentId"
        val json = File(File(File(tmp.root, "saves"), crystalGame.key), old.id + "/backup.json")
        assertTrue(!json.readText().contains("parentId"))
        assertEquals(BackupKind.BACKUP, lib.list(crystalGame.key).single().kind)
        assertNull(lib.list(crystalGame.key).single().parentId)
        assertEquals(BackupKind.EDITED, BackupKind.fromJson("edited"))
        assertEquals("Edited", BackupKind.EDITED.label)
    }

    // ---- unsaved-changes guard --------------------------------------------------------------------------------------

    @Test
    fun leaveGuard_noChanges_leavesAtOnce() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        assertTrue(vm.requestLeave())
        assertFalse(vm.ui.value.leavePrompt)
    }

    @Test
    fun leaveGuard_withChanges_asksThenKeepEditingOrDiscards() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) }
        assertFalse(vm.requestLeave())
        assertTrue(vm.ui.value.leavePrompt)

        vm.keepEditing()
        assertFalse(vm.ui.value.leavePrompt)
        assertTrue(vm.ui.value.hasChanges)
        assertEquals(50, vm.ui.value.snapshot!!.party[0].level)

        assertFalse(vm.requestLeave())
        vm.discard()
        assertFalse(vm.ui.value.leavePrompt)
        assertFalse(vm.ui.value.hasChanges)
        assertEquals(37, vm.ui.value.snapshot!!.party[0].level)
        assertTrue(vm.requestLeave())
    }

    @Test
    fun leaveGuard_saveChoiceOpensTheNoteDialogWithTheAutoNote() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) }
        vm.requestLeave()
        vm.openSaveDialog()
        val ui = vm.ui.value
        assertTrue(ui.saveDialog)
        assertFalse(ui.leavePrompt)
        assertEquals("Edited: Typhlosion L37→L50", ui.noteSuggestion)
        vm.dismissSaveDialog()
        assertFalse(vm.ui.value.saveDialog)
        assertTrue(vm.ui.value.hasChanges)
    }

    @Test
    fun saveDialog_doesNotOpenWithoutChanges() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        vm.openSaveDialog()
        assertFalse(vm.ui.value.saveDialog)
    }

    @Test
    fun moveMode_setAndClearedByMoveOrCancel() = runTest {
        val (vm, _) = vmFor(store(crystalGame, EditorTestSaves.crystal()))
        vm.startMove(SlotRef.Party(0))
        assertEquals(SlotRef.Party(0), vm.ui.value.moveSource)
        vm.cancelMove()
        assertNull(vm.ui.value.moveSource)
        vm.startMove(SlotRef.Party(0))
        assertNull(vm.movePokemon(SlotRef.Party(0), SlotRef.Party(1)))
        assertNull(vm.ui.value.moveSource)
        assertEquals("PIDGEY", vm.ui.value.snapshot!!.party[0].nickname)
        assertTrue(vm.ui.value.changes.any { it.text == "reordered the party" })
    }

    // ---- the user's real saves ------------------------------------------------------------------------------------

    @Test
    fun realBlue_changeLevel_saveAsNew_reloadsWithTheChangeAndValidChecksums() = runTest {
        val bytes = EditorTestSaves.realBlueOrSkip()
        val parent = store(blueGame, bytes)
        val (vm, _) = vmFor(parent)
        assertEquals(EditorPhase.Ready, vm.ui.value.phase)
        val before = vm.ui.value.snapshot!!.party[0]
        val newLevel = if (before.level == 99) 98 else before.level + 1
        assertNull(vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, newLevel) })
        assertEquals(1, vm.ui.value.changes.size)
        vm.saveAsNew(vm.ui.value.noteSuggestion)
        val saved = vm.ui.value.saved!!

        val reloaded = SaveFile.load(lib.readSave(saved))
        assertTrue(reloaded.checksumsValid)
        assertEquals(newLevel, reloaded.party[0].level)
        assertEquals(before.species, reloaded.party[0].species)
        assertEquals(bytes.size, lib.readSave(saved).size)
        // everything else is as it was
        assertEquals(SaveFile.load(bytes).trainer, reloaded.trainer)
        assertArrayEquals(bytes, lib.readSave(parent))
        assertEquals(BackupKind.EDITED, saved.kind)
        assertEquals(parent.id, saved.parentId)
    }

    @Test
    fun realCrystal_changeLevel_saveAsNew_reloadsWithTheChangeAndValidChecksums() = runTest {
        val bytes = EditorTestSaves.realCrystalOrSkip()
        val footer = EditorTestSaves.realCrystalFooter()
        val parent = store(crystalGame, bytes, rtc = footer)
        val (vm, _) = vmFor(parent)
        assertEquals(EditorPhase.Ready, vm.ui.value.phase)
        assertEquals(3, vm.ui.value.info!!.language) // French
        val before = vm.ui.value.snapshot!!.party[0]
        val newLevel = if (before.level == 99) 98 else before.level + 1
        assertNull(vm.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, newLevel) })
        vm.saveAsNew(vm.ui.value.noteSuggestion)
        val saved = vm.ui.value.saved!!

        val reloaded = SaveFile.load(lib.readSave(saved))
        assertTrue(reloaded.checksumsValid)
        assertEquals(newLevel, reloaded.party[0].level)
        assertEquals(before.species, reloaded.party[0].species)
        assertEquals(SaveFile.load(bytes).trainer, reloaded.trainer)
        assertArrayEquals(bytes, lib.readSave(parent))
        assertArrayEquals(footer, lib.readRtcFooter(saved))
        assertTrue(saved.note.startsWith("Edited: "))
    }

    @Test
    fun realCrystal_unchangedRoundTripThroughTheEditorModelIsByteIdentical() {
        val bytes = EditorTestSaves.realCrystalOrSkip()
        val editor = org.noxeng.coincell.editor.SaveEditor.open(bytes)
        assertTrue(editor.changes().isEmpty())
        assertArrayEquals(bytes, editor.exportChecked())
    }
}

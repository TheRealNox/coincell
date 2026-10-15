package org.noxeng.coincell.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.noxeng.coincell.editor.Change
import org.noxeng.coincell.editor.PokemonEdits
import org.noxeng.coincell.editor.PokemonSaveInfo
import org.noxeng.coincell.editor.SaveDiff
import org.noxeng.coincell.editor.SaveEditor
import org.noxeng.coincell.editor.SaveSnapshot
import org.noxeng.coincell.library.BackupEntry
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.LibraryState
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.ItemStack
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.Trainer
import org.noxeng.coincell.pkcore.EditablePokemon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EditorPhase {
    data object Loading : EditorPhase

    data object Ready : EditorPhase

    data class Failed(val message: String) : EditorPhase
}

data class EditorUi(
    val phase: EditorPhase = EditorPhase.Loading,
    val game: GameInfo? = null,
    /** The backup the edits are based on; after a save, the entry that was just stored. */
    val parent: BackupEntry? = null,
    val info: PokemonSaveInfo? = null,
    val snapshot: SaveSnapshot? = null,
    val changes: List<Change> = emptyList(),
    /** "Edited: Typhlosion L37→L50; ..." for the note field of the save dialog. */
    val noteSuggestion: String = "",
    /** The Pokemon being moved (tap the destination next), or null. */
    val moveSource: SlotRef? = null,
    /** "Discard / Keep editing / Save as new backup" is showing. */
    val leavePrompt: Boolean = false,
    /** The note dialog of "Save as new backup" is showing. */
    val saveDialog: Boolean = false,
    val saving: Boolean = false,
    val saveError: String? = null,
    /** The entry just stored (the result dialog is showing), until dismissed. */
    val saved: BackupEntry? = null,
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
}

/**
 * Holds the editor's [SaveEditor] (the in-memory working copy). It is scoped to the editor's back stack entry, so a
 * rotation keeps every edit; leaving the editor drops it. The library entry it started from is never modified:
 * [saveAsNew] always stores a NEW entry of kind `edited`.
 */
class EditorViewModel(
    private val gameKey: String,
    private val backupId: String,
    private val store: LibraryStore,
    private val appVersion: String,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private var editor: SaveEditor? = null
    private val _ui = MutableStateFlow(EditorUi())
    val ui: StateFlow<EditorUi> = _ui.asStateFlow()

    /** The working copy (for the screens that need names, capacities...). Null until loaded. */
    val model: SaveEditor? get() = editor

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val library = store.state.first { it !is LibraryState.Loading }
        val summary = (library as? LibraryState.Loaded)?.games?.firstOrNull { it.key == gameKey }
        val entry = summary?.backups?.firstOrNull { it.id == backupId }
        if (summary == null || entry == null) {
            fail("This backup is not in the library any more.")
            return
        }
        val opened = try {
            val bytes = store.readSave(entry)
            withContext(work) { SaveEditor.open(bytes, summary.game) }
        } catch (e: Exception) {
            fail("This save could not be read as a Pokemon save (${e.message ?: e.javaClass.simpleName}).")
            return
        }
        editor = opened
        _ui.update { it.copy(phase = EditorPhase.Ready, game = summary.game, parent = entry, info = opened.info) }
        refresh()
    }

    private fun fail(message: String) = _ui.update { it.copy(phase = EditorPhase.Failed(message)) }

    private fun refresh() {
        val ed = editor ?: return
        val snapshot = ed.snapshot()
        val changes = ed.changes(snapshot)
        _ui.update {
            it.copy(
                snapshot = snapshot,
                changes = changes,
                noteSuggestion = SaveDiff.note(changes, LibraryStore.NOTE_MAX_LENGTH),
                // A move can't survive an edit of the slots it refers to.
                moveSource = it.moveSource?.takeIf { s -> snapshot.pokemonAt(s) != null },
            )
        }
    }

    /** Runs one edit; returns the message to show when `:pkcore` refused it (nothing was changed then), else null. */
    private fun attempt(block: (SaveEditor) -> Unit): String? {
        val ed = editor ?: return "The editor is not ready."
        return try {
            block(ed)
            refresh()
            null
        } catch (e: InvalidEditException) {
            e.message ?: "This edit is not possible."
        } catch (e: Exception) {
            refresh()
            "The edit failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---- edits ------------------------------------------------------------------------------------------------------
    fun updateTrainer(trainer: Trainer): String? = attempt { it.updateTrainer(trainer) }

    fun edit(slot: SlotRef, block: (EditablePokemon) -> Unit): String? = attempt { it.edit(slot, block) }

    /** Adds a new Pokemon to the first free slot of [slot]'s container; [onAdded] gets the slot it went to. */
    fun addPokemon(slot: SlotRef, species: Int, onAdded: (SlotRef) -> Unit = {}): String? {
        var placed: SlotRef? = null
        val error = attempt { placed = it.addPokemon(slot, species) }
        placed?.let(onAdded)
        return error
    }

    fun removePokemon(slot: SlotRef): String? = attempt { it.removePokemon(slot) }

    fun movePokemon(from: SlotRef, to: SlotRef): String? = attempt { it.movePokemon(from, to) }.also { if (it == null) cancelMove() }

    fun setPocket(type: PocketType, items: List<ItemStack>): String? = attempt { it.setPocket(type, items) }

    fun heal(slot: SlotRef): String? = attempt { ed -> ed.edit(slot) { PokemonEdits.heal(it) } }

    fun makeShiny(slot: SlotRef): String? = attempt { ed -> ed.edit(slot) { PokemonEdits.makeShiny(it) } }

    fun revertAll() {
        editor?.revertAll()
        _ui.update { it.copy(moveSource = null) }
        refresh()
    }

    // ---- move mode --------------------------------------------------------------------------------------------------
    fun startMove(slot: SlotRef) = _ui.update { it.copy(moveSource = slot) }

    fun cancelMove() = _ui.update { it.copy(moveSource = null) }

    // ---- leaving (docs/UI_DESIGN.md 6.3) ----------------------------------------------------------------------------
    /** True if the editor may close now. With unsaved edits it opens the Discard / Keep editing / Save prompt and returns false. */
    fun requestLeave(): Boolean {
        if (!_ui.value.hasChanges) return true
        _ui.update { it.copy(leavePrompt = true) }
        return false
    }

    fun keepEditing() = _ui.update { it.copy(leavePrompt = false) }

    /** "Discard": forget every edit; the caller closes the editor. */
    fun discard() {
        editor?.revertAll()
        _ui.update { it.copy(leavePrompt = false, saveDialog = false, moveSource = null) }
        refresh()
    }

    // ---- save as a new backup ---------------------------------------------------------------------------------------
    fun openSaveDialog() = _ui.update { if (it.hasChanges) it.copy(saveDialog = true, leavePrompt = false, saveError = null) else it }

    fun dismissSaveDialog() = _ui.update { it.copy(saveDialog = false, saveError = null) }

    fun dismissSaved() = _ui.update { it.copy(saved = null) }

    /**
     * Stores the working copy as a NEW `edited` entry next to the backup it was edited from (same game, `parentId`,
     * the parent's clock copied, [note] as the note). The parent is untouched. Afterwards the editor measures
     * further edits against the saved state, and a second save makes another new entry.
     */
    fun saveAsNew(note: String) {
        val ed = editor ?: return
        val parent = _ui.value.parent ?: return
        if (_ui.value.saving) return
        _ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            try {
                val bytes = withContext(work) { ed.exportChecked() }
                val stored = store.storeEdited(parent, bytes, note, appVersion)
                ed.rebase(bytes)
                _ui.update { it.copy(saving = false, saveDialog = false, saved = stored, parent = stored, moveSource = null) }
                refresh()
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, saveError = "Could not save: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }
}

package org.noxeng.coincell.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.GbfApp
import org.noxeng.coincell.editor.PickerEntry
import org.noxeng.coincell.editor.Pickers
import org.noxeng.coincell.library.BackupEntry
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.sprites.PokemonSpriteKey
import org.noxeng.coincell.sprites.SpriteRules
import org.noxeng.coincell.sprites.SpriteStore
import org.noxeng.coincell.ui.LocalShell
import org.noxeng.coincell.ui.ScreenFrame
import org.noxeng.coincell.ui.components.Severity
import org.noxeng.coincell.ui.components.StatusCard

/** What the editor screens can ask of the app around them. */
class EditorActions(
    /** Close the whole editor (back to where it was opened). */
    val onClose: () -> Unit,
    /** Open the existing restore flow for [BackupEntry]; the editor itself has no way to write a cartridge. */
    val onRestore: (BackupEntry) -> Unit,
    val onViewHistory: (BackupEntry) -> Unit,
)

// ---- sprites (optional pack, always with a text fallback) -----------------------------------------------------------

@Composable
fun rememberSprites(): SpriteStore? {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as? GbfApp)?.sprites }
}

/**
 * The box sprite of a Pokemon (shiny and Unown letter from the DVs, egg sprite for eggs), or a tile with the first
 * three letters of its name when the sprite pack is not in the app or the file is missing.
 */
@Composable
fun PokemonSpriteView(p: PokemonSummary, height: Dp, modifier: Modifier = Modifier) {
    val store = rememberSprites()
    val key = SpriteRules.keyOf(p)
    val bitmap = remember(key, store) { store?.pokemon(key) }
    if (bitmap != null) {
        // Sprites are trimmed to their opaque pixels (SpriteStore), so Fit fills the box; None keeps pixel art crisp.
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(height),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    } else {
        InitialsTile(if (p.isEgg) "EGG" else SpriteRules.initials(p.speciesName), height, modifier)
    }
}

@Composable
fun SpriteKeyView(key: PokemonSpriteKey, name: String, height: Dp, modifier: Modifier = Modifier) {
    val store = rememberSprites()
    val bitmap = remember(key, store) { store?.pokemon(key) }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.size(height), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
    } else {
        InitialsTile(SpriteRules.initials(name), height, modifier)
    }
}

@Composable
fun InitialsTile(text: String, height: Dp, modifier: Modifier = Modifier) {
    Surface(
        modifier.height(height).widthIn68(height),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Same proportions as a 68x56 box sprite. */
private fun Modifier.widthIn68(height: Dp): Modifier = this.then(Modifier.width(height * 68f / 56f))

@Composable
fun isDarkBackground(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** The icon of a bag / held item, nothing when the pack is not in the app, a generic glyph when it has no sprite for this item. */
@Composable
fun ItemIcon(generation: Int, itemId: Int, size: Dp = 32.dp) {
    val store = rememberSprites()
    if (store == null || !store.available) return
    val dark = isDarkBackground()
    val bitmap = remember(generation, itemId, dark) { store.item(generation, itemId, dark) }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
    } else {
        Icon(
            Icons.Filled.Inventory2,
            contentDescription = null,
            modifier = Modifier.size(size * 0.6f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- frame ----------------------------------------------------------------------------------------------------------

/**
 * Every editor screen: top bar without the Device Status Bar (the editor never talks to the reader), the
 * "N unsaved changes" bar, and the dialogs of the history rule (leave prompt, save, result).
 */
@Composable
fun EditorFrame(
    title: String,
    vm: EditorViewModel,
    ui: EditorUi,
    actions: EditorActions,
    onBack: () -> Unit,
    overflow: (@Composable (close: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmRevert by remember { mutableStateOf(false) }
    ScreenFrame(
        title = title,
        onBack = onBack,
        statusBar = false,
        actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Save as new backup") },
                    enabled = ui.hasChanges,
                    onClick = { menu = false; vm.openSaveDialog() },
                )
                DropdownMenuItem(
                    text = { Text("Revert all changes") },
                    enabled = ui.hasChanges,
                    onClick = { menu = false; confirmRevert = true },
                )
                overflow?.invoke { menu = false }
            }
        },
    ) {
        if (ui.hasChanges) ChangesBar(ui.changes.size, onSave = vm::openSaveDialog, onRevert = { confirmRevert = true })
        content()
    }
    if (confirmRevert) {
        AlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text("Revert all changes?") },
            text = { Text("Every edit made in this session is dropped. The backup it was opened from is not affected.") },
            confirmButton = { TextButton(onClick = { confirmRevert = false; vm.revertAll() }) { Text("Revert all") } },
            dismissButton = { TextButton(onClick = { confirmRevert = false }) { Text("Keep editing") } },
        )
    }
    EditorDialogs(vm, ui, actions)
}

@Composable
private fun ChangesBar(count: Int, onSave: () -> Unit, onRevert: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (count == 1) "1 unsaved change" else "$count unsaved changes",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onRevert) { Text("Revert all") }
            TextButton(onClick = onSave) { Text("Save") }
        }
    }
}

/** Discard / Keep editing / Save as new backup, the note dialog and the result dialog. */
@Composable
private fun EditorDialogs(vm: EditorViewModel, ui: EditorUi, actions: EditorActions) {
    if (ui.leavePrompt) {
        AlertDialog(
            onDismissRequest = vm::keepEditing,
            title = { Text("Unsaved changes") },
            text = { Text("You edited this save (${ui.changes.size} change${if (ui.changes.size == 1) "" else "s"}). Closing the editor now would lose them.") },
            confirmButton = { TextButton(onClick = vm::openSaveDialog) { Text("Save as new backup") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.discard(); actions.onClose() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = vm::keepEditing) { Text("Keep editing") }
                }
            },
        )
    }
    if (ui.saveDialog) SaveDialog(vm, ui)
    ui.saved?.let { SavedDialog(vm, it, actions) }
}

@Composable
private fun SaveDialog(vm: EditorViewModel, ui: EditorUi) {
    var note by remember { mutableStateOf(ui.noteSuggestion) }
    AlertDialog(
        onDismissRequest = { if (!ui.saving) vm.dismissSaveDialog() },
        title = { Text("Save as new backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "A new entry tagged Edited is added to this game's history. The backup you opened stays exactly as it is.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(LibraryStore.NOTE_MAX_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note") },
                    supportingText = { Text("${note.length} / ${LibraryStore.NOTE_MAX_LENGTH}") },
                    maxLines = 4,
                    enabled = !ui.saving,
                )
                ui.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveAsNew(note) }, enabled = !ui.saving) { Text(if (ui.saving) "Saving..." else "Save") } },
        dismissButton = { TextButton(onClick = vm::dismissSaveDialog, enabled = !ui.saving) { Text("Cancel") } },
    )
}

@Composable
private fun SavedDialog(vm: EditorViewModel, saved: BackupEntry, actions: EditorActions) {
    val shell = LocalShell.current
    val device by shell.device.state.collectAsState()
    AlertDialog(
        onDismissRequest = vm::dismissSaved,
        title = { Text("Saved as a new backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Added to the history as Edited. The backup you opened was not changed. Nothing was written to a cartridge.")
                OutlinedButton(
                    onClick = { vm.dismissSaved(); actions.onRestore(saved) },
                    enabled = device.restoreAvailable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Restore to cartridge...") }
                if (!device.restoreAvailable) {
                    Text(
                        device.restoreUnavailableReason ?: "Restore is not available right now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.dismissSaved(); actions.onViewHistory(saved) }) { Text("View in history") }
            }
        },
        confirmButton = { TextButton(onClick = { vm.dismissSaved(); actions.onClose() }) { Text("Close editor") } },
        dismissButton = { TextButton(onClick = vm::dismissSaved) { Text("Keep editing") } },
    )
}

/** The screen shown while the save loads or when it can't be opened. */
@Composable
fun EditorStatusScreen(ui: EditorUi, onBack: () -> Unit) {
    ScreenFrame(title = "Pokemon editor", onBack = onBack, statusBar = false) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            when (val p = ui.phase) {
                is EditorPhase.Failed -> StatusCard("This save could not be opened", p.message, Severity.ERROR)
                else -> Text("Reading save...", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---- pickers --------------------------------------------------------------------------------------------------------

/**
 * Searchable list in a bottom sheet (docs/UI_DESIGN.md 6.1): names in the save's language with the English name
 * under them when it differs, search matches both and the number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    title: String,
    entries: List<PickerEntry>,
    onPick: (PickerEntry) -> Unit,
    onDismiss: () -> Unit,
    selectedId: Int? = null,
    noneLabel: String? = null,
    onNone: (() -> Unit)? = null,
    leading: (@Composable (PickerEntry) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val shown = remember(entries, query) { Pickers.search(entries, query) }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxHeight(0.92f).imePadding()) {
            Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search by name or number") },
            )
            LazyColumn(Modifier.weight(1f).nestedScroll(KeepEndOfListInSheet)) {
                if (noneLabel != null && onNone != null && query.isBlank()) {
                    item(key = "none") {
                        ListItem(
                            headlineContent = { Text(noneLabel) },
                            modifier = Modifier.clickableRow(onNone),
                        )
                        HorizontalDivider()
                    }
                }
                items(shown, key = { it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.primary, fontWeight = if (e.id == selectedId) FontWeight.Bold else FontWeight.Normal) },
                        supportingContent = e.secondary?.let { { Text(it) } },
                        leadingContent = leading?.let { l -> { l(e) } },
                        trailingContent = { Text("#${e.id}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickableRow { onPick(e) },
                    )
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing matches \"$query\".",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Swallows what is left of a scroll or fling once the list hits its end (upward leftovers only). Passed on, it made
 * the Material 3 sheet move up and spring back again and again after a long fling. Downward leftovers still reach the
 * sheet, so pulling down at the top of the list still closes it.
 */
private val KeepEndOfListInSheet = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

// ---- numeric input ------------------------------------------------------------------------------------------------

/**
 * A number with minus / plus buttons and direct entry. Valid numbers (inside [range]) are applied at once through
 * [onValue]; an out-of-range number only shows the hint and changes nothing.
 */
@Composable
fun StepperField(
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String,
    step: Int = 1,
    fieldWidth: Dp = 84.dp,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val invalid = parsed == null || parsed !in range
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onValue((value - step).coerceIn(range)) }, enabled = value > range.first) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $description")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(7)
                text = digits
                val n = digits.toIntOrNull()
                if (n != null && n in range && n != value) onValue(n)
            },
            modifier = Modifier.width(fieldWidth).semantics { contentDescription = description },
            singleLine = true,
            isError = invalid,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        IconButton(onClick = { onValue((value + step).coerceIn(range)) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $description")
        }
    }
}

/** A labelled row: [label] on the left, the control on the right. */
@Composable
fun FieldRow(label: String, modifier: Modifier = Modifier, hint: String? = null, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        control()
    }
}

@Composable
fun PrimaryActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

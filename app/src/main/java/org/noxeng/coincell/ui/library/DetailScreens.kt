package org.noxeng.coincell.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.editor.PokemonSaveKind
import org.noxeng.coincell.library.BackupEntry
import org.noxeng.coincell.library.BackupKind
import org.noxeng.coincell.library.GameSummary
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.ui.LocalShell
import org.noxeng.coincell.ui.ScreenFrame
import org.noxeng.coincell.ui.components.BackupChips
import org.noxeng.coincell.ui.components.HeaderTitleLine
import org.noxeng.coincell.ui.components.KeyValueRow
import org.noxeng.coincell.ui.components.MonoText
import org.noxeng.coincell.ui.components.NeutralChip
import org.noxeng.coincell.ui.components.PlatformBadge
import org.noxeng.coincell.ui.components.SectionHeader
import org.noxeng.coincell.ui.components.Severity
import org.noxeng.coincell.ui.components.StatusCard
import org.noxeng.coincell.ui.components.crcText
import org.noxeng.coincell.ui.components.formatDateTime
import org.noxeng.coincell.ui.components.formatSizeKiB
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Schedule

// ---- Game detail -------------------------------------------------------------------------------

@Composable
fun GameDetailScreen(
    vm: GameDetailViewModel,
    onBack: () -> Unit,
    onOpenBackup: (BackupEntry) -> Unit,
    onRestore: (BackupEntry) -> Unit,
    onBackupCart: () -> Unit,
    onGoDevice: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val shell = LocalShell.current
    val device by shell.device.state.collectAsState()
    val game = ui.game
    // The last backup was deleted (or the game never existed): nothing left to show.
    LaunchedEffect(game == null && !ui.loading) { if (game == null && !ui.loading) onBack() }
    val actions = rememberBackupActions(game)
    val rename = rememberRenameGame()
    ScreenFrame(
        title = game?.title ?: "Game",
        onBack = onBack,
        actions = {
            if (game != null) {
                IconButton(onClick = { rename(game.game) }) { Icon(Icons.Filled.Edit, contentDescription = "Rename") }
            }
        },
    ) {
        if (game == null) return@ScreenFrame
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IdentityCard(game, ui, onBackupCart, onGoDevice) }
            item {
                SectionHeader("History (${game.count})")
                device.restoreUnavailableReason?.let {
                    Text(
                        "Restore to cartridge is unavailable: $it",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(game.backups, key = { it.id }) { e ->
                BackupRow(
                    e,
                    restoreEnabled = device.restoreAvailable,
                    onOpen = { onOpenBackup(e) },
                    onExport = { actions.export(e) },
                    onRestore = { onRestore(e) },
                    onDelete = { actions.delete(e) },
                )
            }
        }
    }
}

@Composable
private fun IdentityCard(game: GameSummary, ui: GameDetailUi, onBackupCart: () -> Unit, onGoDevice: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlatformBadge(game.platform)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HeaderTitleLine(game.game.secondaryTitle)
                    Text(
                        "${game.game.identityText}  ·  ${game.game.cartType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (ui.inReader) InReaderChip()
                if (game.hasClock) NeutralChip("Clock", Icons.Filled.Schedule)
            }
            PokemonChip(ui.pokemon)
            if (ui.canBackupCart) {
                Button(onClick = onBackupCart, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                    Text("Back up this cart", Modifier.padding(start = 8.dp))
                }
            } else {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                    Text("Back up this cart", Modifier.padding(start = 8.dp))
                }
                Text(
                    "Insert this game's cartridge and read it on the Device tab to back it up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onGoDevice) { Text("Go to Device") }
            }
        }
    }
}

@Composable
private fun BackupRow(
    e: BackupEntry,
    restoreEnabled: Boolean,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatDateTime(e.timestampMillis), style = MaterialTheme.typography.titleMedium)
                MonoText("${formatSizeKiB(e.size)}  CRC ${crcText(e.crc32)}")
                BackupChips(e)
                if (e.note.isNotEmpty()) {
                    Text(
                        "\"${e.note}\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions for the backup of ${formatDateTime(e.timestampMillis)}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Export .sav") },
                        leadingIcon = { Icon(Icons.Filled.IosShare, contentDescription = null) },
                        onClick = { menu = false; onExport() },
                    )
                    DropdownMenuItem(
                        text = { Text("Restore to cartridge...") },
                        leadingIcon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                        enabled = restoreEnabled,
                        onClick = { menu = false; onRestore() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete backup", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

// ---- Backup detail -----------------------------------------------------------------------------

@Composable
fun BackupDetailScreen(
    vm: BackupDetailViewModel,
    onBack: () -> Unit,
    onRestore: (BackupEntry) -> Unit,
    onOpenEditor: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val entry = ui.entry
    LaunchedEffect(entry == null && !ui.loading) { if (entry == null && !ui.loading) onBack() }
    val actions = rememberBackupActions(ui.game, onDeleted = {})
    var editNote by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    ScreenFrame(
        title = entry?.let { "Backup, ${formatDateTime(it.timestampMillis)}" } ?: "Backup",
        onBack = onBack,
        actions = {
            if (entry != null) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete backup", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; actions.delete(entry) },
                    )
                }
            }
        },
    ) {
        val game = ui.game
        if (entry == null || game == null) return@ScreenFrame
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlatformBadge(game.platform)
                Column(Modifier.weight(1f)) {
                    Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HeaderTitleLine(game.game.secondaryTitle)
                }
            }
            BackupChips(entry)
            PokemonChip(ui.pokemon)
            entry.suspectReason?.let {
                StatusCard("This save looks empty", it, Severity.WARNING)
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp)) {
                    KeyValueRow("Size", "${formatSizeKiB(entry.size)} (${entry.size} B)", mono = true)
                    KeyValueRow("CRC32", crcText(entry.crc32), mono = true)
                    entry.saveType?.let { KeyValueRow("Save type", org.noxeng.coincell.cart.AgbSaveTypes.label(it) + (entry.saveTypeSource?.let { s -> " (${s.label})" } ?: "")) }
                    entry.flashChip?.let { KeyValueRow("Flash chip", it) }
                    KeyValueRow(
                        "Clock",
                        when {
                            entry.clock != null -> "Included (${if (entry.agbRtc != null) 16 else 48} B footer): ${entry.clock!!.describe()}"
                            entry.rtcDeclared -> "Declared by the cartridge but not read"
                            else -> "None"
                        },
                    )
                    KeyValueRow("Created", formatDateTime(entry.timestampMillis))
                    KeyValueRow(
                        "Source",
                        when (entry.kind) {
                            BackupKind.BACKUP -> "Cartridge read, every block read twice"
                            BackupKind.PRE_RESTORE -> "Automatic safety copy before a restore"
                            BackupKind.IMPORTED -> "Imported from ${entry.originalFileName ?: "a file"}, never compared with a cartridge"
                            BackupKind.EDITED -> "Edited in the Pokemon editor, never read from a cartridge"
                        },
                    )
                    if (entry.kind == BackupKind.EDITED) {
                        val parent = entry.parentId?.let { id -> game.backups.firstOrNull { it.id == id } }
                        KeyValueRow(
                            "Based on",
                            when {
                                parent != null -> "Backup of ${formatDateTime(parent.timestampMillis)}"
                                entry.parentId != null -> "A backup that was deleted (${entry.parentId})"
                                else -> "Unknown"
                            },
                        )
                    }
                    if (entry.firmware.isNotEmpty()) KeyValueRow("Reader", entry.firmware)
                    KeyValueRow("App", entry.appVersion.ifEmpty { "unknown" })
                    KeyValueRow("Backup id", entry.id, mono = true)
                }
            }
            SectionHeader("Note")
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            entry.note.ifEmpty { "No note" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (entry.note.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { editNote = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = if (entry.note.isEmpty()) "Add note" else "Edit note")
                    }
                }
            }
            when (ui.pokemon) {
                is PokemonSaveKind.Gen12 -> Button(onClick = onOpenEditor, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.CatchingPokemon, contentDescription = null, Modifier.size(18.dp))
                    Text("Open in Pokemon editor", Modifier.padding(start = 8.dp))
                }
                PokemonSaveKind.GbaLater -> Text(
                    "Pokemon editor for GBA games comes later",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
            FilledTonalButton(onClick = { actions.export(entry) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.IosShare, contentDescription = null, Modifier.size(18.dp))
                Text("Export .sav", Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = { onRestore(entry) },
                enabled = ui.restoreAvailable,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Upload, contentDescription = null, Modifier.size(18.dp))
                Text("Restore to cartridge...", Modifier.padding(start = 8.dp))
            }
            if (!ui.restoreAvailable) {
                Text(
                    ui.restoreReason ?: "Restore is not available right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (ui.otherGameInReader) {
                Text(
                    "The cartridge in the reader is a different game. The review will ask you to type its title before anything is written.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (editNote) {
            NoteDialog(
                initial = entry.note,
                onDismiss = { editNote = false },
                onSave = { text ->
                    editNote = false
                    scope.launch {
                        try {
                            vm.saveNote(entry, text)
                            shell.message("Note saved")
                        } catch (e: Exception) {
                            shell.message("Could not save the note: ${e.message}")
                        }
                    }
                },
            )
        }
    }
}

/** "Pokemon save, Gen 2, Crystal French" for a Gen 1/2 save, a short note for a GBA Pokemon game, nothing otherwise. */
@Composable
private fun PokemonChip(kind: PokemonSaveKind?) {
    when (kind) {
        is PokemonSaveKind.Gen12 -> NeutralChip(kind.info.chipText, Icons.Filled.CatchingPokemon)
        PokemonSaveKind.GbaLater -> NeutralChip("Pokemon GBA (editor comes later)", Icons.Filled.CatchingPokemon)
        else -> Unit
    }
}

@Composable
private fun NoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(LibraryStore.NOTE_MAX_LENGTH) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note for this backup") },
                supportingText = { Text("${text.length} / ${LibraryStore.NOTE_MAX_LENGTH}") },
                maxLines = 4,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

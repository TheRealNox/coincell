package org.noxeng.coincell.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.text.KeyboardActions
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.GameSummary
import org.noxeng.coincell.library.LibrarySort
import org.noxeng.coincell.library.LibraryState
import org.noxeng.coincell.library.PlatformFilter
import org.noxeng.coincell.ui.ScreenFrame
import org.noxeng.coincell.ui.components.CartridgeGlyph
import org.noxeng.coincell.ui.components.NeutralChip
import org.noxeng.coincell.ui.components.PlatformBadge
import org.noxeng.coincell.ui.components.StatusChip
import org.noxeng.coincell.ui.components.StatusCard
import org.noxeng.coincell.ui.components.Severity
import org.noxeng.coincell.ui.components.WarningChip
import org.noxeng.coincell.ui.components.formatDateTime
import org.noxeng.coincell.ui.components.spoken
import org.noxeng.coincell.library.DisplayPlatform
import androidx.compose.material.icons.filled.Schedule

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpenGame: (String) -> Unit, onGoDevice: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val rename = rememberRenameGame()
    val import = rememberImportNewGame(onImported = onOpenGame)
    ScreenFrame(
        title = "Library",
        actions = {
            IconButton(onClick = import) {
                Icon(Icons.Filled.FileOpen, contentDescription = "Import a .sav file")
            }
            IconButton(onClick = {
                if (searchOpen) vm.setSearch("")
                searchOpen = !searchOpen
            }) {
                Icon(
                    if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search games",
                )
            }
            IconButton(onClick = { sortMenu = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort, currently by ${ui.query.sort.label.lowercase()}")
            }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                LibrarySort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text((if (s == ui.query.sort) "✓ " else "") + s.label) },
                        onClick = { vm.setSort(s); sortMenu = false },
                    )
                }
            }
        },
    ) {
        if (searchOpen) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                value = ui.query.search,
                onValueChange = vm::setSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
                singleLine = true,
                label = { Text("Search name, title, code or note") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
            )
        }
        if (ui.total > 0 || ui.query.isFiltering) {
            Row(
                Modifier.horizontalScrollRow().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlatformFilter.entries.forEach { f ->
                    FilterChip(
                        selected = ui.query.filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text(f.label) },
                    )
                }
            }
        }
        when (val state = ui.state) {
            LibraryState.Loading -> LoadingRows()
            is LibraryState.Failed -> Column(Modifier.padding(16.dp)) {
                StatusCard("Could not read the library", state.message, Severity.ERROR) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::retry) { Text("Retry") }
                    }
                }
            }
            is LibraryState.Loaded -> when {
                ui.total == 0 -> EmptyLibrary(onGoDevice, import)
                ui.items.isEmpty() -> NoResults(vm::clearFilters)
                else -> GameList(ui.items, ui.inReaderKey, onOpenGame, rename)
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollRow(): Modifier = this.horizontalScroll(rememberScrollState())

@Composable
private fun LoadingRows() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Card(
                Modifier.fillMaxWidth().heightIn(min = 72.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {}
        }
    }
}

@Composable
private fun EmptyLibrary(onGoDevice: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CartridgeGlyph(DisplayPlatform.GB, size = 96.dp)
        Text("No saves yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Back up a cartridge to start your library, or import a .sav file. Your saves stay on this phone and can be browsed without the reader.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onGoDevice) {
            Icon(Icons.Filled.Usb, contentDescription = null, Modifier.size(18.dp))
            Text("Go to Device", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onImport) {
            Icon(Icons.Filled.FileOpen, contentDescription = null, Modifier.size(18.dp))
            Text("Import a .sav file...", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun NoResults(onClear: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.Inventory2, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("No games match", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onClear) { Text("Clear filters") }
    }
}

@Composable
private fun GameList(items: List<GameSummary>, inReaderKey: String?, onOpenGame: (String) -> Unit, onRename: (GameInfo) -> Unit) {
    // Same title with different checksums (revisions, regions, repro carts): show the checksum to tell them apart.
    val duplicateTitles = items.groupBy { it.game.title.uppercase() }.filterValues { it.size > 1 }.keys
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.key }) { g ->
            GameRow(g, inReader = g.key == inReaderKey, showChecksum = g.game.title.uppercase() in duplicateTitles, onClick = { onOpenGame(g.key) }, onRename = { onRename(g.game) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameRow(g: GameSummary, inReader: Boolean, showChecksum: Boolean, onClick: () -> Unit, onRename: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        colors = CardDefaults.cardColors(containerColor = if (inReader) scheme.primaryContainer else scheme.surfaceContainerHigh),
        border = if (inReader) BorderStroke(2.dp, scheme.primary) else null,
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlatformBadge(g.platform)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                g.game.secondaryTitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (inReader) scheme.onPrimaryContainer else scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "Latest ${formatDateTime(g.latestMillis)}" + if (showChecksum) "  ·  ${g.game.identityText}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (inReader) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (inReader) InReaderChip()
                    if (g.hasClock) NeutralChip("Clock", Icons.Filled.Schedule)
                    if (g.hasSuspect) WarningChip("Looks empty")
                }
            }
            Text(
                "${g.count}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { contentDescription = if (g.count == 1) "1 backup" else "${g.count} backups" },
            )
            Column {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions for ${g.title}")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menu = false; onRename() },
                    )
                }
            }
        }
    }
}

/** "In reader": this game is the cartridge in the reader (usb icon + text on primaryContainer). */
@Composable
fun InReaderChip() {
    val scheme = MaterialTheme.colorScheme
    StatusChip("In reader", Icons.Filled.Usb, scheme.primary, scheme.onPrimary)
}

package com.therealnox.gbflash.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.therealnox.gbflash.editor.Pickers
import com.therealnox.gbflash.editor.SaveSnapshot
import com.therealnox.gbflash.pkcore.PokemonSummary
import com.therealnox.gbflash.pkcore.SlotRef
import com.therealnox.gbflash.ui.LocalShell
import com.therealnox.gbflash.ui.components.SectionHeader

/** What a Pokemon tile says to a screen reader: "Typhlosion level 37, shiny". */
private fun PokemonSummary.spoken(): String =
    if (isEgg) "Egg" else "$speciesName level $level" + (if (isShiny) ", shiny" else "")

/** Party / box tile: sprite (or initials), name, level, shiny star. Used by the overview strip and the box grid. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PokemonTile(
    p: PokemonSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier.width(68.dp),
) {
    Surface(
        modifier
            .semantics(mergeDescendants = true) { contentDescription = p.spoken() + if (selected) ", selected for moving" else "" }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box {
            // fillMaxWidth: the column spans the tile, so the sprite and labels centre in it whatever their widths.
            Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PokemonSpriteView(p, height = 48.dp)
                Text(
                    if (p.isEgg) "Egg" else p.speciesName,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (p.isEgg) " " else "Lv ${p.level}",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (p.isShiny && !p.isEgg) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(14.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun EmptyTile(onClick: () -> Unit, modifier: Modifier = Modifier.width(68.dp), forMove: Boolean) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 76.dp).semantics { contentDescription = if (forMove) "Empty slot, move here" else "Empty slot, add a Pokemon" },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(if (forMove) Icons.Filled.SwapHoriz else Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Party & Boxes ---------------------------------------------------------------------------------------------------

private const val COLUMNS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorBoxesScreen(
    vm: EditorViewModel,
    actions: EditorActions,
    onBack: () -> Unit,
    onOpenPokemon: (SlotRef) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val shell = LocalShell.current
    val snapshot = ui.snapshot
    val model = vm.model
    if (ui.phase != EditorPhase.Ready || snapshot == null || model == null) {
        EditorStatusScreen(ui, onBack)
        return
    }
    var box by rememberSaveable { mutableIntStateOf(snapshot.currentBox.coerceIn(0, snapshot.boxes.lastIndex.coerceAtLeast(0))) }
    var actionsFor by remember { mutableStateOf<SlotRef?>(null) }
    var deleteFor by remember { mutableStateOf<SlotRef?>(null) }
    var addAt by remember { mutableStateOf<SlotRef?>(null) }
    val moving = ui.moveSource
    val movingName = moving?.let { snapshot.pokemonAt(it)?.speciesName }

    fun tap(slot: SlotRef, occupied: Boolean) {
        when {
            moving != null && moving == slot -> vm.cancelMove()
            moving != null -> vm.movePokemon(moving, slot)?.let(shell::message)
            occupied -> onOpenPokemon(slot)
            else -> addAt = slot
        }
    }

    EditorFrame("Party & Boxes", vm, ui, actions, onBack) {
        if (moving != null) {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Moving $movingName: tap a slot. A Pokemon there swaps places; an empty slot sends it to the end.",
                        Modifier.weight(1f).padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = vm::cancelMove) { Text("Cancel") }
                }
            }
        }
        val boxSummary = snapshot.boxes.getOrNull(box)
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Party (${snapshot.party.size} / ${model.partyCapacity})") }
            item(span = { GridItemSpan(maxLineSpan) }) {
                PartyRow(snapshot, model.partyCapacity, moving, ::tap) { actionsFor = it }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                BoxSwitcher(
                    index = box,
                    count = snapshot.boxes.size,
                    name = boxSummary?.name.orEmpty(),
                    isCurrent = boxSummary?.isCurrent == true,
                    used = boxSummary?.pokemon?.size ?: 0,
                    capacity = model.boxCapacity,
                    onChange = { box = it },
                )
            }
            val slots = boxSummary?.pokemon.orEmpty()
            val emptyCount = (model.boxCapacity - slots.size).coerceAtLeast(0)
            itemsIndexed(slots, key = { i, _ -> "b$box-$i" }) { i, p ->
                val ref = SlotRef.Box(box, i)
                PokemonTile(
                    p,
                    selected = moving == ref,
                    onClick = { tap(ref, true) },
                    onLongClick = { actionsFor = ref },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            itemsIndexed(List(emptyCount) { it }, key = { _, k -> "e$box-$k" }) { k, _ ->
                val ref = SlotRef.Box(box, slots.size + k)
                EmptyTile({ tap(ref, false) }, Modifier.fillMaxWidth(), forMove = moving != null)
            }
        }
    }

    actionsFor?.let { slot ->
        val p = snapshot.pokemonAt(slot)
        if (p == null) {
            actionsFor = null
        } else {
            SlotActionsSheet(
                p,
                onDismiss = { actionsFor = null },
                onEdit = { actionsFor = null; onOpenPokemon(slot) },
                onMove = { actionsFor = null; vm.startMove(slot) },
                onDelete = { actionsFor = null; deleteFor = slot },
            )
        }
    }
    deleteFor?.let { slot ->
        val p = snapshot.pokemonAt(slot)
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Remove ${p?.let { if (it.isEgg) "this egg" else it.speciesName } ?: "this Pokemon"}?") },
            text = { Text("The Pokemon is deleted from the working copy; the slots after it move up. Nothing is lost for good until you save, and \"Revert all\" brings it back.") },
            confirmButton = {
                TextButton(onClick = { deleteFor = null; vm.removePokemon(slot)?.let(shell::message) }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
        )
    }
    addAt?.let { slot ->
        val entries = remember(model) { Pickers.species(model.generation, model.language) }
        PickerSheet(
            title = "Add a Pokemon",
            entries = entries,
            onDismiss = { addAt = null },
            onPick = { e ->
                addAt = null
                vm.addPokemon(slot, e.id) { placed -> onOpenPokemon(placed) }?.let(shell::message)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotActionsSheet(p: PokemonSummary, onDismiss: () -> Unit, onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                if (p.isEgg) "Egg (${p.speciesName})" else "${p.speciesName}, level ${p.level}",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            ListItem(
                headlineContent = { Text("Edit") },
                leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                modifier = Modifier.rowClick(onEdit),
            )
            ListItem(
                headlineContent = { Text("Move or swap...") },
                supportingContent = { Text("Then tap the destination slot") },
                leadingContent = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                modifier = Modifier.rowClick(onMove),
            )
            ListItem(
                headlineContent = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.rowClick(onDelete),
            )
        }
    }
}

private fun Modifier.rowClick(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

@Composable
private fun PartyRow(
    snapshot: SaveSnapshot,
    capacity: Int,
    moving: SlotRef?,
    tap: (SlotRef, Boolean) -> Unit,
    onLongPress: (SlotRef) -> Unit,
) {
    // One equal-width column per party slot, spanning the screen like the box grid below.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        snapshot.party.forEachIndexed { i, p ->
            val ref = SlotRef.Party(i)
            PokemonTile(p, selected = moving == ref, onClick = { tap(ref, true) }, onLongClick = { onLongPress(ref) }, modifier = Modifier.weight(1f))
        }
        if (snapshot.party.size < capacity) {
            val ref = SlotRef.Party(snapshot.party.size)
            EmptyTile({ tap(ref, false) }, Modifier.weight(1f), forMove = moving != null)
        }
        repeat((capacity - snapshot.party.size - 1).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun BoxSwitcher(index: Int, count: Int, name: String, isCurrent: Boolean, used: Int, capacity: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((index - 1 + count) % count) }, enabled = count > 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous box")
        }
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "Box ${index + 1} of $count, $name, $used of $capacity used" }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Box ${index + 1} / $count", style = MaterialTheme.typography.titleMedium)
            Text(
                "$name · $used / $capacity" + if (isCurrent) " · current box" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onChange((index + 1) % count) }, enabled = count > 1) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next box")
        }
    }
}

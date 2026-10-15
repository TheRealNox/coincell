package org.noxeng.coincell.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.editor.Pickers
import org.noxeng.coincell.editor.SaveDiff
import org.noxeng.coincell.pkcore.ItemPocket
import org.noxeng.coincell.pkcore.ItemStack
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.ui.LocalShell

// ---- Bag ------------------------------------------------------------------------------------------------------------

@Composable
fun EditorBagScreen(vm: EditorViewModel, actions: EditorActions, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val shell = LocalShell.current
    val snapshot = ui.snapshot
    val model = vm.model
    if (ui.phase != EditorPhase.Ready || snapshot == null || model == null) {
        EditorStatusScreen(ui, onBack)
        return
    }
    val pockets = snapshot.inventory.pockets
    var chosen by rememberSaveable { mutableIntStateOf(-1) }
    // The ordinary items first (the pockets come in the save's own order, TM/HM first in Gen 2).
    val selected = if (chosen >= 0) chosen else pockets.indexOfFirst { it.type == PocketType.ITEMS }.coerceAtLeast(0)
    val pocket = pockets.getOrNull(selected.coerceIn(0, (pockets.size - 1).coerceAtLeast(0)))
    var adding by remember { mutableStateOf(false) }
    val gen = model.generation

    fun update(type: PocketType, items: List<ItemStack>) {
        vm.setPocket(type, items)?.let(shell::message)
    }

    EditorFrame("Bag", vm, ui, actions, onBack) {
        if (pocket == null) {
            Text("This save has no bag.", Modifier.padding(16.dp))
            return@EditorFrame
        }
        PrimaryScrollableTabRow(selectedTabIndex = pockets.indexOf(pocket), edgePadding = 8.dp) {
            pockets.forEachIndexed { i, p ->
                Tab(selected = p === pocket, onClick = { chosen = i }, text = { Text(SaveDiff.pocketLabel(p.type)) })
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${pocket.items.size} / ${pocket.capacity} slots used",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { adding = true },
                        enabled = pocket.items.size < pocket.capacity,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                        Text("Add an item", Modifier.padding(start = 8.dp))
                    }
                    if (pocket.items.size >= pocket.capacity) {
                        Text("This pocket is full.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (pocket.items.isEmpty()) {
                item(key = "empty") { Text("Nothing in this pocket.", style = MaterialTheme.typography.bodyMedium) }
            }
            itemsIndexed(pocket.items, key = { _, s -> "${pocket.type}-${s.id}" }) { index, stack ->
                ItemRow(
                    stack = stack,
                    pocket = pocket,
                    name = model.strings.itemName(gen, stack.id),
                    english = model.englishStrings.itemName(gen, stack.id),
                    generation = gen,
                    onCount = { n -> update(pocket.type, pocket.items.toMutableList().also { it[index] = stack.copy(count = n) }) },
                    onDelete = { update(pocket.type, pocket.items.toMutableList().also { it.removeAt(index) }) },
                )
            }
        }
    }

    if (adding && pocket != null) {
        val present = pocket.items.map { it.id }.toSet()
        val entries = remember(pocket.type, present, model) {
            Pickers.items(gen, pocket.validItems.filter { it !in present }, model.language)
        }
        PickerSheet(
            title = SaveDiff.pocketLabel(pocket.type),
            entries = entries,
            onDismiss = { adding = false },
            onPick = { e ->
                adding = false
                update(pocket.type, pocket.items + ItemStack(e.id, 1))
            },
            leading = { e -> ItemIcon(gen, e.id) },
        )
    }
}

@Composable
private fun ItemRow(
    stack: ItemStack,
    pocket: ItemPocket,
    name: String,
    english: String,
    generation: Int,
    onCount: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val max = pocket.maxCountOf(stack.id)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(36.dp), horizontalArrangement = Arrangement.Center) { ItemIcon(generation, stack.id) }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    if (english != name) Text(english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Remove $name") }
            }
            if (pocket.type != PocketType.KEY_ITEMS && max > 1) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity (max $max)", Modifier.weight(1f).padding(start = 44.dp), style = MaterialTheme.typography.bodyMedium)
                    StepperField(stack.count.coerceAtMost(max), 1..max, onCount, description = "quantity of $name", fieldWidth = 64.dp)
                }
            }
        }
    }
}

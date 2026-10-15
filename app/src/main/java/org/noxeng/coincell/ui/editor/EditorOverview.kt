package org.noxeng.coincell.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.CatchingPokemon
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.editor.Badges
import org.noxeng.coincell.editor.DexCounts
import org.noxeng.coincell.editor.PokemonSaveInfo
import org.noxeng.coincell.editor.SaveEditor
import org.noxeng.coincell.editor.SaveSnapshot
import org.noxeng.coincell.editor.TextCheck
import org.noxeng.coincell.editor.TrainerLimits
import org.noxeng.coincell.pkcore.PlayTime
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.Trainer
import org.noxeng.coincell.pkcore.TrainerGender
import org.noxeng.coincell.ui.LocalShell
import org.noxeng.coincell.ui.components.KeyValueRow
import org.noxeng.coincell.ui.components.NeutralChip
import org.noxeng.coincell.ui.components.SectionHeader
import org.noxeng.coincell.ui.components.formatDateTime

// ---- Save overview (docs/UI_DESIGN.md 4.8) ----------------------------------------------------------------------------

@Composable
fun EditorOverviewScreen(
    vm: EditorViewModel,
    actions: EditorActions,
    onOpenBoxes: () -> Unit,
    onOpenPokemon: (SlotRef) -> Unit,
    onOpenBag: () -> Unit,
    onOpenTrainer: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val closeOrAsk = { if (vm.requestLeave()) actions.onClose() }
    // Unsaved edits: the back arrow and the (predictive) back gesture both ask first.
    BackHandler(enabled = ui.hasChanges) { closeOrAsk() }
    val snapshot = ui.snapshot
    val model = vm.model
    if (ui.phase != EditorPhase.Ready || snapshot == null || model == null) {
        EditorStatusScreen(ui, onBack = actions.onClose)
        return
    }
    EditorFrame(
        title = (ui.game?.label ?: "Pokemon") + " · Editor",
        vm = vm,
        ui = ui,
        actions = actions,
        onBack = closeOrAsk,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.info?.let { InfoRow(it, ui.parent?.timestampMillis) }
            TrainerCard(snapshot.trainer, model, onClick = onOpenTrainer)
            DexCard(snapshot.dex)
            SectionHeader("Party")
            PartyStrip(snapshot, onOpenPokemon)
            NavRow(Icons.Filled.CatchingPokemon, "Party & Boxes", "${snapshot.party.size} in the party, ${snapshot.boxedCount} in the boxes", onOpenBoxes)
            val itemCount = snapshot.inventory.pockets.filter { it.type != PocketType.PC_ITEMS }.sumOf { it.items.size }
            val pcCount = snapshot.inventory.pocket(PocketType.PC_ITEMS)?.items?.size ?: 0
            NavRow(Icons.Filled.Backpack, "Bag", "$itemCount stacks in the bag, $pcCount in the PC", onOpenBag)
            NavRow(Icons.Filled.Person, "Trainer", "Name, money, coins, badges, play time", onOpenTrainer)
            LegalityNote()
            PrimaryActionButton("Save as new backup", onClick = vm::openSaveDialog, enabled = ui.hasChanges)
            if (!ui.hasChanges) {
                Text(
                    "Nothing changed yet. Edits are kept in memory; saving always adds a new backup to the history and never overwrites one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(info: PokemonSaveInfo, parentMillis: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NeutralChip(info.chipText, Icons.Filled.CatchingPokemon)
        if (parentMillis != null) {
            Text(
                "Editing a copy of the backup of ${formatDateTime(parentMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pokedex progress: caught and seen, each with a bar, out of the generation's species. */
@Composable
private fun DexCard(dex: DexCounts) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pokedex", style = MaterialTheme.typography.titleMedium)
            DexBar("Caught", dex.caught, dex.total)
            DexBar("Seen", dex.seen, dex.total)
        }
    }
}

@Composable
private fun DexBar(label: String, count: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$count / $total", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else count.toFloat() / total },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label $count of $total" },
            drawStopIndicator = {},
        )
    }
}

/** Plain statement, not a fake check: pkcore has no legality module. */
@Composable
fun LegalityNote() {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Legality check: not available yet. The editor checks that values fit the game's data, not that a Pokemon could exist legitimately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        Modifier.fillMaxWidth().heightIn(min = 64.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun TrainerCard(t: Trainer, model: SaveEditor, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name.ifEmpty { "(no name)" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Icon(Icons.Filled.Edit, contentDescription = "Edit trainer")
            }
            KeyValueRow("Trainer ID", "%05d".format(t.id), mono = true)
            t.secretId?.let { KeyValueRow("Secret ID", "%05d".format(it), mono = true) }
            KeyValueRow("Money", "%,d".format(t.money))
            if (model.generation >= 2) KeyValueRow("Coins", "%,d".format(t.coins))
            KeyValueRow("Badges", "${t.badgeCount} / ${Badges.list(model.game, model.language).size}")
            KeyValueRow("Play time", TrainerLimits.formatTime(t.playTime), mono = true)
            t.rivalName?.let { KeyValueRow("Rival", it.ifEmpty { "-" }) }
            t.gender?.let { KeyValueRow("Gender", if (it == TrainerGender.BOY) "Boy" else "Girl") }
        }
    }
}

@Composable
fun PartyStrip(snapshot: SaveSnapshot, onOpen: (SlotRef) -> Unit) {
    if (snapshot.party.isEmpty()) {
        Text("The party is empty.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    // Six equal columns (the party size), so tiles line up and spread across the card whatever the party size.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        snapshot.party.forEachIndexed { i, p ->
            PokemonTile(p, selected = false, onClick = { onOpen(SlotRef.Party(i)) }, onLongClick = null, modifier = Modifier.weight(1f))
        }
        repeat((PARTY_SLOTS - snapshot.party.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

// ---- Trainer edit -----------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorTrainerScreen(vm: EditorViewModel, actions: EditorActions, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val shell = LocalShell.current
    val snapshot = ui.snapshot
    val model = vm.model
    if (ui.phase != EditorPhase.Ready || snapshot == null || model == null) {
        EditorStatusScreen(ui, onBack)
        return
    }
    val t = snapshot.trainer
    val gen = model.generation
    val nameMax = TrainerLimits.nameLength(model.isJapanese)

    var name by remember { mutableStateOf(t.name) }
    var rival by remember { mutableStateOf(t.rivalName.orEmpty()) }
    var id by remember { mutableIntStateOf(t.id) }
    var secretId by remember { mutableIntStateOf(t.secretId ?: 0) }
    var money by remember { mutableIntStateOf(t.money) }
    var coins by remember { mutableIntStateOf(t.coins) }
    var badges by remember { mutableIntStateOf(t.badges) }
    var hours by remember { mutableIntStateOf(t.playTime.hours) }
    var minutes by remember { mutableIntStateOf(t.playTime.minutes) }
    var seconds by remember { mutableIntStateOf(t.playTime.seconds) }
    var gender by remember { mutableStateOf(t.gender) }

    val nameError = TextCheck.check(name.trim(), nameMax, gen, model.language, model.isJapanese, "name")
        ?: if (name.isBlank()) "The name can't be empty." else null
    val hasRival = t.rivalName != null
    val rivalError = if (!hasRival) {
        null
    } else {
        TextCheck.check(rival.trim(), nameMax, gen, model.language, model.isJapanese, "rival name")
            ?: if (rival.isBlank()) "The rival name can't be empty." else null
    }
    val edited = Trainer(
        name.trim(), id, money, badges, PlayTime(hours, minutes, seconds), if (hasRival) rival.trim() else null, gender,
        if (gen >= 2) coins else t.coins, if (t.secretId != null) secretId else null,
    )
    val changed = edited != t
    val allowed = remember(model) { TextCheck.allowedCharacters(gen, model.language, model.isJapanese) }

    EditorFrame("Trainer", vm, ui, actions, onBack) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(nameMax + 3) },
                label = { Text("Name") },
                isError = nameError != null,
                supportingText = { Text(nameError ?: "Up to $nameMax characters.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasRival) {
                OutlinedTextField(
                    value = rival,
                    onValueChange = { rival = it.take(nameMax + 3) },
                    label = { Text("Rival name") },
                    isError = rivalError != null,
                    supportingText = { Text(rivalError ?: "Up to $nameMax characters.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (allowed.isNotEmpty()) {
                Text(
                    "Characters this save can store: $allowed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FieldRow("Trainer ID", hint = "0 to 65535") {
                StepperField(id, 0..65535, { id = it }, description = "trainer ID", fieldWidth = 100.dp)
            }
            if (t.secretId != null) {
                FieldRow("Secret ID", hint = "0 to 65535. Pokemon keep their own copy of the IDs, so yours stay as they are.") {
                    StepperField(secretId, 0..65535, { secretId = it }, description = "secret ID", fieldWidth = 100.dp)
                }
            }
            FieldRow("Money", hint = "0 to ${"%,d".format(TrainerLimits.MAX_MONEY)}") {
                StepperField(money, 0..TrainerLimits.MAX_MONEY, { money = it }, description = "money", step = 1000, fieldWidth = 120.dp)
            }
            if (gen >= 2) {
                FieldRow("Game Corner coins", hint = "0 to ${"%,d".format(TrainerLimits.MAX_COINS)}") {
                    StepperField(coins, 0..TrainerLimits.MAX_COINS, { coins = it }, description = "coins", step = 100, fieldWidth = 100.dp)
                }
            }
            SectionHeader("Play time")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hours", style = MaterialTheme.typography.labelMedium)
                    StepperField(hours, 0..TrainerLimits.maxHours(gen), { hours = it }, description = "hours", fieldWidth = 64.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Minutes", style = MaterialTheme.typography.labelMedium)
                    StepperField(minutes, 0..59, { minutes = it }, description = "minutes", fieldWidth = 64.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Seconds", style = MaterialTheme.typography.labelMedium)
                    StepperField(seconds, 0..59, { seconds = it }, description = "seconds", fieldWidth = 64.dp)
                }
            }
            SectionHeader("Badges (${Integer.bitCount(badges)})")
            val list = Badges.list(model.game, model.language)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { b ->
                    FilterChip(
                        selected = badges and (1 shl b.bit) != 0,
                        onClick = { badges = badges xor (1 shl b.bit) },
                        label = { Text(if (gen == 2) "${b.name} (${b.region})" else b.name) },
                    )
                }
            }
            t.gender?.let {
                SectionHeader("Gender")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(TrainerGender.BOY to "Boy", TrainerGender.GIRL to "Girl").forEachIndexed { i, (g, label) ->
                        SegmentedButton(
                            selected = gender == g,
                            onClick = { gender = g },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
            }
            PrimaryActionButton(
                "Apply",
                enabled = changed && nameError == null && rivalError == null,
                onClick = {
                    val error = vm.updateTrainer(edited)
                    if (error != null) shell.message(error) else onBack()
                },
            )
        }
    }
}

/** Party size (all generations). */
private const val PARTY_SLOTS = 6

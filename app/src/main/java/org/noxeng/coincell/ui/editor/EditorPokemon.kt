package org.noxeng.coincell.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.editor.DvStat
import org.noxeng.coincell.editor.PickerEntry
import org.noxeng.coincell.editor.PokemonEdits
import org.noxeng.coincell.editor.Pickers
import org.noxeng.coincell.editor.SaveEditor
import org.noxeng.coincell.editor.StatExpStat
import org.noxeng.coincell.editor.StatLabels
import org.noxeng.coincell.editor.TypeNames
import org.noxeng.coincell.pkcore.Gender
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.gb.GbPokemon
import org.noxeng.coincell.sprites.SpriteRules
import org.noxeng.coincell.ui.LocalShell
import org.noxeng.coincell.ui.components.NeutralChip
import org.noxeng.coincell.ui.components.SectionHeader

private enum class PokemonTab(val label: String) { SUMMARY("Summary"), MOVES("Moves"), STATS("Stats"), MET("Met") }

// ---- Pokemon detail and edit ------------------------------------------------------------------------------------------

@Composable
fun EditorPokemonScreen(vm: EditorViewModel, actions: EditorActions, slot: SlotRef, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val shell = LocalShell.current
    val snapshot = ui.snapshot
    val model = vm.model
    if (ui.phase != EditorPhase.Ready || snapshot == null || model == null) {
        EditorStatusScreen(ui, onBack)
        return
    }
    val p = snapshot.pokemonAt(slot)
    // The Pokemon is gone (removed, moved, or the changes were reverted): nothing to show here.
    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val tabs = remember(model) { if (model.generation == 2) PokemonTab.entries else PokemonTab.entries - PokemonTab.MET }
    var tab by rememberSaveable { mutableStateOf(PokemonTab.SUMMARY.name) }
    val current = tabs.firstOrNull { it.name == tab } ?: PokemonTab.SUMMARY

    fun apply(block: (GbPokemon) -> Unit) {
        vm.edit(slot, block)?.let(shell::message)
    }

    EditorFrame(
        title = p.nickname.ifBlank { p.speciesName },
        vm = vm,
        ui = ui,
        actions = actions,
        onBack = onBack,
        overflow = { close ->
            DropdownMenuItem(
                text = { Text("Heal (HP, status, PP)") },
                onClick = { close(); vm.heal(slot)?.let(shell::message) },
            )
            if (model.generation == 2) {
                DropdownMenuItem(
                    text = { Text("Make shiny") },
                    enabled = !p.isShiny,
                    onClick = { close(); vm.makeShiny(slot)?.let(shell::message) },
                )
            }
        },
    ) {
        Header(p, model, slot)
        PrimaryTabRow(selectedTabIndex = tabs.indexOf(current)) {
            tabs.forEach { t ->
                Tab(selected = t == current, onClick = { tab = t.name }, text = { Text(t.label) })
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (current) {
                PokemonTab.SUMMARY -> SummaryTab(p, model, slot, ::apply)
                PokemonTab.MOVES -> MovesTab(p, model, ::apply)
                PokemonTab.STATS -> StatsTab(p, model, ::apply, onMakeShiny = { vm.makeShiny(slot)?.let(shell::message) })
                PokemonTab.MET -> MetTab(p, model, ::apply)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(p: PokemonSummary, model: SaveEditor, slot: SlotRef) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PokemonSpriteView(p, height = 88.dp, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (p.isEgg) "Egg (${p.speciesName})" else p.speciesName, style = MaterialTheme.typography.titleLarge)
            val english = model.englishStrings.speciesName(p.species)
            if (english != p.speciesName) Text(english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NeutralChip(if (p.isEgg) "Egg" else "Lv ${p.level}", Icons.Filled.Edit)
                if (p.isShiny && !p.isEgg) NeutralChip("Shiny", Icons.Filled.Star)
                NeutralChip(
                    when (slot) {
                        is SlotRef.Party -> "Party ${slot.index + 1}"
                        is SlotRef.Box -> "Box ${slot.box + 1}, slot ${slot.index + 1}"
                    },
                    Icons.Filled.Edit,
                )
            }
        }
    }
}

// ---- Summary --------------------------------------------------------------------------------------------------------

@Composable
private fun SummaryTab(p: PokemonSummary, model: SaveEditor, slot: SlotRef, apply: (((GbPokemon) -> Unit)) -> Unit) {
    var pickSpecies by remember { mutableStateOf(false) }
    var pickItem by remember { mutableStateOf(false) }
    var editNickname by remember { mutableStateOf(false) }
    var editOt by remember { mutableStateOf(false) }
    val gen2 = model.generation == 2

    ClickRow("Species", p.speciesName + model.englishStrings.speciesName(p.species).let { if (it != p.speciesName) " ($it)" else "" }) { pickSpecies = true }
    ClickRow("Nickname", p.nickname.ifBlank { "(none)" } + if (!p.isNicknamed) "  (species name)" else "") { editNickname = true }
    FieldRow("Level") {
        StepperField(p.level, 1..100, { v -> apply { PokemonEdits.setLevel(it, v) } }, description = "level", fieldWidth = 72.dp)
    }
    FieldRow("Experience", hint = "Level ${p.level}; the level follows the experience") {
        StepperField(p.exp, 0..PokemonEdits.maxExp(p.generation, p.species), { v -> apply { PokemonEdits.setExp(it, v) } }, description = "experience", step = 100, fieldWidth = 112.dp)
    }
    ClickRow("Original trainer", p.otName.ifBlank { "(none)" }) { editOt = true }
    FieldRow("Trainer ID", hint = "0 to 65535") {
        StepperField(p.trainerId, 0..65535, { v -> apply { PokemonEdits.setTrainerId(it, v) } }, description = "trainer ID", fieldWidth = 100.dp)
    }
    if (gen2) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { pickItem = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Held item", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (p.heldItem == 0) "None" else p.heldItemName + model.englishStrings.itemName(2, p.heldItem).let { if (it != p.heldItemName) " ($it)" else "" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (p.heldItem != 0) ItemIcon(2, p.heldItem)
            Icon(Icons.Filled.Edit, contentDescription = "Change the held item")
        }
        FieldRow("Friendship", hint = if (p.isEgg) "For an egg: steps left to hatch" else "0 to 255") {
            StepperField(p.friendship ?: 0, 0..255, { v -> apply { PokemonEdits.setFriendship(it, v) } }, description = "friendship", fieldWidth = 72.dp)
        }
        FieldRow("Egg", hint = "Shows the egg sprite; the species is kept") {
            Switch(checked = p.isEgg, onCheckedChange = { v -> apply { PokemonEdits.setEgg(it, v) } })
        }
        if (p.isShiny) NeutralChip("Shiny (derived from the DVs)", Icons.Filled.Star)
    } else {
        Text("Generation 1 has no held items, friendship or shininess.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (slot is SlotRef.Party) {
        Text(
            "HP ${p.currentHp} / ${p.storedStats.hp}" + if (p.status != 0) "  ·  status ${p.status}" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }

    if (pickSpecies) {
        val entries = remember(model) { Pickers.species(model.generation, model.language) }
        PickerSheet(
            title = "Species",
            entries = entries,
            selectedId = p.species,
            onDismiss = { pickSpecies = false },
            onPick = { e -> pickSpecies = false; apply { PokemonEdits.setSpecies(it, e.id) } },
            leading = { e -> PickerSprite(e) },
        )
    }
    if (pickItem) {
        val entries = remember(model) { Pickers.heldItems(model.language) }
        PickerSheet(
            title = "Held item",
            entries = entries,
            selectedId = p.heldItem,
            onDismiss = { pickItem = false },
            noneLabel = "No item",
            onNone = { pickItem = false; apply { PokemonEdits.setHeldItem(it, 0) } },
            onPick = { e -> pickItem = false; apply { PokemonEdits.setHeldItem(it, e.id) } },
            leading = { e -> ItemIcon(2, e.id) },
        )
    }
    if (editNickname) {
        TextInputDialog(
            title = "Nickname",
            initial = if (p.isNicknamed) p.nickname else "",
            hint = "Leave empty to use the species name. Up to ${if (model.isJapanese) 5 else 10} characters.",
            onDismiss = { editNickname = false },
            onConfirm = { text -> editNickname = false; apply { PokemonEdits.setNickname(it, text) } },
        )
    }
    if (editOt) {
        TextInputDialog(
            title = "Original trainer",
            initial = p.otName,
            hint = "Up to ${if (model.isJapanese) 5 else 7} characters.",
            onDismiss = { editOt = false },
            onConfirm = { text -> editOt = false; apply { PokemonEdits.setOtName(it, text) } },
        )
    }
}

@Composable
private fun PickerSprite(e: PickerEntry) {
    SpriteKeyView(
        org.noxeng.coincell.sprites.PokemonSpriteKey(e.id, shiny = false, form = 0, female = false, egg = false),
        name = e.primary,
        height = 32.dp,
    )
}

@Composable
private fun ClickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.Edit, contentDescription = "Change $label")
    }
}

// ---- Moves ----------------------------------------------------------------------------------------------------------

@Composable
private fun MovesTab(p: PokemonSummary, model: SaveEditor, apply: (((GbPokemon) -> Unit)) -> Unit) {
    var pickSlot by remember { mutableStateOf<Int?>(null) }
    val firstFree = p.moves.indexOfFirst { it.id == 0 }.let { if (it < 0) 4 else it }
    p.moves.forEachIndexed { i, m ->
        if (m.id == 0 && i > firstFree) return@forEachIndexed
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { pickSlot = i },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        if (m.id == 0) {
                            Text("Move ${i + 1}: add a move", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text(m.name, style = MaterialTheme.typography.titleMedium)
                            val en = model.englishStrings.moveName(m.id)
                            if (en != m.name) Text(en, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (m.id != 0) {
                        Icon(Icons.Filled.Edit, contentDescription = "Change move ${i + 1}")
                        IconButton(onClick = { apply { PokemonEdits.clearMove(it, i) } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove move ${i + 1}")
                        }
                    }
                }
                if (m.id != 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("PP (max ${m.maxPp})", style = MaterialTheme.typography.labelMedium)
                            StepperField(m.pp, 0..m.maxPp, { v -> apply { PokemonEdits.setPp(it, i, v) } }, description = "PP of move ${i + 1}", fieldWidth = 56.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("PP Ups", style = MaterialTheme.typography.labelMedium)
                            StepperField(m.ppUps, 0..3, { v -> apply { PokemonEdits.setPpUps(it, i, v) } }, description = "PP Ups of move ${i + 1}", fieldWidth = 56.dp)
                        }
                    }
                }
            }
        }
    }
    pickSlot?.let { slot ->
        val entries = remember(model) { Pickers.moves(model.generation, model.language) }
        PickerSheet(
            title = "Move ${slot + 1}",
            entries = entries,
            selectedId = p.moves.getOrNull(slot)?.id,
            onDismiss = { pickSlot = null },
            onPick = { e -> pickSlot = null; apply { PokemonEdits.setMove(it, slot, e.id) } },
        )
    }
}

// ---- Stats ----------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsTab(p: PokemonSummary, model: SaveEditor, apply: (((GbPokemon) -> Unit)) -> Unit, onMakeShiny: () -> Unit) {
    val lang = model.language
    val gen2 = model.generation == 2
    Text(
        "DVs (0-15) and stat experience (0-65535) are the Generation 1/2 equivalents of IVs and EVs. The totals follow your edits.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth()) {
        Text("", Modifier.weight(1.3f))
        Text("DV", Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text("Stat exp", Modifier.width(100.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text("Total", Modifier.weight(0.7f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
    StatRow(StatLabels.hp(lang), p.dvs.hp, null, p.statExp.hp, { v -> apply { PokemonEdits.setStatExp(it, StatExpStat.HP, v) } }, p.stats.hp.toString())
    StatRow(StatLabels.attack(lang), p.dvs.attack, { v -> apply { PokemonEdits.setDv(it, DvStat.ATTACK, v) } }, p.statExp.attack, { v -> apply { PokemonEdits.setStatExp(it, StatExpStat.ATTACK, v) } }, p.stats.attack.toString())
    StatRow(StatLabels.defense(lang), p.dvs.defense, { v -> apply { PokemonEdits.setDv(it, DvStat.DEFENSE, v) } }, p.statExp.defense, { v -> apply { PokemonEdits.setStatExp(it, StatExpStat.DEFENSE, v) } }, p.stats.defense.toString())
    StatRow(StatLabels.speed(lang), p.dvs.speed, { v -> apply { PokemonEdits.setDv(it, DvStat.SPEED, v) } }, p.statExp.speed, { v -> apply { PokemonEdits.setStatExp(it, StatExpStat.SPEED, v) } }, p.stats.speed.toString())
    StatRow(
        StatLabels.special(lang), p.dvs.special, { v -> apply { PokemonEdits.setDv(it, DvStat.SPECIAL, v) } },
        p.statExp.special, { v -> apply { PokemonEdits.setStatExp(it, StatExpStat.SPECIAL, v) } },
        if (gen2) "${p.stats.specialAttack}/${p.stats.specialDefense}" else p.stats.specialAttack.toString(),
    )
    if (gen2) {
        Text(
            "${StatLabels.spAtk(lang)} ${p.stats.specialAttack}, ${StatLabels.spDef(lang)} ${p.stats.specialDefense} (both use the Special DV and stat exp).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text("The HP DV is not stored: it comes from the other four DVs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { apply { pk -> DvStat.entries.forEach { PokemonEdits.setDv(pk, it, 15) } } }) { Text("Perfect DVs") }
        OutlinedButton(onClick = { apply { pk -> StatExpStat.entries.forEach { PokemonEdits.setStatExp(pk, it, 65535) } } }) { Text("Max stat exp") }
        OutlinedButton(onClick = { apply { pk -> StatExpStat.entries.forEach { PokemonEdits.setStatExp(pk, it, 0) } } }) { Text("Clear stat exp") }
    }

    SectionHeader("Derived from the DVs")
    if (gen2) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(if (p.isShiny) "Shiny: yes" else "Shiny: no", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Shininess is not stored: it exists when Defense, Speed and Special are 10 and Attack is 2, 3, 6, 7, 10, 11, 14 or 15.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!p.isShiny) OutlinedButton(onClick = onMakeShiny) { Text("Make shiny") }
        }
        Text("Hidden Power type: ${TypeNames.hiddenPower(p.hiddenPowerType, lang)}", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("Generation 1 has no shiny Pokemon and no Hidden Power.", style = MaterialTheme.typography.bodyMedium)
    }
    Text(
        "Gender: " + when (p.gender) {
            Gender.MALE -> "male"
            Gender.FEMALE -> "female"
            Gender.GENDERLESS -> "none"
        } + if (gen2 && p.gender != Gender.GENDERLESS) " (from the Attack DV)" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (p.species == SpriteRules.UNOWN) {
        val letter = ('A' + SpriteRules.unownForm(p.dvs))
        Text("Unown letter: $letter (from the DVs; changing them changes the letter)", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatRow(
    label: String,
    dv: Int,
    onDv: ((Int) -> Unit)?,
    statExp: Int,
    onStatExp: (Int) -> Unit,
    total: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        if (onDv != null) {
            CompactNumberField(dv, 0..15, onDv, Modifier.width(72.dp), "$label DV")
        } else {
            Text(dv.toString(), Modifier.width(72.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CompactNumberField(statExp, 0..65535, onStatExp, Modifier.width(100.dp), "$label stat experience")
        Text(total, Modifier.weight(0.7f), textAlign = TextAlign.End, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CompactNumberField(value: Int, range: IntRange, onValue: (Int) -> Unit, modifier: Modifier, description: String) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val n = text.toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(5)
            text = digits
            val v = digits.toIntOrNull()
            if (v != null && v in range && v != value) onValue(v)
        },
        modifier = modifier.padding(horizontal = 4.dp),
        singleLine = true,
        isError = n == null || n !in range,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

// ---- Met data (Generation 2) ------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetTab(p: PokemonSummary, model: SaveEditor, apply: (((GbPokemon) -> Unit)) -> Unit) {
    val c = p.caughtData
    if (c == null) {
        Text("This Pokemon has no caught data.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        if (c.raw == 0) "No caught data stored (for example a Pokemon from Gold/Silver, a trade, or an egg that has not hatched). Setting values here creates it."
        else "Caught data as Crystal stores it. Gold and Silver keep it but never use it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SectionHeader("Time of day")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Unknown", "Morning", "Day", "Night").forEachIndexed { i, label ->
            FilterChip(selected = c.timeOfDay == i, onClick = { apply { PokemonEdits.setMetTimeOfDay(it, i) } }, label = { Text(label) })
        }
    }
    FieldRow("Met level", hint = "0 to 63") {
        StepperField(c.metLevel, 0..63, { v -> apply { PokemonEdits.setMetLevel(it, v) } }, description = "met level", fieldWidth = 72.dp)
    }
    var pickLocation by remember { mutableStateOf(false) }
    ClickRow(
        "Met location",
        if (c.metLocation == 0) "None"
        else model.strings.locationName(c.metLocation) + model.englishStrings.locationName(c.metLocation).let { if (it != model.strings.locationName(c.metLocation)) " ($it)" else "" },
    ) { pickLocation = true }
    if (pickLocation) {
        val entries = remember(model) { Pickers.locations(model.language) }
        PickerSheet(
            title = "Met location",
            entries = entries,
            selectedId = c.metLocation,
            onDismiss = { pickLocation = false },
            noneLabel = "No location",
            onNone = { pickLocation = false; apply { PokemonEdits.setMetLocation(it, 0) } },
            onPick = { e -> pickLocation = false; apply { PokemonEdits.setMetLocation(it, e.id) } },
        )
    }
    SectionHeader("Original trainer gender")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Boy", "Girl").forEachIndexed { i, label ->
            FilterChip(selected = c.originalTrainerGender == i, onClick = { apply { PokemonEdits.setOriginalTrainerGender(it, i) } }, label = { Text(label) })
        }
    }
    if (p.pokerusStrain != 0 || p.pokerusDays != 0) {
        Text("Pokerus: strain ${p.pokerusStrain}, ${p.pokerusDays} days left", style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.width(1.dp))
}

// ---- small shared dialog --------------------------------------------------------------------------------------------

@Composable
fun TextInputDialog(title: String, initial: String, hint: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(20) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(hint) },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

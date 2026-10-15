package org.noxeng.coincell.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.editor.OriginGames
import org.noxeng.coincell.editor.PickerEntry
import org.noxeng.coincell.editor.Pickers
import org.noxeng.coincell.editor.PokemonEdits
import org.noxeng.coincell.editor.PokemonSaveInfo
import org.noxeng.coincell.editor.SaveEditor
import org.noxeng.coincell.editor.Stat3
import org.noxeng.coincell.editor.StatLabels
import org.noxeng.coincell.editor.TypeNames
import org.noxeng.coincell.pkcore.EditablePokemon
import org.noxeng.coincell.pkcore.Gender
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.pkcore.data.Gen3Util
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.ui.components.NeutralChip
import org.noxeng.coincell.ui.components.SectionHeader

// Generation 3 parts of the Pokemon screen: what comes from the PID (nature, gender, shininess, Unown's letter),
// the ability slot, IVs / EVs, and the met data.

private const val UNOWN = 201
private val UNOWN_LETTERS = ('A'..'Z').map { it.toString() } + listOf("!", "?")

/** Label of the stat a nature raises / lowers, by index 1..5 of (HP, Atk, Def, Spe, SpA, SpD). */
private fun statShort(index: Int, language: Int): String = when (index) {
    1 -> StatLabels.attack(language)
    2 -> StatLabels.defense(language)
    3 -> StatLabels.speed(language)
    4 -> StatLabels.spAtk(language)
    else -> StatLabels.spDef(language)
}

private fun natureEffect(nature: Int, language: Int): String =
    Gen3Util.natureModification(nature)?.let { (up, down) -> "+${statShort(up, language)}  -${statShort(down, language)}" } ?: "Neutral"

/** Rows of the Summary tab after the egg switch: nature, ability, gender, shininess, Unown letter, language. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Gen3SummaryRows(p: PokemonSummary, model: SaveEditor, apply: ((EditablePokemon) -> Unit) -> Unit) {
    val g = p.gen3 ?: return
    val lang = model.language
    var pickNature by remember { mutableStateOf(false) }
    var pickLetter by remember { mutableStateOf(false) }

    val enNature = model.englishStrings.natureName(g.nature)
    ClickRow(
        "Nature",
        g.natureName + (if (enNature != g.natureName) " ($enNature)" else "") + " · " + natureEffect(g.nature, lang) +
            "\nComes from the PID: changing it picks a new PID that keeps the gender, shininess and Unown letter.",
    ) { pickNature = true }

    SectionHeader("Ability")
    if (g.hasSecondAbility) {
        val personal = PersonalTable.RS[p.species]
        val names = listOf(personal.ability1, personal.ability2).map { model.strings.abilityName(it) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            names.forEachIndexed { i, name ->
                SegmentedButton(
                    selected = g.abilitySlot == i,
                    onClick = { apply { PokemonEdits.setAbilitySlot(it, i == 1) } },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(name, maxLines = 1) }
            }
        }
    } else {
        Text("${g.abilityName} (the species' only ability)", style = MaterialTheme.typography.bodyMedium)
    }

    SectionHeader("Gender")
    val ratio = PersonalTable.RS[p.species].gender
    if (ratio in 1..253) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Gender.MALE to "Male", Gender.FEMALE to "Female").forEach { (gender, label) ->
                FilterChip(selected = p.gender == gender, onClick = { apply { PokemonEdits.setGender(it, gender) } }, label = { Text(label) })
            }
        }
        Text("Comes from the PID: changing it picks a new PID that keeps the nature and shininess.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val name = if (p.gender == Gender.FEMALE) "Female" else "Male"
        Text(
            when {
                p.gender == Gender.GENDERLESS -> "Genderless"
                ratio !in 1..253 -> "$name (the species has a single gender)"
                else -> name
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (p.species == UNOWN) {
        ClickRow("Unown letter", UNOWN_LETTERS.getOrElse(p.form) { "?" } + " (from the PID)") { pickLetter = true }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(if (p.isShiny) "Shiny: yes" else "Shiny: no", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Shininess is not stored: it comes from the PID and the original trainer's IDs. \"Make shiny\" (menu) picks a new PID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (p.isShiny) NeutralChip("Shiny", Icons.Filled.Star)
    }
    Text(
        "Language: ${PokemonSaveInfo.languageName(g.language)}" + (if (p.isEgg) " (eggs are tagged Japanese until they hatch)" else "") +
            "  ·  PID %08X".format(g.pid),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )

    if (pickNature) {
        val entries = remember(model) { Pickers.natures(lang).map { it.copy(primary = it.primary + "  ·  " + natureEffect(it.id, lang)) } }
        PickerSheet(
            title = "Nature",
            entries = entries,
            selectedId = g.nature,
            onDismiss = { pickNature = false },
            onPick = { e -> pickNature = false; apply { PokemonEdits.setNature(it, e.id) } },
        )
    }
    if (pickLetter) {
        val entries = remember { UNOWN_LETTERS.mapIndexed { i, l -> PickerEntry(i, l, null, l.lowercase()) } }
        PickerSheet(
            title = "Unown letter",
            entries = entries,
            selectedId = p.form,
            onDismiss = { pickLetter = false },
            onPick = { e -> pickLetter = false; apply { PokemonEdits.setUnownForm(it, e.id) } },
        )
    }
}

// ---- Stats ----------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsTab3(p: PokemonSummary, model: SaveEditor, apply: ((EditablePokemon) -> Unit) -> Unit) {
    val g = p.gen3 ?: return
    val lang = model.language
    val ivs = g.ivs.toList()
    val evs = g.evs.toList()
    val totals = p.stats.toList()
    val labels = listOf(StatLabels.hp(lang), StatLabels.attack(lang), StatLabels.defense(lang), StatLabels.speed(lang), StatLabels.spAtk(lang), StatLabels.spDef(lang))
    val evTotal = g.evs.total
    val mod = Gen3Util.natureModification(g.nature)

    Text(
        "IVs (0-31) and EVs (0-255 each, ${PokemonEdits.MAX_EV_TOTAL} in total). The totals follow your edits and the nature.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth()) {
        Text("", Modifier.weight(1.3f))
        Text("IV", Modifier.width(72.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text("EV", Modifier.width(88.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        Text("Total", Modifier.weight(0.7f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
    }
    Stat3.entries.forEach { stat ->
        val i = stat.ordinal
        val arrow = when {
            mod?.first == i -> " ▲"
            mod?.second == i -> " ▼"
            else -> ""
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(labels[i] + arrow, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            CompactNumberField(ivs[i], 0..PokemonEdits.MAX_IV, { v -> apply { PokemonEdits.setIv(it, stat, v) } }, Modifier.width(72.dp), "${labels[i]} IV")
            CompactNumberField(evs[i], 0..PokemonEdits.MAX_EV, { v -> apply { PokemonEdits.setEv(it, stat, v) } }, Modifier.width(88.dp), "${labels[i]} EV")
            Text(totals[i].toString(), Modifier.weight(0.7f), textAlign = TextAlign.End, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
    }
    Text(
        "EVs used: $evTotal / ${PokemonEdits.MAX_EV_TOTAL}",
        style = MaterialTheme.typography.bodyMedium,
        color = if (evTotal > PokemonEdits.MAX_EV_TOTAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { apply { pk -> Stat3.entries.forEach { PokemonEdits.setIv(pk, it, PokemonEdits.MAX_IV) } } }) { Text("Perfect IVs") }
        OutlinedButton(onClick = { apply { pk -> Stat3.entries.forEach { PokemonEdits.setEv(pk, it, 0) } } }) { Text("Clear EVs") }
    }
    SectionHeader("Derived")
    Text("Nature: ${g.natureName} (${natureEffect(g.nature, lang)})", style = MaterialTheme.typography.bodyMedium)
    Text("Hidden Power type: ${TypeNames.hiddenPower(p.hiddenPowerType, lang)}", style = MaterialTheme.typography.bodyMedium)
}

// ---- Met data -------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetTab3(p: PokemonSummary, model: SaveEditor, apply: ((EditablePokemon) -> Unit) -> Unit) {
    val g = p.gen3 ?: return
    var pickLocation by remember { mutableStateOf(false) }
    var pickBall by remember { mutableStateOf(false) }

    val enLocation = model.englishStrings.locationName3(g.metLocation)
    ClickRow("Met location", g.metLocationName + if (enLocation != g.metLocationName) " ($enLocation)" else "") { pickLocation = true }
    FieldRow("Met level", hint = "0 to 100; 0 for a hatched egg") {
        StepperField(g.metLevel, 0..100, { v -> apply { PokemonEdits.setMetLevel(it, v) } }, description = "met level", fieldWidth = 72.dp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ClickRow("Ball", model.strings.itemName(3, g.ball)) { pickBall = true }
        }
        ItemIcon(3, g.ball)
    }
    SectionHeader("Game of origin")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PokemonEdits.ORIGIN_GAMES.forEach { game ->
            FilterChip(selected = g.originGame == game, onClick = { apply { PokemonEdits.setOriginGame(it, game) } }, label = { Text(OriginGames.name(game)) })
        }
    }
    SectionHeader("Original trainer gender")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Boy", "Girl").forEachIndexed { i, label ->
            FilterChip(selected = g.otGender == i, onClick = { apply { PokemonEdits.setOriginalTrainerGender(it, i) } }, label = { Text(label) })
        }
    }
    val extras = buildList {
        if (g.fatefulEncounter) add("Fateful encounter (event Pokemon)")
        if (g.ribbonCount > 0) add("${g.ribbonCount} ribbon${if (g.ribbonCount > 1) "s" else ""}")
        if (p.pokerusStrain != 0 || p.pokerusDays != 0) add("Pokerus: strain ${p.pokerusStrain}, ${p.pokerusDays} days left")
        val c = g.contest
        if (listOf(c.cool, c.beauty, c.cute, c.smart, c.tough, c.sheen).any { it != 0 }) {
            add("Contest: cool ${c.cool}, beauty ${c.beauty}, cute ${c.cute}, smart ${c.smart}, tough ${c.tough}, sheen ${c.sheen}")
        }
        if (g.isBadEgg) add("This data failed its checksum when it was read (the game shows a Bad Egg).")
    }
    extras.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }

    if (pickLocation) {
        val entries = remember(model) { Pickers.locations3(model.language) }
        PickerSheet(
            title = "Met location",
            entries = entries,
            selectedId = g.metLocation,
            onDismiss = { pickLocation = false },
            onPick = { e -> pickLocation = false; apply { PokemonEdits.setMetLocation(it, e.id) } },
        )
    }
    if (pickBall) {
        val entries = remember(model) { Pickers.balls(model.language) }
        PickerSheet(
            title = "Ball",
            entries = entries,
            selectedId = g.ball,
            onDismiss = { pickBall = false },
            onPick = { e -> pickBall = false; apply { PokemonEdits.setBall(it, e.id) } },
            leading = { e -> ItemIcon(3, e.id) },
        )
    }
}

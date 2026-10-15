package org.noxeng.coincell.editor

import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.data.ItemInfo
import org.noxeng.coincell.pkcore.data.MoveInfo
import org.noxeng.coincell.pkcore.text.LanguageId
import java.text.Normalizer

/**
 * One row of a searchable picker: the name in the save's language, the English name as secondary text when it
 * differs, and the id. [search] holds both names in normalised form.
 */
data class PickerEntry(val id: Int, val primary: String, val secondary: String?, val search: String)

/** Picker lists and their search; pure and unit tested (French and English). */
object Pickers {
    /** Lower case, accents removed, only letters and digits ("Pokéball" and "pokeball" find each other). */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    private fun entry(id: Int, primary: String, english: String): PickerEntry =
        PickerEntry(
            id,
            primary,
            english.takeIf { it != primary && it.isNotBlank() },
            normalize(primary) + "|" + normalize(english),
        )

    fun species(generation: Int, language: Int): List<PickerEntry> {
        val local = GameStrings.forLanguage(language)
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        return (1..PokemonEdits.maxSpecies(generation)).map { entry(it, local.speciesName(it), en.speciesName(it)) }
    }

    fun moves(generation: Int, language: Int): List<PickerEntry> {
        val local = GameStrings.forLanguage(language)
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        return (1..PokemonEdits.maxMove(generation))
            .filter { MoveInfo.getBasePp(generation, it) > 0 }
            .map { entry(it, local.moveName(it), en.moveName(it)) }
    }

    /** Placeholder names of unused item slots ("Teru-sama (0)", "?????", "---"). */
    private fun isRealItemName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("Teru-sama") && !name.startsWith("?") && !name.startsWith("―") && !name.startsWith("-") &&
            !name.startsWith("Item #") && name != "(None)"

    /** The given item ids of [generation] (a pocket's valid items), by name. */
    fun items(generation: Int, ids: Collection<Int>, language: Int): List<PickerEntry> {
        val local = GameStrings.forLanguage(language)
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        val maxId = if (generation == 3) MAX_ITEM_3 else 255
        return ids.filter { it in 1..maxId }
            .filter { isRealItemName(en.itemName(generation, it)) }
            .map { entry(it, local.itemName(generation, it), en.itemName(generation, it)) }
            .sortedWith(compareBy({ normalize(it.primary) }, { it.id }))
    }

    /** Every item a Pokemon of [generation] (2 or 3) can hold. */
    fun heldItems(generation: Int, language: Int): List<PickerEntry> =
        if (generation == 3) items(3, ItemInfo.heldItems3, language) else items(2, (1..255).toList(), language)

    /** Generation 3 met locations with a name, in id order (0 is Littleroot Town, so it is listed). */
    fun locations3(language: Int): List<PickerEntry> {
        val local = GameStrings.forLanguage(language)
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        return (0..255).filter { en.locationsGen3.getOrElse(it) { "" }.isNotBlank() }
            .map { entry(it, local.locationName3(it), en.locationName3(it)) }
    }

    /** The 25 natures, 0 = Hardy ... 24 = Quirky. */
    fun natures(language: Int): List<PickerEntry> {
        val local = GameStrings.forLanguage(language)
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        return (0..24).map { entry(it, local.natureName(it), en.natureName(it)) }
    }

    /** The 12 Generation 3 balls (ball ids are the item ids 1..12). */
    fun balls(language: Int): List<PickerEntry> {
        val local = GameStrings.forLanguage(language)
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        return (1..PokemonEdits.BALL_COUNT).map { entry(it, local.itemName(3, it), en.itemName(3, it)) }
    }

    private const val MAX_ITEM_3 = 376

    /** Generation 2 met locations (unused ids left out), in game order. Location 0 ("none") is not listed. */
    fun locations(language: Int): List<PickerEntry> {
        val local = GameStrings.forLanguage(language)
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        return (1..127).filter { en.locationsGen2.getOrElse(it) { "" }.isNotBlank() }
            .map { entry(it, local.locationName(it), en.locationName(it)) }
    }

    /**
     * Entries matching [query] in either language (accents and case ignored), best first: exact id, exact name,
     * name starts with the query, then anywhere in the name. An empty query returns [entries] unchanged.
     */
    fun search(entries: List<PickerEntry>, query: String): List<PickerEntry> {
        val q = normalize(query)
        if (q.isEmpty()) return entries
        val byId = query.trim().toIntOrNull()
        val scored = ArrayList<Pair<Int, PickerEntry>>()
        for (e in entries) {
            val names = e.search.split('|')
            val score = when {
                byId != null && e.id == byId -> 0
                names.any { it == q } -> 1
                names.any { it.startsWith(q) } -> 2
                names.any { it.contains(q) } -> 3
                else -> continue
            }
            scored += score to e
        }
        return scored.sortedWith(compareBy({ it.first }, { it.second.id })).map { it.second }
    }
}

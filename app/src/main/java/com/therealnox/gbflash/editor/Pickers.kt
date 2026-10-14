package com.therealnox.gbflash.editor

import com.therealnox.gbflash.pkcore.data.GameStrings
import com.therealnox.gbflash.pkcore.data.MoveInfo
import com.therealnox.gbflash.pkcore.text.LanguageId
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
        return ids.filter { it in 1..255 }
            .filter { isRealItemName(en.itemName(generation, it)) }
            .map { entry(it, local.itemName(generation, it), en.itemName(generation, it)) }
            .sortedWith(compareBy({ normalize(it.primary) }, { it.id }))
    }

    /** Every item a Generation 2 Pokemon can hold. */
    fun heldItems(language: Int): List<PickerEntry> = items(2, (1..255).toList(), language)

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

// Ported from PKHeX.Core/Game/GameStrings (names), PKM/Util/SpeciesName.cs, PKM/Strings/StringConverter4Util.cs
// (StripDiacriticsFR4) (PKHeX, GPL-3.0, commit 641c26b). Data: Resources/text/{other,items}/*.
package com.therealnox.gbflash.pkcore.data

import com.therealnox.gbflash.pkcore.text.LanguageId
import com.therealnox.gbflash.pkcore.text.StringConverter1

/** Localised species, move and item names for one language. */
public class GameStrings private constructor(public val language: Int, private val code: String) {
    /** Index 0 is the egg name, then species 1..251 by National Dex id. */
    public val species: List<String> by lazy { Resources.lines("species_$code.txt") }

    /** Move names by id (0 = none), up to move 251. */
    public val moves: List<String> by lazy { Resources.lines("moves_$code.txt") }

    /** Generation 1 item names by item id (0..255). */
    public val itemsGen1: List<String> by lazy { Resources.lines("items_g1_$code.txt") }

    /** Generation 2 item names by item id (0..255). */
    public val itemsGen2: List<String> by lazy { Resources.lines("items_g2_$code.txt") }

    public fun speciesName(species: Int): String = this.species.getOrElse(species) { "" }
    public fun moveName(move: Int): String = moves.getOrElse(move) { "" }
    public fun itemName(generation: Int, item: Int): String =
        (if (generation == 1) itemsGen1 else itemsGen2).getOrElse(item) { "" }.ifEmpty { "Item #$item" }

    public companion object {
        /** Languages with packaged name data, in PKHeX's `Languages` order (matters for ties). */
        public val supportedLanguages: List<Int> = listOf(
            LanguageId.JAPANESE, LanguageId.ENGLISH, LanguageId.FRENCH,
            LanguageId.ITALIAN, LanguageId.GERMAN, LanguageId.SPANISH,
        )

        private val cache = HashMap<Int, GameStrings>()

        private fun codeOf(language: Int): String = when (language) {
            LanguageId.JAPANESE -> "ja"
            LanguageId.FRENCH -> "fr"
            LanguageId.ITALIAN -> "it"
            LanguageId.GERMAN -> "de"
            LanguageId.SPANISH -> "es"
            else -> "en"
        }

        /** Strings for [language]; unknown languages (including Korean) fall back to English. */
        @Synchronized
        public fun forLanguage(language: Int): GameStrings {
            val effective = if (language in supportedLanguages) language else LanguageId.ENGLISH
            return cache.getOrPut(effective) { GameStrings(effective, codeOf(effective)) }
        }
    }
}

/** Generation-aware default species names ("nicknames") and nickname-language detection. */
public object SpeciesName {
    private const val FRENCH_EGG_GEN2 = "OEUF"

    /**
     * The species name as the Generation 1/2 games store it as a default nickname (all caps, no spaces,
     * `MR.MIME` uses a special period). [species] 0 gives the egg name.
     */
    public fun getGeneration12(species: Int, language: Int): String {
        if (species == 0) return getEggName12(language)
        val nick = GameStrings.forLanguage(language).speciesName(species)
        if (language == LanguageId.JAPANESE) return nick // No further processing

        // All names are uppercase. Char-wise, like .NET's ToUpperInvariant (e.g. ß stays ß).
        val result = StringBuilder(nick.length)
        for (c in nick) result.append(Character.toUpperCase(c))
        if (language == LanguageId.FRENCH) stripDiacriticsFr(result)

        // Gen1/2 species names do not have spaces; the only one with a space is Mr. Mime.
        if (species == MR_MIME) {
            val indexSpace = result.indexOf(" ")
            if (indexSpace > 0) {
                result.setCharAt(indexSpace - 1, StringConverter1.DOT)
                result.deleteCharAt(indexSpace)
            }
        }
        return result.toString()
    }

    private fun getEggName12(language: Int): String {
        if (language == LanguageId.FRENCH) return FRENCH_EGG_GEN2
        return GameStrings.forLanguage(language).speciesName(0).let { name ->
            val sb = StringBuilder(name.length)
            for (c in name) sb.append(Character.toUpperCase(c))
            sb.toString()
        }
    }

    /** Strips accents on E and I: È É Ê -> E, Ï -> I (PKHeX `StringConverter4Util.StripDiacriticsFR4`). */
    private fun stripDiacriticsFr(input: StringBuilder) {
        for (i in input.indices) {
            val delta = input[i].code - 'È'.code
            if (delta !in 0..7) continue
            if (delta < 3) input.setCharAt(i, 'E') else if (delta == 7) input.setCharAt(i, 'I')
        }
    }

    /**
     * Language whose default species name equals [nickname], or -1.
     * Languages are tried in PKHeX order (ja, en, fr, it, de, es), so names shared by several languages resolve to the first.
     */
    public fun getSpeciesNameLanguage(species: Int, nickname: String): Int {
        for (lang in GameStrings.supportedLanguages) {
            if (nickname == getGeneration12(species, lang)) return lang
        }
        return -1
    }

    private const val MR_MIME = 122
}

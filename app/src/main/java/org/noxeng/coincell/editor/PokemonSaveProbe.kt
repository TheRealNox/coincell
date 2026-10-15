package org.noxeng.coincell.editor

import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.Region
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.text.LanguageId

/** What the Pokemon editor can say about one library save. */
sealed interface PokemonSaveKind {
    /** A Generation 1, 2 or 3 Pokemon save that `:pkcore` reads. */
    data class Editable(val info: PokemonSaveInfo) : PokemonSaveKind

    /** Not a Pokemon save the editor can open (never an error). */
    data object NotSupported : PokemonSaveKind
}

/** Identity of a Pokemon save as detected by `:pkcore`. */
data class PokemonSaveInfo(val game: Game, val region: Region, val language: Int) {
    val generation: Int get() = game.generation

    /** "Pokemon save · Gen 2 · Crystal French" (the chip on Game detail and Backup detail). */
    val chipText: String get() = "Pokemon save · Gen $generation · ${gameName(game)} ${languageName(language)}"

    companion object {
        fun gameName(game: Game): String = when (game) {
            Game.RED_BLUE -> "Red/Blue"
            Game.YELLOW -> "Yellow"
            Game.GOLD_SILVER -> "Gold/Silver"
            Game.CRYSTAL -> "Crystal"
            Game.RUBY_SAPPHIRE -> "Ruby/Sapphire"
            Game.EMERALD -> "Emerald"
            Game.FIRERED_LEAFGREEN -> "FireRed/LeafGreen"
        }

        fun languageName(language: Int): String = when (language) {
            LanguageId.JAPANESE -> "Japanese"
            LanguageId.FRENCH -> "French"
            LanguageId.ITALIAN -> "Italian"
            LanguageId.GERMAN -> "German"
            LanguageId.SPANISH -> "Spanish"
            LanguageId.KOREAN -> "Korean"
            else -> "English"
        }
    }
}

/** Detects whether a library save opens in the editor. Pure, so it is unit tested. */
object PokemonSaveProbe {
    /** Game codes of the five Generation 3 games: Ruby, Sapphire, FireRed, LeafGreen, Emerald. */
    private val GEN3_PREFIXES = listOf("AXV", "AXP", "BPR", "BPG", "BPE")

    private fun isGen3Code(code: String?): Boolean = GEN3_PREFIXES.any { code?.uppercase()?.startsWith(it) == true }

    /**
     * The answer that needs no save bytes: a GBA cartridge whose game code is not one of the Generation 3 Pokemon games
     * is [PokemonSaveKind.NotSupported]; otherwise (Pokemon cartridges, GB / GBC, files without a game code) the bytes
     * decide (null here).
     */
    fun byGame(game: GameInfo): PokemonSaveKind? = when {
        game.platform == "AGB" && game.gameCode != null && !isGen3Code(game.gameCode) -> PokemonSaveKind.NotSupported
        else -> null
    }

    /**
     * The text language a GBA cartridge's game code names (its last letter: J, E, F, D, S, I), or null. Generation 3
     * saves don't store their language, so this beats guessing from the Pokemon. GB / GBC keep `:pkcore`'s guess.
     */
    fun languageHint(game: GameInfo?): Int? {
        val code = game?.gameCode?.uppercase() ?: return null
        if (game.platform != "AGB" || !isGen3Code(code) || code.length < 4) return null
        return when (code[3]) {
            'J' -> LanguageId.JAPANESE
            'E' -> LanguageId.ENGLISH
            'F' -> LanguageId.FRENCH
            'D' -> LanguageId.GERMAN
            'S' -> LanguageId.SPANISH
            'I' -> LanguageId.ITALIAN
            else -> null
        }
    }

    /** Tries `SaveFile.load` on [bytes]; anything that goes wrong means "not a Pokemon save". */
    fun probe(game: GameInfo, bytes: ByteArray?): PokemonSaveKind {
        byGame(game)?.let { return it }
        if (bytes == null) return PokemonSaveKind.NotSupported
        return probeBytes(bytes, languageHint(game))
    }

    fun probeBytes(bytes: ByteArray, language: Int? = null): PokemonSaveKind = try {
        val save = SaveFile.load(bytes, language)
        PokemonSaveKind.Editable(PokemonSaveInfo(save.game, save.region, save.language))
    } catch (_: Exception) {
        PokemonSaveKind.NotSupported
    }
}

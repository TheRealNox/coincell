package com.therealnox.gbflash.editor

import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.pkcore.Game
import com.therealnox.gbflash.pkcore.Region
import com.therealnox.gbflash.pkcore.SaveFile
import com.therealnox.gbflash.pkcore.text.LanguageId

/** What the Pokemon editor can say about one library save. */
sealed interface PokemonSaveKind {
    /** A Generation 1 or 2 Pokemon save that `:pkcore` reads. */
    data class Gen12(val info: PokemonSaveInfo) : PokemonSaveKind

    /** A Generation 3 (GBA) Pokemon game: recognised, but there is no editor for it yet. */
    data object GbaLater : PokemonSaveKind

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

    /**
     * The answer that needs no save bytes: a GBA Pokemon game is [PokemonSaveKind.GbaLater], any other GBA game
     * [PokemonSaveKind.NotSupported]; for GB / GBC the bytes decide (null here).
     */
    fun byGame(game: GameInfo): PokemonSaveKind? = when {
        game.platform != "AGB" -> null
        GEN3_PREFIXES.any { game.gameCode?.uppercase()?.startsWith(it) == true } -> PokemonSaveKind.GbaLater
        else -> PokemonSaveKind.NotSupported
    }

    /** Tries `SaveFile.load` on [bytes]; anything that goes wrong means "not a Pokemon save". */
    fun probe(game: GameInfo, bytes: ByteArray?): PokemonSaveKind {
        byGame(game)?.let { return it }
        if (bytes == null) return PokemonSaveKind.NotSupported
        return probeBytes(bytes)
    }

    fun probeBytes(bytes: ByteArray): PokemonSaveKind = try {
        val save = SaveFile.load(bytes)
        PokemonSaveKind.Gen12(PokemonSaveInfo(save.game, save.region, save.language))
    } catch (_: Exception) {
        PokemonSaveKind.NotSupported
    }
}

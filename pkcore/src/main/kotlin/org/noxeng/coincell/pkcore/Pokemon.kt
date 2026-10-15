// Original API types for :pkcore (not a PKHeX port). Immutable UI-facing snapshots of a Pokemon.
package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.data.GameStrings

/** Gender of a Pokemon, derived from the species ratio and the Attack DV in Generations 1/2. */
public enum class Gender { MALE, FEMALE, GENDERLESS }

/** One of the four move slots. [id] 0 means the slot is empty. */
public data class MoveSlot(
    val id: Int,
    val name: String,
    val pp: Int,
    val ppUps: Int,
    /** PP when fully restored with the current PP Ups. */
    val maxPp: Int,
)

/** Battle stats (Special is shared in Gen 1, so [specialAttack] == [specialDefense] there). */
public data class StatBlock(
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val speed: Int,
    val specialAttack: Int,
    val specialDefense: Int,
)

/** Determinant values ("DVs", 0..15) of Generations 1/2. HP is derived from the other four. */
public data class Dvs(val attack: Int, val defense: Int, val speed: Int, val special: Int) {
    val hp: Int get() = ((attack and 1) shl 3) or ((defense and 1) shl 2) or ((speed and 1) shl 1) or (special and 1)
}

/** Stat experience (0..65535), the Gen 1/2 equivalent of EVs. */
public data class StatExp(val hp: Int, val attack: Int, val defense: Int, val speed: Int, val special: Int)

/** Crystal-only "caught data" (also stored, unused, by Gold/Silver). */
public data class CaughtData(
    val raw: Int,
    /** 0 = unknown, 1 = morning, 2 = day, 3 = night. */
    val timeOfDay: Int,
    val metLevel: Int,
    /** Original trainer gender bit: 0 = boy, 1 = girl. */
    val originalTrainerGender: Int,
    val metLocation: Int,
)

/** Immutable snapshot of a stored Pokemon, ready for display. Built by [Pokemon.summary]. */
public data class PokemonSummary(
    val generation: Int,
    val species: Int,
    val speciesName: String,
    val nickname: String,
    val isNicknamed: Boolean,
    val isEgg: Boolean,
    /** Level derived from experience (what PKHeX shows). */
    val level: Int,
    val exp: Int,
    /** Level byte stored in the data (party format / box format). */
    val storedLevel: Int,
    val otName: String,
    val trainerId: Int,
    val moves: List<MoveSlot>,
    val dvs: Dvs,
    val statExp: StatExp,
    /** Stats as PKHeX computes them from base stats, DVs, stat experience and level. */
    val stats: StatBlock,
    /** Stats stored in the party data (all 0 when the Pokemon comes from a box). */
    val storedStats: StatBlock,
    val currentHp: Int,
    val status: Int,
    /** Gen 2 held item id (0 for none / Gen 1). */
    val heldItem: Int,
    val heldItemName: String,
    /** Gen 2 friendship, null in Gen 1. */
    val friendship: Int?,
    val pokerusStrain: Int,
    val pokerusDays: Int,
    /** Gen 2 only. */
    val caughtData: CaughtData?,
    /** Gen 2 only (Gen 1 has no shininess). */
    val isShiny: Boolean,
    val gender: Gender,
    val hiddenPowerType: Int,
)

/**
 * A Pokemon of any generation. Concrete mutable types ([org.noxeng.coincell.pkcore.gb.Pk1],
 * [org.noxeng.coincell.pkcore.gb.Pk2]) expose the editable fields; save files hand out copies,
 * so changes only reach the save through the `set...`/`add...` operations of [SaveFile].
 */
public interface Pokemon {
    public val generation: Int
    public val species: Int
    public val isEgg: Boolean

    /** Independent copy. */
    public fun duplicate(): Pokemon

    /** Immutable display snapshot with names in the language of [strings]. */
    public fun summary(strings: GameStrings = GameStrings.forLanguage(org.noxeng.coincell.pkcore.text.LanguageId.ENGLISH)): PokemonSummary
}

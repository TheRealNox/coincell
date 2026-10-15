// Original API types for :pkcore (not a PKHeX port). Immutable UI-facing snapshots of a Pokemon.
package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.data.GameStrings

/** Gender of a Pokemon, derived from the species ratio and the Attack DV (Generations 1/2) or the PID (Generation 3). */
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

/**
 * Six values in HP, Attack, Defense, Speed, Sp. Atk, Sp. Def order: battle stats (Special is shared in Gen 1, so
 * [specialAttack] == [specialDefense] there), and in Generation 3 also IVs and EVs.
 */
public data class StatBlock(
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val speed: Int,
    val specialAttack: Int,
    val specialDefense: Int,
) {
    /** The six values in HP, Atk, Def, Spe, SpA, SpD order. */
    public fun toList(): List<Int> = listOf(hp, attack, defense, speed, specialAttack, specialDefense)

    public val total: Int get() = hp + attack + defense + speed + specialAttack + specialDefense

    public companion object {
        public fun of(values: List<Int>): StatBlock = StatBlock(values[0], values[1], values[2], values[3], values[4], values[5])
    }
}

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

/** Generation 3 contest condition. */
public data class ContestStats(val cool: Int, val beauty: Int, val cute: Int, val smart: Int, val tough: Int, val sheen: Int)

/** What only Generation 3 Pokemon have, part of [PokemonSummary.gen3]. */
public data class Gen3Details(
    /** Personality value (32 bit, unsigned): decides nature, gender, shininess and Unown's letter. */
    val pid: Long,
    val secretId: Int,
    /** 0..31 each. */
    val ivs: StatBlock,
    /** 0..255 each, 510 in total in the games. */
    val evs: StatBlock,
    val nature: Int,
    val natureName: String,
    /** 0 = first ability, 1 = second (only meaningful when [hasSecondAbility]). */
    val abilitySlot: Int,
    val ability: Int,
    val abilityName: String,
    val hasSecondAbility: Boolean,
    /** Ball id 1..12 (Master Ball .. Premier Ball). */
    val ball: Int,
    val metLocation: Int,
    val metLocationName: String,
    /** 0 for a hatched egg. */
    val metLevel: Int,
    /** Game of origin (1 Sapphire, 2 Ruby, 3 Emerald, 4 FireRed, 5 LeafGreen, 15 Colosseum/XD). */
    val originGame: Int,
    /** 0 = male, 1 = female. */
    val otGender: Int,
    /** The Pokemon's own language byte. */
    val language: Int,
    val markings: Int,
    val ribbonCount: Int,
    val fatefulEncounter: Boolean,
    val contest: ContestStats,
    /** The data failed its checksum when read (the games show a "Bad Egg"). */
    val isBadEgg: Boolean,
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
    /** Generations 1/2 only. */
    val dvs: Dvs?,
    /** Generations 1/2 only. */
    val statExp: StatExp?,
    /** Stats as PKHeX computes them from base stats, DVs, stat experience and level. */
    val stats: StatBlock,
    /** Stats stored in the party data (all 0 when the Pokemon comes from a box; Gen 3 box data has none either). */
    val storedStats: StatBlock,
    val currentHp: Int,
    val status: Int,
    /** Gen 2/3 held item id (0 for none / Gen 1). */
    val heldItem: Int,
    val heldItemName: String,
    /** Gen 2/3 friendship, null in Gen 1. */
    val friendship: Int?,
    val pokerusStrain: Int,
    val pokerusDays: Int,
    /** Gen 2 only. */
    val caughtData: CaughtData?,
    /** Gen 2/3 (Gen 1 has no shininess). */
    val isShiny: Boolean,
    val gender: Gender,
    val hiddenPowerType: Int,
    /** Unown's letter (0 = A; Gen 3 adds 26 = ! and 27 = ?), 0 for every other species. */
    val form: Int = 0,
    /** Generation 3 only. */
    val gen3: Gen3Details? = null,
)

/**
 * A Pokemon of any generation. Concrete mutable types ([org.noxeng.coincell.pkcore.gb.Pk1],
 * [org.noxeng.coincell.pkcore.gb.Pk2], [org.noxeng.coincell.pkcore.gba.Pk3]) expose the editable fields; save files
 * hand out copies, so changes only reach the save through the `set...`/`add...` operations of [SaveFile].
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

/**
 * The editable fields every generation shares, so editing code can treat Pk1, Pk2 and Pk3 alike. Generation-specific
 * fields stay on the concrete types.
 */
public interface EditablePokemon : Pokemon {
    override var species: Int

    /** Experience points. */
    public var exp: Int

    /** Level derived from [exp]; setting it sets [exp] to the minimum of that level and the stored level. */
    public var level: Int

    /** Level byte stored in the party data. */
    public var storedLevel: Int

    /** Experience growth group of the species (index into the experience tables). */
    public val expGrowth: Int

    public var trainerId: Int
    public var nickname: String
    public var otName: String
    public val isNicknamed: Boolean
    public val isJapanese: Boolean
    public val maxNicknameLength: Int
    public val maxTrainerNameLength: Int

    /** Puts the species name back as the nickname, in the Pokemon's language. */
    public fun resetNickname()

    public fun getMove(index: Int): Int
    public fun setMove(index: Int, move: Int)
    public fun getMovePp(index: Int): Int
    public fun setMovePp(index: Int, pp: Int)
    public fun getMovePpUps(index: Int): Int
    public fun setMovePpUps(index: Int, ups: Int)

    /** PP of [move] with [ppUps] applied, by the rules of the Pokemon's generation. */
    public fun getMaxPp(move: Int, ppUps: Int): Int

    public var status: Int
    public var currentHp: Int
    public var maxHp: Int

    /** True once party stats are present, false for data that came from a box. */
    public val partyStatsPresent: Boolean

    /** Recomputes the party stats and level and clears the status (PKHeX `ResetPartyStats`). */
    public fun resetPartyStats()

    /** Restores the PP of every move. */
    public fun healPp()

    /** [resetPartyStats] followed by [healPp]. */
    public fun heal()
}

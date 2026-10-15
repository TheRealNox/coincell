// Ported from PKHeX.Core/PKM/Util/Conversion/SpeciesConverter.cs, PKM/Util/Experience.cs, Moves/MoveInfo*.cs,
// PKM/Util/Conversion/ItemConverter.cs, Editing/HiddenPower.cs, Legality/RNG/Util/ShinyUtil.cs, Editing/NatureAmp.cs,
// PKM/Util/EntityPID.cs (GetUnownForm3), PKM/Util/EntityGender.cs (PKHeX, GPL-3.0, commit 641c26b).
package org.noxeng.coincell.pkcore.data

import org.noxeng.coincell.pkcore.Gender

/** Generation 1 and 3 internal species ids <-> National Dex ids. */
public object SpeciesConverter {
    private const val FIRST_UNALIGNED_NATIONAL3 = 252
    private const val FIRST_UNALIGNED_INTERNAL3 = 277

    /** National Dex id -> Generation 3 internal species id. */
    public fun getInternal3(species: Int): Int {
        val shift = species - FIRST_UNALIGNED_NATIONAL3
        val table = Gen3Tables.nationalToInternalDelta
        if (shift !in table.indices) return species
        return species + table[shift]
    }

    /** Generation 3 internal species id -> National Dex id (0 for the unused ids 252..276 and anything past the table). */
    public fun getNational3(raw: Int): Int {
        if (raw < FIRST_UNALIGNED_NATIONAL3) return raw
        val shift = raw - FIRST_UNALIGNED_INTERNAL3
        val table = Gen3Tables.internalToNationalDelta
        if (shift !in table.indices) return 0
        return raw + table[shift]
    }

    /** National Dex id -> Generation 1 internal species id (0 when out of range). */
    public fun getInternal1(species: Int): Int =
        if (species !in Gen1Tables.nationalToInternal.indices) 0 else Gen1Tables.nationalToInternal[species]

    /** Generation 1 internal species id -> National Dex id. */
    public fun getNational1(raw: Int): Int = Gen1Tables.internalToNational[raw and 0xFF]
}

/** Experience / level calculations (PKHeX `Experience`). */
public object Experience {
    public const val MAX_LEVEL: Int = 100
    public const val MIN_LEVEL: Int = 1

    /** Gets the level for [exp] in the given growth-rate group. */
    public fun getLevel(exp: Long, growth: Int): Int {
        val table = ExperienceTables.growth[growth]
        // Eagerly return 100 if the exp is at max
        if (exp >= table[table.size - 1]) return MAX_LEVEL
        var level = MIN_LEVEL
        while (exp >= table[level]) level++
        return level
    }

    /** Minimum experience for [level]. */
    public fun getExp(level: Int, growth: Int): Long {
        if (level <= MIN_LEVEL) return 0
        val clamped = if (level > MAX_LEVEL) MAX_LEVEL else level
        return ExperienceTables.growth[growth][clamped - 1].toLong()
    }
}

/** Base move data. */
public object MoveInfo {
    /** Base PP (no PP Ups) of [move] in the given generation (1, 2 or 3); 0 when unknown. */
    public fun getBasePp(generation: Int, move: Int): Int {
        val table = when (generation) {
            1 -> MoveTables.ppGen1
            2 -> MoveTables.ppGen2
            else -> MoveTables.ppGen3
        }
        return if (move in table.indices) table[move] else 0
    }
}

/** Item helpers shared by Generation 1 and 2. */
public object ItemInfo {
    /** True if the Gen 1 catch-rate byte is also a valid Gen 2 held item id (PKHeX `IsCatchRateHeldItem`). */
    public fun isCatchRateHeldItem(rate: Int): Boolean {
        val bits = Gen1Tables.catchRateIsHeldItem
        return (bits[(rate shr 3) and 0x1F] shr (rate and 7)) and 1 != 0
    }

    public fun isHm1(item: Int): Boolean = item in 196..200
    public fun isHm2(item: Int): Boolean = item in 243..249

    /** PKHeX `ItemConverter.IsItemHM3`. */
    public fun isHm3(item: Int): Boolean = item in 339..346

    /** Mail items of Generation 3 (PKHeX `SAV3.IsMail`). */
    public fun isMail3(item: Int): Boolean = item in 121..132

    /**
     * Generation 3 item id -> the item id of Generation 4 and later (PKHeX `ItemConverter.GetItemFuture3`), used to
     * find artwork; 128 when there is none.
     */
    public fun getItemFuture3(item: Int): Int = if (item in Gen3Tables.item3to4.indices) Gen3Tables.item3to4[item] else NAN

    /** Items a Generation 3 Pokemon can hold (PKHeX `Legal.HeldItems_RS`: general items, balls, berries and TMs). */
    public val heldItems3: List<Int> by lazy {
        (Gen3Tables.general + Gen3Tables.balls + Gen3Tables.berries + Gen3Tables.machine.copyOf(TM_COUNT_3)).toList()
    }

    private const val TM_COUNT_3 = 50

    private const val NAN = 128
}

/** Shininess and Hidden Power for Gen 1/2 DVs. */
public object DvUtil {
    /** Gen 2 shininess from the 16-bit DV value. */
    public fun isShiny(dv16: Int): Boolean = (dv16 and 0x2FFF) == 0x2AAA

    /** Hidden Power type index (0 = Fighting ... 15 = Dark), from the 16-bit DV value. */
    public fun hiddenPowerType(dv16: Int): Int = ((dv16 shr 10) and 0b1100) or ((dv16 shr 8) and 0b11)
}

/** Generation 3 values derived from the PID and IVs. */
public object Gen3Util {
    /** Nature 0..24 from the PID (`PID % 25`). */
    public fun nature(pid: Long): Int = ((pid and 0xFFFFFFFFL) % 25).toInt()

    /** Shiny when `TID ^ SID ^ PID high ^ PID low` is below 8. */
    public fun isShiny(pid: Long, tid: Int, sid: Int): Boolean = shinyXor(pid, tid, sid) < 8

    /** `TID ^ SID ^ PID high ^ PID low` (shiny below 8). */
    public fun shinyXor(pid: Long, tid: Int, sid: Int): Int {
        val hi = ((pid ushr 16) and 0xFFFF).toInt()
        val lo = (pid and 0xFFFF).toInt()
        return tid xor sid xor hi xor lo
    }

    /** Unown letter 0..27 (A..Z, !, ?) from the PID (PKHeX `EntityPID.GetUnownForm3`). */
    public fun unownForm(pid: Long): Int {
        val p = pid and 0xFFFFFFFFL
        val value = ((p and 0x3000000L) shr 18) or ((p and 0x30000L) shr 12) or ((p and 0x300L) shr 6) or (p and 0x3L)
        return (value % 28).toInt()
    }

    /** Gender from the PID's low byte and the species' ratio (PKHeX `EntityGender.GetFromPIDAndRatio`). */
    public fun gender(pid: Long, ratio: Int): Gender = when (ratio) {
        PersonalInfo.RATIO_MAGIC_GENDERLESS -> Gender.GENDERLESS
        PersonalInfo.RATIO_MAGIC_FEMALE -> Gender.FEMALE
        PersonalInfo.RATIO_MAGIC_MALE -> Gender.MALE
        else -> if ((pid and 0xFF).toInt() < ratio) Gender.FEMALE else Gender.MALE
    }

    /**
     * Stats raised and lowered by [nature], as indexes 1..5 of (HP, Atk, Def, Spe, SpA, SpD), or null for a neutral
     * nature (PKHeX `NatureAmp.GetNatureModification`: up = nature / 5, down = nature % 5, in Atk/Def/Spe/SpA/SpD order).
     */
    public fun natureModification(nature: Int): Pair<Int, Int>? {
        if (nature !in 0..24) return null
        val up = nature / 5
        val down = nature % 5
        if (up == down) return null
        return (up + 1) to (down + 1)
    }

    /** Hidden Power type (0 = Fighting ... 15 = Dark) from the IVs in HP, Atk, Def, Spe, SpA, SpD order. */
    public fun hiddenPowerType(ivs: IntArray): Int {
        var bits = 0
        for (i in 0 until 6) bits = bits or ((ivs[i] and 1) shl i)
        return bits * 15 / 63
    }
}

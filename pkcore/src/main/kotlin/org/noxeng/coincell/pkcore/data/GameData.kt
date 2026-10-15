// Ported from PKHeX.Core/PKM/Util/Conversion/SpeciesConverter.cs, PKM/Util/Experience.cs, Moves/MoveInfo*.cs,
// PKM/Util/Conversion/ItemConverter.cs, Editing/HiddenPower.cs, Legality/RNG/Util/ShinyUtil.cs
// (PKHeX, GPL-3.0, commit 641c26b).
package org.noxeng.coincell.pkcore.data

/** Generation 1 internal species ids <-> National Dex ids. */
public object SpeciesConverter {
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
    /** Base PP (no PP Ups) of [move] in the given generation (1 or 2); 0 when unknown. */
    public fun getBasePp(generation: Int, move: Int): Int {
        val table = if (generation == 1) MoveTables.ppGen1 else MoveTables.ppGen2
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
}

/** Shininess and Hidden Power for Gen 1/2 DVs. */
public object DvUtil {
    /** Gen 2 shininess from the 16-bit DV value. */
    public fun isShiny(dv16: Int): Boolean = (dv16 and 0x2FFF) == 0x2AAA

    /** Hidden Power type index (0 = Fighting ... 15 = Dark), from the 16-bit DV value. */
    public fun hiddenPowerType(dv16: Int): Int = ((dv16 shr 10) and 0b1100) or ((dv16 shr 8) and 0b11)
}

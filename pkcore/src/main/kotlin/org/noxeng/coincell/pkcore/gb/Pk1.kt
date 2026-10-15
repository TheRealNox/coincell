// Ported from PKHeX.Core/PKM/PK1.cs (PKHeX, GPL-3.0, commit 641c26b).
// Not ported: conversion to PK2/PK7 (transfer logic).
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.CaughtData
import org.noxeng.coincell.pkcore.data.Gen1Tables
import org.noxeng.coincell.pkcore.data.ItemInfo
import org.noxeng.coincell.pkcore.data.PersonalInfo
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.pkcore.data.SpeciesConverter
import org.noxeng.coincell.pkcore.text.StringConverter1
import org.noxeng.coincell.pkcore.text.StringOption

/** Generation 1 Pokemon (party format is 44 bytes, box format the first 33). */
public class Pk1 internal constructor(data: ByteArray, otTrash: ByteArray, nickTrash: ByteArray) :
    GbPokemon(data, otTrash, nickTrash, LAYOUT) {

    /** Creates a blank Pokemon (species 0, terminator-filled names). */
    public constructor(japanese: Boolean = false) : this(
        ByteArray(PARTY_SIZE),
        blankString(japanese),
        blankString(japanese),
    )

    override val generation: Int get() = 1
    override val personalInfo: PersonalInfo get() = PersonalTable.Y[species]

    override fun decodeText(buffer: ByteArray): String = StringConverter1.getString(buffer, 0, buffer.size, isJapanese)
    override fun encodeText(dest: ByteArray, value: String, maxLength: Int, option: StringOption): Int =
        StringConverter1.setString(dest, 0, dest.size, value, maxLength, isJapanese, option)

    override fun duplicate(): Pk1 = Pk1(data.copyOf(), otTrash.copyOf(), nickTrash.copyOf()).also {
        it.fallbackLanguage = fallbackLanguage
    }

    /** Raw internal species id (the games' own order, not the National Dex). */
    public var speciesInternal: Int
        get() = u8(0)
        set(value) = setU8(0, value)

    /**
     * National Dex species. Setting it also updates the types and, if the catch-rate byte isn't valid for the
     * new species line, resets the catch rate (PKHeX `SetSpeciesValues`).
     */
    override var species: Int
        get() = SpeciesConverter.getNational1(speciesInternal)
        set(value) {
            val internalId = SpeciesConverter.getInternal1(value)
            if (speciesInternal == internalId) return
            speciesInternal = internalId

            val pi = PersonalTable.RB[value]
            type1 = pi.type1
            type2 = pi.type2

            // Before updating catch rate, check if non-standard
            if (isValidCatchRateAnyPreEvo(value, catchRate)) return
            // Matches nothing possible; just reset to current Species' rate.
            catchRate = pi.catchRate
        }

    override val isEgg: Boolean get() = false

    /** Level byte in the box slot (offset 3); the party level (offset 0x21) is [storedLevel]. */
    public var levelBox: Int
        get() = u8(3)
        set(value) = setU8(3, value)

    override var storedLevel: Int
        get() = u8(0x21)
        set(value) {
            levelBox = value
            setU8(0x21, value)
        }

    public var type1: Int
        get() = u8(5)
        set(value) = setU8(5, value)
    public var type2: Int
        get() = u8(6)
        set(value) = setU8(6, value)

    /** Catch rate byte; in Gen 2 it doubles as the held item of a Pokemon traded forward. */
    public var catchRate: Int
        get() = u8(7)
        set(value) = setU8(7, value)

    override val heldItemId: Int get() = 0
    override val friendshipValue: Int? get() = null
    override val caughtDataValue: CaughtData? get() = null
    override val pokerusStrainValue: Int get() = 0
    override val pokerusDaysValue: Int get() = 0

    private fun isValidCatchRateAnyPreEvo(species: Int, rate: Int): Boolean {
        if (ItemInfo.isCatchRateHeldItem(rate)) return true
        if (species == PIKACHU && rate == 0xA3) return true // Light Ball (starter)

        // Get de-evolution steps we should check for.
        val stage = if (species in Gen1Tables.evolutionStages.indices) Gen1Tables.evolutionStages[species] else 0
        val baby = species - stage
        for (s in baby..species) {
            if (rate == PersonalTable.RB[s].catchRate || rate == PersonalTable.Y[s].catchRate) return true
        }
        return false
    }

    internal companion object {
        const val PARTY_SIZE = 44
        const val STORED_SIZE = 33
        private const val PIKACHU = 25

        val LAYOUT = GbLayout(
            species = 0, tid = 0xC, exp = 0xE, statExp = 0x11, dv = 0x1B, moves = 8, pp = 0x1D,
            storedLevel = 0x21, status = 4, hpCurrent = 1, battleStats = 0x22,
            partySize = PARTY_SIZE, storedSize = STORED_SIZE,
        )

        fun blankString(japanese: Boolean): ByteArray = ByteArray(
            if (japanese) GbPokemon.STRING_LENGTH_JAPANESE else GbPokemon.STRING_LENGTH_OTHER,
        ) { StringConverter1.TERMINATOR_CODE.toByte() }
    }
}

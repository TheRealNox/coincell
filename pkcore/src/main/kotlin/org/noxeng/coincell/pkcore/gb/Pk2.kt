// Ported from PKHeX.Core/PKM/PK2.cs (PKHeX, GPL-3.0, commit 641c26b).
// Not ported: conversion to PK1/PK7/SK2 (transfer / Stadium logic).
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.CaughtData
import org.noxeng.coincell.pkcore.data.PersonalInfo
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.pkcore.text.StringConverter1
import org.noxeng.coincell.pkcore.text.StringConverter2
import org.noxeng.coincell.pkcore.text.StringOption

/** Generation 2 Pokemon (party format is 48 bytes, box format the first 32). */
public class Pk2 internal constructor(data: ByteArray, otTrash: ByteArray, nickTrash: ByteArray) :
    GbPokemon(data, otTrash, nickTrash, LAYOUT) {

    /** Creates a blank Pokemon (species 0, terminator-filled names). */
    public constructor(japanese: Boolean = false) : this(
        ByteArray(PARTY_SIZE),
        blankString(japanese),
        blankString(japanese),
    )

    override val generation: Int get() = 2
    override val personalInfo: PersonalInfo get() = PersonalTable.C[species]

    override fun decodeText(buffer: ByteArray): String = StringConverter2.getString(buffer, 0, buffer.size, textLanguage)
    override fun encodeText(dest: ByteArray, value: String, maxLength: Int, option: StringOption): Int =
        StringConverter2.setString(dest, 0, dest.size, value, maxLength, textLanguage, option)

    override fun duplicate(): Pk2 = Pk2(data.copyOf(), otTrash.copyOf(), nickTrash.copyOf()).also {
        it.fallbackLanguage = fallbackLanguage
        it.isEgg = isEgg
    }

    override var species: Int
        get() = u8(0)
        set(value) = setU8(0, value)

    /**
     * Egg flag. It is not part of the stored bytes: the list header holds 0xFD instead of the species for eggs,
     * which is how [PokeListFormat] restores it.
     */
    override var isEgg: Boolean = false

    public var heldItem: Int
        get() = u8(1)
        set(value) = setU8(1, value)

    override var storedLevel: Int
        get() = u8(0x1F)
        set(value) = setU8(0x1F, value)

    public var friendship: Int
        get() = u8(0x1B)
        set(value) = setU8(0x1B, value)

    /** Pokerus byte: high nibble strain, low nibble days remaining. */
    public var pokerusState: Int
        get() = u8(0x1C)
        set(value) = setU8(0x1C, value)

    public var pokerusDays: Int
        get() = pokerusState and 0xF
        set(value) {
            pokerusState = (pokerusState and 0xF.inv()) or value
        }

    public var pokerusStrain: Int
        get() = pokerusState shr 4
        set(value) {
            pokerusState = (pokerusState and 0xF) or (value shl 4)
        }

    /** Crystal only: time of day (2 bits), met level (6), OT gender (1), location (7). */
    public var caughtData: Int
        get() = u16(0x1D)
        set(value) = setU16(0x1D, value)

    public var metTimeOfDay: Int
        get() = (caughtData shr 14) and 0x3
        set(value) {
            caughtData = (caughtData and 0x3FFF) or ((value and 0x3) shl 14)
        }
    public var metLevel: Int
        get() = (caughtData shr 8) and 0x3F
        set(value) {
            caughtData = (caughtData and 0xC0FF) or ((value and 0x3F) shl 8)
        }
    public var originalTrainerGender: Int
        get() = (caughtData shr 7) and 1
        set(value) {
            caughtData = (caughtData and 0xFF7F) or ((value and 1) shl 7)
        }
    public var metLocation: Int
        get() = caughtData and 0x7F
        set(value) {
            caughtData = (caughtData and 0xFF80) or (value and 0x7F)
        }

    /** True if the caught data is non-zero (PKHeX `HasOriginalMetLocation`). */
    public val hasCaughtData: Boolean get() = caughtData != 0

    override val heldItemId: Int get() = heldItem
    override val friendshipValue: Int? get() = friendship
    override val pokerusStrainValue: Int get() = pokerusStrain
    override val pokerusDaysValue: Int get() = pokerusDays
    override val caughtDataValue: CaughtData?
        get() = CaughtData(caughtData, metTimeOfDay, metLevel, originalTrainerGender, metLocation)

    internal companion object {
        const val PARTY_SIZE = 48
        const val STORED_SIZE = 32

        val LAYOUT = GbLayout(
            species = 0, tid = 6, exp = 8, statExp = 0xB, dv = 0x15, moves = 2, pp = 0x17,
            storedLevel = 0x1F, status = 0x20, hpCurrent = 0x22, battleStats = 0x24,
            partySize = PARTY_SIZE, storedSize = STORED_SIZE,
        )

        fun blankString(japanese: Boolean): ByteArray = ByteArray(
            if (japanese) GbPokemon.STRING_LENGTH_JAPANESE else GbPokemon.STRING_LENGTH_OTHER,
        ) { StringConverter1.TERMINATOR_CODE.toByte() }
    }
}

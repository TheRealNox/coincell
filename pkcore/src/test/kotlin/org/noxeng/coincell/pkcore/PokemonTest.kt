package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.data.Experience
import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.data.MoveInfo
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.pkcore.data.SpeciesConverter
import org.noxeng.coincell.pkcore.gb.Pk1
import org.noxeng.coincell.pkcore.gb.Pk2
import org.noxeng.coincell.pkcore.text.LanguageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonTest {
    @Test
    fun personalTablesHaveTheExpectedShape() {
        assertEquals(152, PersonalTable.RB.size)
        assertEquals(152, PersonalTable.Y.size)
        assertEquals(252, PersonalTable.GS.size)
        assertEquals(252, PersonalTable.C.size)
        // Pikachu (25): 35/55/30/90/50, Electric (type 23)
        val pika = PersonalTable.Y[25]
        assertEquals(listOf(35, 55, 30, 90, 50), listOf(pika.hp, pika.atk, pika.def, pika.spe, pika.spa))
        assertEquals(23, pika.type1)
        assertEquals(163, pika.catchRate) // Yellow's Pikachu; Red/Blue's is 190
        assertEquals(190, PersonalTable.RB[25].catchRate)
        // Gen 2 splits Special: Typhlosion (157) 78/84/78/100/109/85
        val typh = PersonalTable.C[157]
        assertEquals(listOf(78, 84, 78, 100, 109, 85), listOf(typh.hp, typh.atk, typh.def, typh.spe, typh.spa, typh.spd))
        assertEquals(70, typh.baseFriendship)
    }

    @Test
    fun gen1InternalSpeciesOrderRoundTrips() {
        assertEquals(0x24, SpeciesConverter.getInternal1(16)) // Pidgey
        assertEquals(0x15, SpeciesConverter.getInternal1(151)) // Mew
        assertEquals(0x99, SpeciesConverter.getInternal1(1)) // Bulbasaur
        for (national in 1..151) {
            assertEquals(national, SpeciesConverter.getNational1(SpeciesConverter.getInternal1(national)))
        }
    }

    @Test
    fun experienceTables() {
        assertEquals(1, Experience.getLevel(0, 0))
        assertEquals(3, Experience.getLevel(57, 3)) // seen in the real Blue save: L3 with 57 exp
        assertEquals(100, Experience.getLevel(1_000_000, 0))
        assertEquals(1_000_000L, Experience.getExp(100, 0))
        assertEquals(0L, Experience.getExp(1, 5))
        val growth = PersonalTable.C[157].expGrowth
        assertEquals(37, Experience.getLevel(44801, growth)) // the real Crystal save's Typhlosion
        assertTrue(Experience.getExp(37, growth) <= 44801)
        assertTrue(Experience.getExp(38, growth) > 44801)
    }

    @Test
    fun statsUseTheGbFormula() {
        val pk = SyntheticSaves.pk2(species = 157, level = 37)
        pk.dv16 = 0xFFFF
        pk.evHp = 65535; pk.evAttack = 65535; pk.evDefense = 65535; pk.evSpeed = 65535; pk.evSpecial = 65535
        val s = pk.calculateStats()
        // Values of the real Crystal save's Typhlosion (checked against the stored party stats there).
        assertEquals(139, s.hp)
        assertEquals(101, s.attack)
        assertEquals(97, s.defense)
        assertEquals(113, s.speed)
        assertEquals(120, s.specialAttack)
        assertEquals(102, s.specialDefense)

        pk.evHp = 0; pk.evAttack = 0; pk.evDefense = 0; pk.evSpeed = 0; pk.evSpecial = 0
        assertEquals(((2 * (78 + 15)) * 37 / 100) + 5 + 5 + 37, pk.calculateStats().hp)
    }

    @Test
    fun gen1SharesSpecialAcrossAttackAndDefense() {
        val pk = SyntheticSaves.pk1(species = 25, level = 50)
        val s = pk.calculateStats()
        assertEquals(s.specialAttack, s.specialDefense)
        assertEquals(15, pk.ivAttack)
        assertEquals(10, pk.ivDefense)
        assertEquals(10, pk.ivSpeed)
        assertEquals(10, pk.ivSpecial)
        // HP DV is made of the lowest bits of the others: 1,0,0,0
        assertEquals(0b1000, pk.ivHp)
    }

    @Test
    fun ppUpsRaiseMaxPp() {
        val pk = SyntheticSaves.pk1()
        assertEquals(35, MoveInfo.getBasePp(1, 33)) // Tackle
        assertEquals(35, pk.getMaxPp(33, 0))
        assertEquals(35 + 3 * 7, pk.getMaxPp(33, 3))
        pk.setMovePpUps(0, 3)
        pk.healPp()
        assertEquals(56, pk.getMovePp(0))
        assertEquals(3, pk.getMovePpUps(0))
        pk.setMovePp(0, 70) // capped to 6 bits, PP Ups untouched
        assertEquals(63, pk.getMovePp(0))
        assertEquals(3, pk.getMovePpUps(0))
        // moves not set have 0 base PP
        assertEquals(0, pk.getMaxPp(0, 3))
    }

    @Test
    fun levelSetterWritesExpAndStoredLevel() {
        val pk = SyntheticSaves.pk1(species = 41, level = 3) // Zubat: medium fast
        pk.level = 17
        assertEquals(17, pk.level)
        assertEquals(17, pk.storedLevel)
        assertEquals(17, pk.levelBox)
        assertEquals(4913, pk.exp)
        pk.level = 1
        assertEquals(0, pk.exp)
    }

    @Test
    fun genderDerivesFromAttackDvAndRatio() {
        val pk = SyntheticSaves.pk1(species = 16)
        pk.ivAttack = 2
        assertEquals(Gender.FEMALE, pk.gender)
        pk.ivAttack = 10
        assertEquals(Gender.MALE, pk.gender)
        assertEquals(Gender.GENDERLESS, SyntheticSaves.pk1(species = 81).gender) // Magnemite
        assertEquals(Gender.FEMALE, SyntheticSaves.pk1(species = 113).gender) // Chansey
        assertEquals(Gender.MALE, SyntheticSaves.pk1(species = 106).gender) // Hitmonlee
    }

    @Test
    fun shinyAndHiddenPowerComeFromTheDvs() {
        val pk = SyntheticSaves.pk2()
        assertTrue(pk.isShiny) // 0xFAAA
        pk.ivAttack = 4
        assertFalse(pk.isShiny)
        pk.dv16 = 0xFAAA
        assertEquals(((0xFAAA shr 10) and 0b1100) or ((0xFAAA shr 8) and 3), pk.hiddenPowerType)
        assertFalse(SyntheticSaves.pk1().isShiny) // no shininess in Gen 1
    }

    @Test
    fun unownLetterFromDvs() {
        val pk = SyntheticSaves.pk2(species = 201)
        pk.dv16 = 0xFFFF
        assertEquals(25, pk.unownForm) // Z
        pk.dv16 = 0x0000
        assertEquals(0, pk.unownForm) // A
        assertEquals(0, SyntheticSaves.pk2(species = 25).unownForm)
    }

    @Test
    fun gen1SpeciesSetterUpdatesTypesAndCatchRate() {
        val pk = Pk1(false)
        pk.species = 25
        assertEquals(25, pk.species)
        assertEquals(0x54, pk.speciesInternal)
        assertEquals(23, pk.type1)
        assertEquals(23, pk.type2)
        assertEquals(0, pk.catchRate) // 0 is a valid "no held item" value, so it is left alone (as in PKHeX)

        // A catch rate that belongs to a pre-evolution is kept ...
        pk.species = 26 // Raichu
        pk.catchRate = 190
        pk.species = 25
        assertEquals(190, pk.catchRate)
        // ... one that matches nothing is reset to the species' own.
        pk.species = 26
        pk.catchRate = 7
        pk.species = 25
        assertEquals(190, pk.catchRate)
        pk.species = 150
        assertEquals(PersonalTable.RB[150].catchRate, pk.catchRate)
    }

    @Test
    fun gen2CaughtDataBits() {
        val pk = Pk2(false)
        pk.metTimeOfDay = 3
        pk.metLevel = 42
        pk.originalTrainerGender = 1
        pk.metLocation = 0x7F
        assertEquals(3, pk.metTimeOfDay)
        assertEquals(42, pk.metLevel)
        assertEquals(1, pk.originalTrainerGender)
        assertEquals(0x7F, pk.metLocation)
        assertEquals((3 shl 14) or (42 shl 8) or 0x80 or 0x7F, pk.caughtData)
        assertTrue(pk.hasCaughtData)
        pk.pokerusStrain = 5
        pk.pokerusDays = 3
        assertEquals(0x53, pk.pokerusState)
    }

    @Test
    fun summaryUsesLocalisedNames() {
        val pk = SyntheticSaves.pk2(species = 157, level = 20)
        pk.heldItem = 0x0E
        val en = pk.summary(GameStrings.forLanguage(LanguageId.ENGLISH))
        val fr = pk.summary(GameStrings.forLanguage(LanguageId.FRENCH))
        assertEquals("Typhlosion", en.speciesName)
        assertEquals("Typhlosion", fr.speciesName)
        assertEquals("Tackle", en.moves[0].name)
        assertEquals(GameStrings.forLanguage(LanguageId.FRENCH).moveName(33), fr.moves[0].name)
        assertEquals(GameStrings.forLanguage(LanguageId.ENGLISH).moveName(33), en.moves[0].name)
        assertTrue(en.heldItemName.isNotEmpty())
        assertEquals(20, en.level)
        assertEquals(2, en.generation)
    }

    @Test
    fun duplicateIsIndependent() {
        val pk = SyntheticSaves.pk2()
        val copy = pk.duplicate()
        assertTrue(pk.contentEquals(copy))
        copy.level = 50
        assertFalse(pk.contentEquals(copy))
        assertEquals(5, pk.level)
    }
}

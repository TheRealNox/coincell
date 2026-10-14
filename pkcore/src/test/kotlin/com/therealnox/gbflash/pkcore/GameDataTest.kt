package com.therealnox.gbflash.pkcore

import com.therealnox.gbflash.pkcore.data.GameStrings
import com.therealnox.gbflash.pkcore.data.ItemTables
import com.therealnox.gbflash.pkcore.data.MoveInfo
import com.therealnox.gbflash.pkcore.text.LanguageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The packaged resources (converted from PKHeX's) have the expected shape and content in every language. */
class GameDataTest {
    @Test
    fun everyLanguageHasCompleteNameLists() {
        for (lang in GameStrings.supportedLanguages) {
            val s = GameStrings.forLanguage(lang)
            assertEquals("species $lang", 252, s.species.size)
            assertEquals("moves $lang", 252, s.moves.size)
            assertEquals("items1 $lang", 256, s.itemsGen1.size)
            assertEquals("items2 $lang", 256, s.itemsGen2.size)
            for (id in 1..251) assertTrue("species $id in $lang", s.speciesName(id).isNotBlank())
            for (id in 1..251) assertTrue("move $id in $lang", s.moveName(id).isNotBlank())
            assertTrue(s.itemName(2, 5).isNotBlank())
        }
    }

    @Test
    fun spotCheckNamesAcrossLanguages() {
        val en = GameStrings.forLanguage(LanguageId.ENGLISH)
        val fr = GameStrings.forLanguage(LanguageId.FRENCH)
        val de = GameStrings.forLanguage(LanguageId.GERMAN)
        val es = GameStrings.forLanguage(LanguageId.SPANISH)
        val it = GameStrings.forLanguage(LanguageId.ITALIAN)
        val ja = GameStrings.forLanguage(LanguageId.JAPANESE)
        assertEquals("Bulbasaur", en.speciesName(1))
        assertEquals("Bulbizarre", fr.speciesName(1))
        assertEquals("Bisasam", de.speciesName(1))
        assertEquals("Bulbasaur", es.speciesName(1))
        assertEquals("Bulbasaur", it.speciesName(1))
        assertEquals("フシギダネ", ja.speciesName(1))
        assertEquals("Celebi", en.speciesName(251))
        assertEquals("Egg", en.speciesName(0))
        assertEquals("Pound", en.moveName(1))
        assertEquals("Tornade", fr.moveName(16)) // Gust
        assertEquals("Master Ball", en.itemName(1, 1))
        assertEquals("Master Ball", fr.itemName(2, 1))
        assertEquals("Poké Ball", fr.itemName(2, 5))
        assertEquals("Item #300", en.itemName(2, 300))
    }

    @Test
    fun moveTablesHaveTheRightSizes() {
        assertEquals(166, com.therealnox.gbflash.pkcore.data.MoveTables.ppGen1.size)
        assertEquals(252, com.therealnox.gbflash.pkcore.data.MoveTables.ppGen2.size)
        assertEquals(35, MoveInfo.getBasePp(2, 33))
        assertEquals(0, MoveInfo.getBasePp(1, 200)) // beyond Gen 1's last move
        assertTrue(MoveInfo.getBasePp(2, 200) > 0)
    }

    @Test
    fun legalItemListsAreSane() {
        assertTrue(ItemTables.gen2Machine.size == 57)
        assertTrue(ItemTables.gen2Balls.size == 11)
        assertTrue(ItemTables.gen1General.all { it in 0..255 })
        assertTrue(ItemTables.gen2General.toSet().size == ItemTables.gen2General.size)
    }
}

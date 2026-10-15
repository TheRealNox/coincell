package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.gba.Pk3
import org.noxeng.coincell.pkcore.gba.Sav3
import org.noxeng.coincell.pkcore.text.LanguageId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests on the user's French Sapphire (`fixtures-local`, never committed); skipped when the file is absent. */
class RealSaveGen3Test {
    @Test
    fun sapphireIsDetectedAndDecoded() {
        val bytes = Fixtures.sapphireOrSkip()
        val save = SaveFile.load(bytes)
        assertEquals(Game.RUBY_SAPPHIRE, save.game)
        assertEquals(Region.INTERNATIONAL, save.region)
        assertEquals(LanguageId.FRENCH, save.language)
        assertTrue(save.checksumsValid)

        val t = save.trainer
        assertEquals("SACHA", t.name)
        assertEquals(27212, t.id)
        assertEquals(47696, t.secretId)
        assertEquals(720951, t.money)
        assertEquals(43, t.coins)
        assertEquals(0xFF, t.badges)
        assertEquals(PlayTime(127, 6, 21), t.playTime)
        assertEquals(TrainerGender.BOY, t.gender)
        assertNull(t.rivalName)

        assertEquals(listOf("LEVIATOR", "HARIYAMA", "BEKIPAN", "STAROSS", "TENTACRUEL", "MAGNETON"), save.party.map { it.nickname })
        for (p in save.party) {
            assertEquals("${p.speciesName}: computed stats must equal the stats stored by the game", p.storedStats, p.stats)
            assertEquals(p.level, p.storedLevel)
            assertFalse(p.isNicknamed)
            assertFalse(p.gen3!!.isBadEgg)
            for (m in p.moves) assertTrue(m.pp <= m.maxPp)
        }
        val gyarados = save.party[0].gen3!!
        assertEquals("Rigide", gyarados.natureName)
        assertEquals("Intimidation", gyarados.abilityName)
        assertEquals(14, gyarados.metLocation)

        assertEquals("BOITE 1", save.boxName(0))
        assertEquals(3, save.currentBox)
        assertEquals(listOf(17, 27, 26, 22, 9), save.boxes.take(5).map { it.pokemon.size })
        for (b in save.boxes) for (p in b.pokemon) assertFalse(p.gen3!!.isBadEgg)
        assertEquals(183, (1..386).count { save.isSeen(it) })
        assertEquals(170, (1..386).count { save.isCaught(it) })

        val bag = save.inventory
        assertEquals(13, bag.pocket(PocketType.ITEMS)!!.items.size)
        assertEquals(ItemStack(4, 10), bag.pocket(PocketType.BALLS)!!.items[0]) // 10 Poke Balls
        assertEquals(19, bag.pocket(PocketType.TM_HM)!!.items.size)
    }

    @Test
    fun sapphireExportsByteIdenticallyUntouchedAndRewritten() {
        val bytes = Fixtures.sapphireOrSkip()
        assertArrayEquals(bytes, SaveFile.load(bytes).export())
        // Every sector of the active slot rewritten with fresh checksums gives the very same bytes.
        assertArrayEquals(bytes, (SaveFile.load(bytes) as Sav3).exportLikePkhex())
    }

    @Test
    fun sapphireEditsOnlyTouchTheActiveSlot() {
        val bytes = Fixtures.sapphireOrSkip()
        val save = SaveFile.load(bytes)
        save.updateTrainer(save.trainer.copy(money = 999_999))
        val pk = save.getPokemon(SlotRef.Party(1)) as Pk3
        pk.level = 50
        pk.resetPartyStats()
        save.setPokemon(SlotRef.Party(1), pk, updatePokedex = false)
        save.movePokemon(SlotRef.Party(5), SlotRef.Box(5, 7))
        save.setPocket(PocketType.BALLS, save.inventory.pocket(PocketType.BALLS)!!.items + ItemStack(1, 3))
        val out = save.export()

        val changed = bytes.indices.filter { bytes[it] != out[it] }
        val activeStart = changed.minOrNull()!! / 0xE000 * 0xE000
        assertTrue("only one save slot changes", changed.all { it in activeStart until activeStart + 0xE000 })

        val again = SaveFile.load(out)
        assertTrue(again.checksumsValid)
        assertEquals(999_999, again.trainer.money)
        assertEquals(50, again.party[1].level)
        assertEquals(again.party[1].stats, again.party[1].storedStats)
        assertEquals(5, again.party.size)
        assertEquals("MAGNETON", again.boxes[5].slots[7]!!.nickname)
        assertEquals(ItemStack(1, 3), again.inventory.pocket(PocketType.BALLS)!!.items.last())
        // Everything else is as it was.
        assertEquals(save.boxes.take(5).map { it.slots }, again.boxes.take(5).map { it.slots })
    }
}

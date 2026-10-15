package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.gb.GbPokemon
import org.noxeng.coincell.pkcore.gb.GbSave
import org.noxeng.coincell.pkcore.gb.Sav1
import org.noxeng.coincell.pkcore.text.LanguageId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests on the user's own saves (`pkcore/src/test/fixtures-local`, never committed). Each test is skipped through
 * JUnit `Assume` when its file is absent, so CI and fresh clones still pass.
 */
class RealSaveTest {
    private fun diff(a: ByteArray, b: ByteArray): Set<Int> = a.indices.filter { a[it] != b[it] }.toSet()

    private fun hex(set: Set<Int>) = set.sorted().joinToString { "0x" + it.toString(16) }

    /** Everything the party members' stored stats must satisfy if the formulas match the game. */
    private fun assertPartyConsistent(save: SaveFile) {
        for ((i, p) in save.party.withIndex()) {
            val label = "party[$i] ${p.speciesName}"
            assertTrue(label, p.species in 1..(if (save.generation == 1) 151 else 251))
            assertEquals(label, p.level, p.storedLevel)
            assertEquals("$label: computed stats must equal the stats stored by the game", p.storedStats, p.stats)
            assertTrue("$label hp", p.currentHp in 0..p.stats.hp)
            for (m in p.moves) assertTrue("$label ${m.name}", m.pp <= m.maxPp)
            assertTrue(label, p.moves[0].id != 0)
            for (dv in listOf(p.dvs.attack, p.dvs.defense, p.dvs.speed, p.dvs.special)) assertTrue(label, dv in 0..15)
        }
    }

    // ---- Pokemon Blue (English cartridge, Gen 1) -----------------------------------------------------------------

    @Test
    fun blueIsDetectedAndDecoded() {
        val bytes = Fixtures.blueOrSkip()
        val save = SaveFile.load(bytes)
        assertEquals(1, save.generation)
        assertEquals(Game.RED_BLUE, save.game)
        assertEquals(Region.INTERNATIONAL, save.region)
        assertEquals(12, save.boxCount)
        assertEquals(20, save.boxCapacity)

        assertEquals(0x2E, bytes[0x3523].toInt() and 0xFF)
        assertTrue(save.checksumsValid)
        assertTrue((save as Sav1).bankChecksumsValid())

        val t = save.trainer
        assertEquals("NICK", t.name)
        assertEquals("GARY", t.rivalName)
        assertEquals(PlayTime(255, 39, 7), t.playTime)
        assertEquals(4445, t.id)
        assertEquals(556786, t.money)
        assertEquals(0xFF, t.badges)
        assertEquals(8, t.badgeCount)
        assertEquals(343, t.coins)

        assertEquals(6, save.party.size)
        assertEquals(listOf(16, 16, 41, 16, 16, 16), save.party.map { it.species })
        assertEquals(listOf(3, 3, 17, 3, 2, 4), save.party.map { it.level })
        assertEquals(List(6) { "Nox" }, save.party.map { it.otName })
        assertEquals(List(6) { 1960 }, save.party.map { it.trainerId })
        assertEquals(listOf("ROUCOOL", "ROUCOOL", "NOSFERAPTI", "ROUCOOL", "ROUCOOL", "ROUCOOL"), save.party.map { it.nickname })
        assertTrue(save.party.none { it.isNicknamed })
        assertEquals(Dvs(2, 7, 1, 0), save.party[0].dvs)
        assertEquals(Dvs(10, 5, 6, 5), save.party[1].dvs)
        assertPartyConsistent(save)

        // PC: 12 boxes; Box 8 is full of Tauros, box 11 is empty
        assertEquals(81, save.boxes.sumOf { it.pokemon.size })
        assertEquals(20, save.boxes[7].pokemon.size)
        assertTrue(save.boxes[7].pokemon.all { it.species == 128 })
        assertEquals(0, save.boxes[10].pokemon.size)
        assertEquals(1, save.currentBox)
        assertTrue(save.boxes[1].isCurrent)
        assertEquals(15, save.boxes[1].pokemon.size)
        // box Pokemon have no party data; their stored level matches the exp-derived one
        for (b in save.boxes) for (p in b.pokemon) {
            assertTrue(p.species in 1..151)
            assertEquals(0, p.storedStats.hp)
            assertTrue("${b.name} ${p.speciesName}", p.level in 1..100)
        }
    }

    @Test
    fun blueRoundTripIsByteIdentical() {
        val bytes = Fixtures.blueOrSkip()
        val save = SaveFile.load(bytes)
        assertFalse(save.isModified)
        assertArrayEquals(bytes, save.export())
        // twice, and through a reload
        assertArrayEquals(bytes, save.export())
        assertArrayEquals(bytes, SaveFile.load(save.export()).export())
    }

    @Test
    fun blueFullPkhexStyleRewriteKeepsAllContent() {
        // PKHeX re-packs every list on export. In this save that only clears leftover bytes after the last
        // Pokemon of a box (outside the lists' counts); every decoded value stays the same.
        val bytes = Fixtures.blueOrSkip()
        val original = SaveFile.load(bytes)
        val rewritten = (SaveFile.load(bytes) as GbSave).exportLikePkhex()
        val reloaded = SaveFile.load(rewritten)
        assertTrue(reloaded.checksumsValid)
        assertTrue((reloaded as Sav1).bankChecksumsValid())
        assertEquals(original.trainer, reloaded.trainer)
        assertEquals(original.party, reloaded.party)
        assertEquals(original.boxes, reloaded.boxes)
        assertEquals(original.inventory, reloaded.inventory)
        // the rewrite is stable
        assertArrayEquals(rewritten, (reloaded as GbSave).exportLikePkhex())
    }

    @Test
    fun blueLevelEditChangesOnlyTheExpectedBytes() {
        val bytes = Fixtures.blueOrSkip()
        val save = SaveFile.load(bytes)
        val slot = 2 // Zubat, L17
        assertEquals(17, save.party[slot].level)

        val pk = save.getPokemon(SlotRef.Party(slot)) as GbPokemon
        pk.level = 20
        save.setPokemon(SlotRef.Party(slot), pk)
        assertTrue(save.isModified)
        val out = save.export()
        assertEquals(bytes.size, out.size)

        val body = 0x2F2C + 1 + 7 + slot * 44
        val changed = diff(bytes, out)
        // exp (3 bytes at +0xE), the level byte of the box format (+3), the party level (+0x21), main checksum
        val allowed = setOf(body + 3, body + 0xE, body + 0xF, body + 0x10, body + 0x21, 0x3523)
        assertTrue("unexpected changes: ${hex(changed - allowed)}", allowed.containsAll(changed))
        assertTrue(hex(changed), changed.containsAll(setOf(body + 3, body + 0x21, 0x3523)))
        assertEquals(20, out[body + 0x21].toInt())
        assertEquals(20, out[body + 3].toInt())

        val reloaded = SaveFile.load(out)
        assertTrue(reloaded.checksumsValid)
        assertEquals(20, reloaded.party[slot].level)
        assertEquals(save.party[slot].exp, reloaded.party[slot].exp)
        // everything else decodes identically
        assertEquals(SaveFile.load(bytes).trainer, reloaded.trainer)
        assertEquals(SaveFile.load(bytes).boxes, reloaded.boxes)
        for (i in 0 until 6) if (i != slot) assertEquals(SaveFile.load(bytes).party[i], reloaded.party[i])
        // the stats were not recalculated by a plain level edit; an explicit call does that
        assertEquals(SaveFile.load(bytes).party[slot].storedStats, reloaded.party[slot].storedStats)
    }

    @Test
    fun blueEditsInABoxAndTheTrainerKeepUntouchedBoxesIntact() {
        val bytes = Fixtures.blueOrSkip()
        val save = SaveFile.load(bytes)
        // move the Mew of box 1 to the party's end... party is full, so swap it with the last member
        val mew = save.getPokemon(SlotRef.Box(0, 0)) as GbPokemon
        assertEquals(151, mew.species)
        mew.level = 6
        save.setPokemon(SlotRef.Box(0, 0), mew)
        save.updateTrainer(save.trainer.copy(money = 999999))
        val out = save.export()
        val reloaded = SaveFile.load(out)
        assertEquals(6, reloaded.boxes[0].pokemon[0].level)
        assertEquals(999999, reloaded.trainer.money)
        assertTrue(reloaded.checksumsValid)
        assertTrue((reloaded as Sav1).bankChecksumsValid())
        // the other boxes are byte-for-byte what they were (leftover bytes included): box 8 sits in bank 3
        val boxLen = 1122
        assertArrayEquals(
            bytes.copyOfRange(0x6000 + 1 * boxLen, 0x6000 + 2 * boxLen),
            out.copyOfRange(0x6000 + 1 * boxLen, 0x6000 + 2 * boxLen),
        )
        // the current box (box 2) is untouched in both copies
        assertArrayEquals(bytes.copyOfRange(0x30C0, 0x30C0 + boxLen), out.copyOfRange(0x30C0, 0x30C0 + boxLen))
    }

    // ---- Pokemon Crystal (French cartridge, Gen 2) ---------------------------------------------------------------

    @Test
    fun crystalIsDetectedAndDecoded() {
        val bytes = Fixtures.crystalOrSkip()
        assertEquals(0x8000, bytes.size)
        val save = SaveFile.load(bytes)
        assertEquals(2, save.generation)
        assertEquals(Game.CRYSTAL, save.game)
        assertEquals(Region.INTERNATIONAL, save.region)
        assertEquals(LanguageId.FRENCH, save.language)
        assertEquals(14, save.boxCount)
        assertTrue(save.checksumsValid)

        val t = save.trainer
        assertEquals("Nox", t.name)
        assertEquals(59539, t.id)
        assertEquals("Victor", t.rivalName)
        assertEquals(TrainerGender.BOY, t.gender)
        assertEquals(PlayTime(41, 18, 19), t.playTime)
        assertEquals(63154, t.money)
        assertEquals(4794, t.coins)
        assertEquals(0xFF, t.badges and 0xFF) // all eight Johto badges

        assertEquals(listOf(157, 178, 94, 76, 62, 125), save.party.map { it.species })
        assertEquals(listOf(37, 36, 35, 35, 35, 39), save.party.map { it.level })
        assertEquals(
            listOf("TYPHLOSION", "XATU", "ECTOPLASMA", "GROLEM", "TARTARD", "ELEKTEK"),
            save.party.map { it.nickname },
        )
        assertTrue(save.party.none { it.isNicknamed })
        assertEquals(List(6) { "Nox" }, save.party.map { it.otName })
        assertEquals(List(6) { 59539 }, save.party.map { it.trainerId })
        assertEquals(List(6) { Dvs(15, 15, 15, 15) }, save.party.map { it.dvs })
        assertEquals(List(6) { Gender.MALE }, save.party.map { it.gender })
        assertTrue(save.party.none { it.isShiny })
        assertTrue(save.party.all { (it.caughtData?.metLevel ?: 0) > 0 })
        assertPartyConsistent(save)

        // PC: boxes 1-5 are filled; the names are the French defaults
        assertEquals(listOf(20, 20, 20, 20, 18), save.boxes.take(5).map { it.pokemon.size })
        assertEquals(0, save.boxes.drop(5).sumOf { it.pokemon.size })
        assertEquals("BOITE1", save.boxName(0))
        assertEquals(4, save.currentBox)
        assertTrue(save.boxes[4].isCurrent)
        for (b in save.boxes) for (p in b.pokemon) assertTrue("${b.name} ${p.speciesName}", p.species in 1..251)

        // bag
        val inv = save.inventory
        assertEquals(29, inv.pocket(PocketType.TM_HM)!!.items.size)
        assertEquals(19, inv.pocket(PocketType.ITEMS)!!.items.size)
        assertEquals(8, inv.pocket(PocketType.KEY_ITEMS)!!.items.size)
        assertEquals(listOf(ItemStack(5, 61), ItemStack(161, 1), ItemStack(4, 27), ItemStack(2, 3)), inv.pocket(PocketType.BALLS)!!.items)
    }

    @Test
    fun crystalRoundTripIsByteIdentical() {
        val bytes = Fixtures.crystalOrSkip()
        val save = SaveFile.load(bytes)
        assertFalse(save.isModified)
        assertArrayEquals(bytes, save.export())
        assertArrayEquals(bytes, SaveFile.load(save.export()).export())
        // The save is also a fixed point of the full PKHeX-style rewrite (lists, checksums and backups).
        assertArrayEquals(bytes, (SaveFile.load(bytes) as GbSave).exportLikePkhex())
    }

    @Test
    fun crystalLevelEditChangesOnlyTheExpectedBytes() {
        val bytes = Fixtures.crystalOrSkip()
        val save = SaveFile.load(bytes)
        val slot = 1 // Xatu, L36
        assertEquals(36, save.party[slot].level)

        val pk = save.getPokemon(SlotRef.Party(slot)) as GbPokemon
        pk.level = 40
        save.setPokemon(SlotRef.Party(slot), pk)
        val out = save.export()

        val body = 0x2865 + 1 + 7 + slot * 48
        val primary = setOf(body + 8, body + 9, body + 0xA, body + 0x1F) // exp (3 bytes), level
        val backup = primary.map { it - 0x2009 + 0x1209 }.toSet() // the game's mirrored copy of the main block
        val checksums = setOf(0x2D0D, 0x2D0E, 0x1F0D, 0x1F0E)
        val changed = diff(bytes, out)
        val allowed = primary + backup + checksums
        assertTrue("unexpected changes: ${hex(changed - allowed)}", allowed.containsAll(changed))
        assertTrue(hex(changed), changed.containsAll(setOf(body + 0x1F, body + 0x1F - 0x2009 + 0x1209)))
        // both checksum copies changed (the low byte always does for a +delta of a few bytes)
        assertTrue(hex(changed), changed.any { it in setOf(0x2D0D, 0x2D0E) } && changed.any { it in setOf(0x1F0D, 0x1F0E) })

        val reloaded = SaveFile.load(out)
        assertTrue(reloaded.checksumsValid)
        assertEquals(40, reloaded.party[slot].level)
        val before = SaveFile.load(bytes)
        assertEquals(before.trainer, reloaded.trainer)
        assertEquals(before.boxes, reloaded.boxes)
        assertEquals(before.inventory, reloaded.inventory)
        for (i in 0 until 6) if (i != slot) assertEquals(before.party[i], reloaded.party[i])
    }

    @Test
    fun crystalTrainerAndBagEditsKeepTheBackupInSync() {
        val bytes = Fixtures.crystalOrSkip()
        val save = SaveFile.load(bytes)
        save.updateTrainer(save.trainer.copy(money = 999999, coins = 9999, gender = TrainerGender.GIRL))
        save.setPocket(PocketType.ITEMS, listOf(ItemStack(18, 99)))
        val out = save.export()
        val reloaded = SaveFile.load(out)
        assertTrue(reloaded.checksumsValid)
        assertEquals(999999, reloaded.trainer.money)
        assertEquals(TrainerGender.GIRL, reloaded.trainer.gender)
        assertEquals(listOf(ItemStack(18, 99)), reloaded.inventory.pocket(PocketType.ITEMS)!!.items)
        assertArrayEquals(out.copyOfRange(0x2009, 0x2009 + 0xB7A), out.copyOfRange(0x1209, 0x1209 + 0xB7A))
        // Pokemon and PC were not touched
        assertEquals(SaveFile.load(bytes).party, reloaded.party)
        assertEquals(SaveFile.load(bytes).boxes, reloaded.boxes)
    }

    @Test
    fun crystalPcMoveBetweenBoxAndPartyRoundTrips() {
        val bytes = Fixtures.crystalOrSkip()
        val save = SaveFile.load(bytes)
        val before = SaveFile.load(bytes)
        // deposit the last party member into box 6 and take a Pokemon of box 5 into the party
        save.movePokemon(SlotRef.Party(5), SlotRef.Box(5, 0))
        save.movePokemon(SlotRef.Box(4, 0), SlotRef.Party(5))
        val out = save.export()
        val reloaded = SaveFile.load(out)
        assertTrue(reloaded.checksumsValid)
        assertEquals(before.party.take(5), reloaded.party.take(5))
        assertEquals(before.boxes[4].pokemon[0].species, reloaded.party[5].species)
        assertEquals(before.party[5].species, reloaded.boxes[5].pokemon[0].species)
        assertEquals(before.party[5].level, reloaded.boxes[5].pokemon[0].level)
        assertEquals(17, reloaded.boxes[4].pokemon.size)
        // the untouched boxes keep their exact bytes
        val boxLen = 1102 + 2
        for (b in 0 until 4) {
            assertArrayEquals(
                bytes.copyOfRange(0x4000 + b * boxLen, 0x4000 + (b + 1) * boxLen),
                out.copyOfRange(0x4000 + b * boxLen, 0x4000 + (b + 1) * boxLen),
            )
        }
    }
}

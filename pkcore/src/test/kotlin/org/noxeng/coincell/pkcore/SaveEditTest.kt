package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.gb.GbPokemon
import org.noxeng.coincell.pkcore.gb.Pk2
import org.noxeng.coincell.pkcore.text.LanguageId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Edit operations on synthetic saves: trainer, party/box slots, inventory, pokedex, text languages. */
class SaveEditTest {
    private fun ByteArray.u8(ofs: Int) = this[ofs].toInt() and 0xFF

    private val all = SyntheticSaves.allVariants
    private fun load(game: Game, region: Region = Region.INTERNATIONAL, size: Int = 0x8000) =
        SaveFile.load(SyntheticSaves.blank(game, region, size, yellow = game == Game.YELLOW))

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected InvalidEditException")
        } catch (_: InvalidEditException) {
            // expected
        }
    }

    // ---- trainer ---------------------------------------------------------------------------------------------

    @Test
    fun trainerEditsRoundTripOnEveryVariant() {
        for ((game, region, size) in all) {
            val save = load(game, region, size)
            val jp = region == Region.JAPANESE
            val edited = save.trainer.copy(
                name = if (jp) "アッシュ" else "ASH",
                id = 4242,
                money = 123456,
                badges = 0x0F,
                playTime = PlayTime(12, 34, 56),
                rivalName = if (jp) "グリーン" else "GARY",
                coins = 777,
            )
            save.updateTrainer(edited)
            assertTrue(save.isModified)
            val out = save.export()
            val reloaded = SaveFile.load(out)
            assertEquals("$game $region", edited, reloaded.trainer)
            assertTrue("$game $region", reloaded.checksumsValid)
        }
    }

    @Test
    fun gen1MoneyAndCoinsAreBcdAndPlayTimeOverflowSetsTheMaxFlag() {
        val save = load(Game.RED_BLUE)
        save.updateTrainer(save.trainer.copy(money = 123456, coins = 4321))
        var out = save.export()
        assertEquals(listOf(0x12, 0x34, 0x56), (0x25F3..0x25F5).map { out.u8(it) })
        assertEquals(listOf(0x43, 0x21), (0x2850..0x2851).map { out.u8(it) })
        // 999999 is the cap
        save.updateTrainer(save.trainer.copy(money = 5_000_000))
        out = save.export()
        assertEquals(999999, SaveFile.load(out).trainer.money)
        // 255 hours or more: flag set, minutes/seconds cleared
        save.updateTrainer(save.trainer.copy(playTime = PlayTime(300, 12, 13)))
        out = save.export()
        assertEquals(255, out.u8(0x2CED))
        assertEquals(1, out.u8(0x2CED + 1))
        assertEquals(PlayTime(255, 0, 0), SaveFile.load(out).trainer.playTime)
    }

    @Test
    fun gen2MoneyIsBigEndianAndKeepsTheFourthByte() {
        val bytes = SyntheticSaves.blank(Game.CRYSTAL)
        bytes[0x23DC + 3] = 0x5A // the byte after the 3 money bytes is not ours
        val save = SaveFile.load(bytes)
        save.updateTrainer(save.trainer.copy(money = 0x0102A3, coins = 0x1234 and 0x0FFF))
        val out = save.export()
        assertEquals(listOf(0x01, 0x02, 0xA3), (0x23DC..0x23DE).map { out.u8(it) })
        assertEquals(0x5A, out.u8(0x23DC + 3))
        assertEquals(listOf(0x02, 0x34), (0x23DC + 7..0x23DC + 8).map { out.u8(it) })
    }

    @Test
    fun crystalHasTrainerGenderAndGoldSilverDoesNot() {
        val crystal = load(Game.CRYSTAL)
        assertEquals(TrainerGender.BOY, crystal.trainer.gender)
        crystal.updateTrainer(crystal.trainer.copy(gender = TrainerGender.GIRL))
        val out = crystal.export()
        assertEquals(1, out.u8(0x3E3D))
        assertEquals(1, out.u8(0x206A)) // palette follows the gender
        assertEquals(TrainerGender.GIRL, SaveFile.load(out).trainer.gender)
        assertNull(load(Game.GOLD_SILVER).trainer.gender)
        assertNull(load(Game.RED_BLUE).trainer.gender)
        // a Japanese Crystal on a 32 KiB cartridge has its gender byte beyond the data: unavailable, not a crash
        assertNull(load(Game.CRYSTAL, Region.JAPANESE, 0x8000).trainer.gender)
        assertNotNull(load(Game.CRYSTAL, Region.JAPANESE, 0x10000).trainer.gender)
    }

    @Test
    fun updatingWithTheSameTrainerChangesNothing() {
        val save = load(Game.CRYSTAL)
        save.updateTrainer(save.trainer)
        assertFalse(save.isModified)
    }

    @Test
    fun trainerNamesAreCappedToTheGameLimit() {
        val save = load(Game.RED_BLUE)
        save.updateTrainer(save.trainer.copy(name = "ABCDEFGHIJKLMNOP", rivalName = "ABCDEFGHIJKLMNOP"))
        val t = SaveFile.load(save.export()).trainer
        assertEquals("ABCDEFG", t.name)
        assertEquals("ABCDEFG", t.rivalName)
    }

    // ---- slots -------------------------------------------------------------------------------------------------

    @Test
    fun partyGrowsContiguouslyUpToSix() {
        for ((game, region, size) in all) {
            val save = load(game, region, size)
            for (i in 0 until 6) save.setPokemon(SlotRef.Party(i), SyntheticSaves.pokemon(save, 1 + i, 5 + i))
            assertEquals(6, save.party.size)
            expectInvalid { save.setPokemon(SlotRef.Party(6), SyntheticSaves.pokemon(save)) }
            val reloaded = SaveFile.load(save.export())
            assertEquals(listOf(1, 2, 3, 4, 5, 6), reloaded.party.map { it.species })
        }
        val save = load(Game.CRYSTAL)
        expectInvalid { save.setPokemon(SlotRef.Party(1), SyntheticSaves.pokemon(save)) } // gap
    }

    @Test
    fun removingASlotShiftsTheFollowingOnesUp() {
        val save = load(Game.GOLD_SILVER)
        for (i in 0 until 4) save.setPokemon(SlotRef.Party(i), SyntheticSaves.pokemon(save, 10 + i))
        val removed = save.removePokemon(SlotRef.Party(1))
        assertEquals(11, removed!!.species)
        assertEquals(listOf(10, 12, 13), save.party.map { it.species })
        assertNull(save.removePokemon(SlotRef.Party(5)))
        assertNull(save.getPokemon(SlotRef.Party(3)))
        val out = save.export()
        assertEquals(3, out.u8(0x288A))
        assertEquals(listOf(10, 12, 13, 0xFF), (1..4).map { out.u8(0x288A + it) })
        assertEquals(listOf(10, 12, 13), SaveFile.load(out).party.map { it.species })
    }

    @Test
    fun movingBetweenPartyAndBoxConvertsTheStatsLikeTheGameDoes() {
        for (game in listOf(Game.RED_BLUE, Game.CRYSTAL)) {
            val save = load(game)
            save.setPokemon(SlotRef.Party(0), SyntheticSaves.pokemon(save, 25, 30))
            val hp = (save.getPokemon(SlotRef.Party(0)) as GbPokemon).maxHp
            assertTrue(hp > 0)

            save.movePokemon(SlotRef.Party(0), SlotRef.Box(2, 0))
            assertEquals(0, save.party.size)
            val boxed = save.getPokemon(SlotRef.Box(2, 0)) as GbPokemon
            assertEquals(0, boxed.maxHp) // box form has no party stats
            assertEquals(30, boxed.level)

            save.movePokemon(SlotRef.Box(2, 0), SlotRef.Party(0))
            val back = save.getPokemon(SlotRef.Party(0)) as GbPokemon
            assertEquals(hp, back.maxHp) // recomputed
            assertEquals(hp, back.currentHp)
            assertEquals(0, save.boxes[2].pokemon.size)
        }
    }

    @Test
    fun movingOntoAnOccupiedSlotSwaps() {
        val save = load(Game.CRYSTAL)
        save.setPokemon(SlotRef.Party(0), SyntheticSaves.pokemon(save, 1))
        save.setPokemon(SlotRef.Party(1), SyntheticSaves.pokemon(save, 4))
        save.setPokemon(SlotRef.Box(0, 0), SyntheticSaves.pokemon(save, 7))
        save.movePokemon(SlotRef.Party(0), SlotRef.Party(1))
        assertEquals(listOf(4, 1), save.party.map { it.species })
        save.movePokemon(SlotRef.Party(0), SlotRef.Box(0, 0))
        assertEquals(listOf(7, 1), save.party.map { it.species })
        assertEquals(listOf(4), save.boxes[0].pokemon.map { it.species })
        expectInvalid { save.movePokemon(SlotRef.Party(3), SlotRef.Box(0, 0)) } // empty source
        expectInvalid { save.movePokemon(SlotRef.Party(0), SlotRef.Box(0, 5)) } // gap in destination
    }

    @Test
    fun addPokemonGoesToTheFirstBoxWithRoomStartingAtTheCurrentOne() {
        val save = load(Game.RED_BLUE)
        val first = save.addPokemon(SyntheticSaves.pokemon(save, 25))
        assertEquals(SlotRef.Box(0, 0), first)
        for (i in 1 until 20) save.addPokemon(SyntheticSaves.pokemon(save, 25))
        assertEquals(SlotRef.Box(1, 0), save.addPokemon(SyntheticSaves.pokemon(save, 26)))
        // fill everything
        var n = 0
        while (save.addPokemon(SyntheticSaves.pokemon(save, 27)) != null) n++
        assertEquals(12 * 20 - 21, n)
        assertEquals(20, save.boxes.last().pokemon.size)
        // and the whole thing exports and reloads
        val reloaded = SaveFile.load(save.export())
        assertEquals(240, reloaded.boxes.sumOf { it.pokemon.size })
    }

    @Test
    fun wrongKindsOfPokemonAreRefused() {
        val gen1 = load(Game.RED_BLUE)
        val gen2 = load(Game.CRYSTAL)
        expectInvalid { gen1.setPokemon(SlotRef.Party(0), SyntheticSaves.pk2()) }
        expectInvalid { gen2.setPokemon(SlotRef.Party(0), SyntheticSaves.pk1()) }
        expectInvalid { gen1.setPokemon(SlotRef.Party(0), SyntheticSaves.pk1(japanese = true)) } // text buffers differ
        expectInvalid { gen2.setPokemon(SlotRef.Party(0), Pk2(false)) } // species 0
        expectInvalid { gen2.setPokemon(SlotRef.Party(9), SyntheticSaves.pk2()) }
        expectInvalid { gen2.setPokemon(SlotRef.Box(14, 0), SyntheticSaves.pk2()) }
        expectInvalid { gen2.setPokemon(SlotRef.Box(0, 20), SyntheticSaves.pk2()) }
    }

    @Test
    fun gettersReturnCopiesSoEditsOnlyApplyThroughSetPokemon() {
        val save = load(Game.CRYSTAL)
        save.setPokemon(SlotRef.Party(0), SyntheticSaves.pokemon(save, 25, 10))
        val copy = save.getPokemon(SlotRef.Party(0)) as Pk2
        copy.level = 50
        assertEquals(10, save.party[0].level)
        save.setPokemon(SlotRef.Party(0), copy)
        assertEquals(50, save.party[0].level)
        // and the stored object is independent of the one passed in
        copy.level = 60
        assertEquals(50, save.party[0].level)
    }

    @Test
    fun editingEveryFieldOfAPokemonSurvivesExport() {
        val save = load(Game.CRYSTAL)
        val pk = SyntheticSaves.pk2(species = 157, level = 40, nick = "FLAMME")
        pk.heldItem = 0x0E
        pk.friendship = 200
        pk.dv16 = 0x9876
        pk.evHp = 1000; pk.evAttack = 2000; pk.evDefense = 3000; pk.evSpeed = 4000; pk.evSpecial = 5000
        pk.setMove(1, 52); pk.setMovePp(1, 25); pk.setMovePpUps(1, 2)
        pk.pokerusStrain = 2; pk.pokerusDays = 4
        pk.metLevel = 9; pk.metLocation = 33; pk.metTimeOfDay = 2; pk.originalTrainerGender = 1
        pk.status = 0x08
        pk.otName = "TEST"
        pk.trainerId = 31337
        save.setPokemon(SlotRef.Party(0), pk)
        val back = SaveFile.load(save.export()).getPokemon(SlotRef.Party(0)) as Pk2
        assertTrue(pk.contentEquals(back))
        val s = back.summary()
        assertEquals("FLAMME", s.nickname)
        assertEquals("TEST", s.otName)
        assertEquals(31337, s.trainerId)
        assertEquals(0x9876, back.dv16)
        assertEquals(StatExp(1000, 2000, 3000, 4000, 5000), s.statExp)
        assertEquals(MoveSlot(52, "Ember", 25, 2, 25 + 2 * 5), s.moves[1]) // 25 base PP, +5 per PP Up
        assertEquals(0x0E, s.heldItem)
        assertEquals(200, s.friendship)
        assertEquals(2, s.pokerusStrain)
        assertEquals(4, s.pokerusDays)
        assertEquals(CaughtData(back.caughtData, 2, 9, 1, 33), s.caughtData)
        assertEquals(0x08, s.status)
    }

    @Test
    fun gen2EggsStayEggs() {
        val save = load(Game.CRYSTAL)
        val egg = SyntheticSaves.pk2(species = 175)
        egg.isEgg = true
        save.setPokemon(SlotRef.Party(0), egg)
        assertFalse(save.isSeen(175)) // eggs don't register in the Pokedex
        val out = save.export()
        assertEquals(0xFD, out.u8(0x2865 + 1))
        val back = SaveFile.load(out).getPokemon(SlotRef.Party(0)) as Pk2
        assertTrue(back.isEgg)
        assertTrue(save.party[0].isEgg)
    }

    // ---- pokedex ---------------------------------------------------------------------------------------------

    @Test
    fun storingAPokemonMarksItSeenAndCaught() {
        for (game in listOf(Game.RED_BLUE, Game.GOLD_SILVER)) {
            val save = load(game)
            assertFalse(save.isSeen(25))
            save.setPokemon(SlotRef.Party(0), SyntheticSaves.pokemon(save, 25))
            assertTrue(save.isSeen(25))
            assertTrue(save.isCaught(25))
            save.setPokemon(SlotRef.Party(1), SyntheticSaves.pokemon(save, 26), updatePokedex = false)
            assertFalse(save.isSeen(26))
            assertFalse(save.isCaught(26))
            val reloaded = SaveFile.load(save.export())
            assertTrue(reloaded.isCaught(25))
            assertFalse(reloaded.isCaught(26))
        }
    }

    @Test
    fun dexBitsSitWhereTheOffsetsSay() {
        val save = load(Game.RED_BLUE)
        save.setPokemon(SlotRef.Party(0), SyntheticSaves.pokemon(save, 9)) // bit 8 -> second byte, bit 0
        val out = save.export()
        assertEquals(1, out.u8(0x25A3 + 1) and 1) // caught
        assertEquals(1, out.u8(0x25B6 + 1) and 1) // seen
    }

    @Test
    fun catchingUnownUnlocksTheLetters() {
        val save = load(Game.CRYSTAL)
        save.setPokemon(SlotRef.Party(0), SyntheticSaves.pokemon(save, 201))
        val out = save.export()
        for (i in 1..26) assertEquals(i, out.u8(0x2A47 + 0x1F + i))
        assertEquals(1, out.u8(0x2A47 + 0x1F + 28)) // first seen: A
    }

    // ---- inventory -------------------------------------------------------------------------------------------

    @Test
    fun gen1BagRoundTrip() {
        val save = load(Game.RED_BLUE)
        assertEquals(listOf(PocketType.ITEMS, PocketType.PC_ITEMS), save.inventory.pockets.map { it.type })
        save.setPocket(PocketType.ITEMS, listOf(ItemStack(20, 5), ItemStack(4, 10), ItemStack(2, 0)))
        save.setPocket(PocketType.PC_ITEMS, listOf(ItemStack(1, 1)))
        val out = save.export()
        assertEquals(listOf(2, 20, 5, 4, 10, 0xFF), (0x25C9..0x25CE).map { out.u8(it) })
        assertEquals(listOf(1, 1, 1, 0xFF), (0x27E6..0x27E9).map { out.u8(it) })
        val reloaded = SaveFile.load(out)
        assertEquals(listOf(ItemStack(20, 5), ItemStack(4, 10)), reloaded.inventory.pocket(PocketType.ITEMS)!!.items)
        assertEquals(20, reloaded.inventory.pocket(PocketType.ITEMS)!!.capacity)
        assertEquals(50, reloaded.inventory.pocket(PocketType.PC_ITEMS)!!.capacity)
        assertTrue(reloaded.checksumsValid)
    }

    @Test
    fun gen1BagRejectsTooManyStacksAndMissingPockets() {
        val save = load(Game.RED_BLUE)
        expectInvalid { save.setPocket(PocketType.ITEMS, (1..21).map { ItemStack(it, 1) }) }
        expectInvalid { save.setPocket(PocketType.KEY_ITEMS, listOf(ItemStack(7, 1))) }
        expectInvalid { save.setPocket(PocketType.ITEMS, listOf(ItemStack(0, 1))) }
        expectInvalid { save.setPocket(PocketType.ITEMS, listOf(ItemStack(5, 300))) }
        assertFalse(save.isModified)
    }

    @Test
    fun gen2BagHasFivePocketsWithTheirOwnFormats() {
        val save = load(Game.CRYSTAL)
        assertEquals(
            listOf(PocketType.TM_HM, PocketType.ITEMS, PocketType.KEY_ITEMS, PocketType.BALLS, PocketType.PC_ITEMS),
            save.inventory.pockets.map { it.type },
        )
        assertEquals(listOf(57, 20, 26, 12, 50), save.inventory.pockets.map { it.capacity })
        save.setPocket(PocketType.ITEMS, listOf(ItemStack(18, 3)))
        save.setPocket(PocketType.KEY_ITEMS, listOf(ItemStack(7, 1), ItemStack(58, 1)))
        save.setPocket(PocketType.BALLS, listOf(ItemStack(5, 99)))
        save.setPocket(PocketType.TM_HM, listOf(ItemStack(191, 1), ItemStack(249, 1)))
        val out = save.export()
        assertEquals(listOf(1, 18, 3, 0xFF), (0x2420..0x2423).map { out.u8(it) })
        assertEquals(listOf(2, 7, 58, 0xFF), (0x244A..0x244D).map { out.u8(it) })
        assertEquals(listOf(1, 5, 99, 0xFF), (0x2465..0x2468).map { out.u8(it) })
        // TM/HM pocket: one count byte per machine in a fixed order (191 = first, 249 = last of 57)
        assertEquals(1, out.u8(0x23E7))
        assertEquals(1, out.u8(0x23E7 + 56))
        val inv = SaveFile.load(out).inventory
        assertEquals(listOf(ItemStack(191, 1), ItemStack(249, 1)), inv.pocket(PocketType.TM_HM)!!.items)
        assertEquals(listOf(ItemStack(7, 1), ItemStack(58, 1)), inv.pocket(PocketType.KEY_ITEMS)!!.items)
        // removing a TM actually clears it (PKHeX would leave the old count behind)
        val again = SaveFile.load(out)
        again.setPocket(PocketType.TM_HM, listOf(ItemStack(191, 1)))
        assertEquals(0, again.export().u8(0x23E7 + 56))
        expectInvalid { again.setPocket(PocketType.TM_HM, listOf(ItemStack(18, 1))) } // not a machine
    }

    @Test
    fun pocketMetadata() {
        val crystal = load(Game.CRYSTAL).inventory
        val items = crystal.pocket(PocketType.ITEMS)!!
        assertEquals(99, items.maxCount)
        assertTrue(items.validItems.isNotEmpty())
        assertEquals(22, crystal.pocket(PocketType.KEY_ITEMS)!!.validItems.size) // 18 + 4 Crystal-only
        assertEquals(18, load(Game.GOLD_SILVER).inventory.pocket(PocketType.KEY_ITEMS)!!.validItems.size)
        // HMs (items 243..249) come as a single copy, TMs as up to 99
        val machines = crystal.pocket(PocketType.TM_HM)!!
        assertEquals(57, machines.validItems.size)
        assertEquals(1, machines.maxCountOf(243))
        assertEquals(1, machines.maxCountOf(249))
        assertEquals(99, machines.maxCountOf(191))
        assertEquals(99, items.maxCountOf(18))
    }

    // ---- text language -------------------------------------------------------------------------------------------

    @Test
    fun frenchTextNeedsTheFrenchTable() {
        val bytes = SyntheticSaves.blank(Game.CRYSTAL)
        val french = SaveFile.load(bytes, language = LanguageId.FRENCH)
        assertEquals(LanguageId.FRENCH, french.language)
        french.updateTrainer(french.trainer.copy(name = "Leçon"))
        val out = french.export()
        assertEquals("Leçon", SaveFile.load(out, language = LanguageId.FRENCH).trainer.name)
        // the English table has no cedilla c: the same byte decodes as another letter
        assertEquals("LeÁon", SaveFile.load(out, language = LanguageId.ENGLISH).trainer.name)
    }

    @Test
    fun languageIsGuessedFromDefaultNicknames() {
        val save = load(Game.CRYSTAL)
        assertEquals(LanguageId.ENGLISH, save.language) // nothing to go on
        for ((i, species) in listOf(1, 4, 7).withIndex()) {
            val pk = SyntheticSaves.pk2(species = species)
            pk.setNotNicknamed(LanguageId.FRENCH)
            save.setPokemon(SlotRef.Party(i), pk)
        }
        val reloaded = SaveFile.load(save.export())
        assertEquals(LanguageId.FRENCH, reloaded.language)
        assertEquals(listOf("BULBIZARRE", "SALAMECHE", "CARAPUCE"), reloaded.party.map { it.nickname })
        assertTrue(reloaded.party.none { it.isNicknamed })
        // an explicit language wins over the guess
        assertEquals(LanguageId.GERMAN, SaveFile.load(save.export(), language = LanguageId.GERMAN).language)
    }

    @Test
    fun japaneseSavesAreAlwaysJapanese() {
        for ((game, region, size) in all.filter { it.second == Region.JAPANESE }) {
            assertEquals(LanguageId.JAPANESE, load(game, region, size).language)
        }
    }

    @Test
    fun boxNamesAreDefaultsForGen1AndStoredForGen2() {
        assertEquals("Box 1", load(Game.RED_BLUE).boxName(0))
        assertEquals("Box 12", load(Game.RED_BLUE).boxName(11))
        assertEquals("ボックス3", load(Game.RED_BLUE, Region.JAPANESE).boxName(2))
        // Gen 2 stores names at BoxNames: 9 bytes each
        val bytes = SyntheticSaves.blank(Game.CRYSTAL)
        val encoded = byteArrayOf(0x81.toByte(), 0x8E.toByte(), 0x97.toByte(), 0x50, 0x50, 0x50, 0x50, 0x50, 0x50) // "BOX"
        encoded.copyInto(bytes, 0x2703 + 9) // box index 1
        assertEquals("BOX", SaveFile.load(bytes).boxName(1))
        assertEquals(14, SaveFile.load(bytes).boxes.size)
    }

    @Test
    fun trashBytesInUntouchedPokemonSurviveEdits() {
        // Names keep whatever follows their terminator; an edit of another slot must not disturb them.
        val save = load(Game.CRYSTAL)
        val a = SyntheticSaves.pk2(species = 25)
        a.nickTrash[7] = 0x41; a.nickTrash[8] = 0x42; a.nickTrash[10] = 0x43
        a.otTrash[9] = 0x44
        save.setPokemon(SlotRef.Party(0), a)
        save.setPokemon(SlotRef.Party(1), SyntheticSaves.pk2(species = 4))
        val reloaded = SaveFile.load(save.export())
        val back = reloaded.getPokemon(SlotRef.Party(0)) as GbPokemon
        assertArrayEquals(a.nickTrash, back.nickTrash)
        assertArrayEquals(a.otTrash, back.otTrash)
        reloaded.setPokemon(SlotRef.Party(1), (reloaded.getPokemon(SlotRef.Party(1)) as Pk2).also { it.level = 33 })
        val again = SaveFile.load(reloaded.export()).getPokemon(SlotRef.Party(0)) as GbPokemon
        assertArrayEquals(a.nickTrash, again.nickTrash)
        assertArrayEquals(a.otTrash, again.otTrash)
    }
}

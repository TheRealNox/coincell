package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.data.Gen3Util
import org.noxeng.coincell.pkcore.data.SpeciesConverter
import org.noxeng.coincell.pkcore.data.SpeciesName
import org.noxeng.coincell.pkcore.gba.Pk3
import org.noxeng.coincell.pkcore.gba.Sav3
import org.noxeng.coincell.pkcore.text.LanguageId
import org.noxeng.coincell.pkcore.text.StringConverter3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Generation 3 port: entity encryption, text, detection, and save edits on synthetic saves (no personal files). */
class Gen3Test {
    private fun newPk(species: Int, level: Int = 50, pid: Long = 0x12345678L): Pk3 = Pk3().apply {
        this.pid = pid
        this.species = species
        trainerId = 12345
        secretId = 54321
        language = LanguageId.ENGLISH
        resetNickname()
        otName = "ASH"
        this.level = level
        setMove(0, 33)
        setMovePp(0, getMaxPp(33, 0))
        ball = 4
        originGame = 1
        metLevel = level
    }

    // ---- entity --------------------------------------------------------------------------------------------------------

    @Test
    fun encryptionRoundTripsForEveryBlockOrder() {
        val rnd = Random(3)
        for (sv in 0 until 24) {
            val pk = Pk3()
            rnd.nextBytes(pk.data)
            pk.pid = sv.toLong() + 24L * rnd.nextInt(1, 1000)
            val plain = pk.data.copyOf()
            val stored = pk.encryptedStored()
            val back = ByteArray(Pk3.PARTY_SIZE)
            stored.copyInto(back)
            Pk3.decrypt(back)
            // The checksum was refreshed and the bad-egg flag cleared before encrypting; everything else round-trips.
            assertArrayEquals("sv $sv", pk.data.copyOf(Pk3.STORED_SIZE), back.copyOf(Pk3.STORED_SIZE))
            assertEquals(plain.copyOfRange(0x20, 0x50).toList(), back.copyOfRange(0x20, 0x50).toList())
        }
    }

    @Test
    fun encryptedSlotsReadBackWithAValidChecksum() {
        val pk = newPk(260) // Swampert
        val raw = pk.encryptedParty()
        assertTrue(Pk3.isPresent(raw, 0))
        val read = Pk3.fromEncrypted(raw, 0, Pk3.PARTY_SIZE)
        assertTrue(read.checksumValidWhenRead)
        assertTrue(read.contentEquals(pk))
        raw[0x30] = (raw[0x30] + 1).toByte()
        assertFalse(Pk3.fromEncrypted(raw, 0, Pk3.PARTY_SIZE).checksumValidWhenRead)
        assertFalse(Pk3.isPresent(ByteArray(Pk3.STORED_SIZE), 0))
    }

    @Test
    fun speciesIdsRoundTrip() {
        for (s in 1..386) assertEquals(s, SpeciesConverter.getNational3(SpeciesConverter.getInternal3(s)))
        assertEquals(277, SpeciesConverter.getInternal3(252)) // Treecko
        assertEquals(410, SpeciesConverter.getInternal3(386)) // Deoxys
        assertEquals(409, SpeciesConverter.getInternal3(385)) // Jirachi
        for (raw in 252..276) assertEquals(0, SpeciesConverter.getNational3(raw)) // unused "?" slots
        assertEquals(251, SpeciesConverter.getNational3(251))
    }

    @Test
    fun derivedValuesFollowThePid() {
        assertEquals(3, Gen3Util.nature(28))
        assertTrue(Gen3Util.isShiny(pid = (12345L xor 54321L) shl 16, tid = 12345, sid = 54321))
        assertFalse(Gen3Util.isShiny(pid = 0x12345678, tid = 12345, sid = 54321))
        assertEquals(0, Gen3Util.unownForm(0))
        assertEquals(1 to 2, Gen3Util.natureModification(1)) // Lonely: +Atk -Def
        assertNull(Gen3Util.natureModification(0)) // Hardy
        assertEquals(15, Gen3Util.hiddenPowerType(IntArray(6) { 31 })) // Dark
    }

    @Test
    fun statsUseNatureAndShedinjaHasOneHp() {
        val pk = newPk(292, level = 30) // Shedinja
        assertEquals(1, pk.calculateStats().hp)
        val swampert = newPk(260, level = 100, pid = 1) // Lonely
        for (i in 0 until 6) swampert.setIv(i, 31)
        val s = swampert.calculateStats()
        // Base 100/110/90/60/85/90, 31 IVs, 0 EVs, level 100: 2*B+31+5 (+110 HP), Atk*1.1, Def*0.9.
        assertEquals(341, s.hp)
        assertEquals((2 * 110 + 31 + 5) * 11 / 10, s.attack)
        assertEquals((2 * 90 + 31 + 5) * 9 / 10, s.defense)
        assertEquals(2 * 60 + 31 + 5, s.speed)
    }

    @Test
    fun eggsUseTheJapaneseEggName() {
        val pk = newPk(258, level = 5)
        pk.isEgg = true
        assertTrue(pk.isEgg)
        assertEquals(LanguageId.JAPANESE, pk.language)
        assertEquals("タマゴ", pk.nickname)
        assertEquals(SpeciesName.EGG_NAME_GEN3, pk.summary().nickname)
    }

    @Test
    fun defaultNamesFollowTheGen3Rules() {
        assertEquals("FARFETCH'D", SpeciesName.getGeneration3(83, LanguageId.ENGLISH))
        assertEquals("LEVIATOR", SpeciesName.getGeneration3(130, LanguageId.FRENCH))
        assertEquals("MR. MIME", SpeciesName.getGeneration3(122, LanguageId.ENGLISH))
        val pk = newPk(130)
        pk.language = LanguageId.FRENCH
        pk.resetNickname()
        assertEquals("LEVIATOR", pk.nickname)
        assertFalse(pk.isNicknamed)
        pk.nickname = "Bob"
        assertTrue(pk.isNicknamed)
    }

    // ---- text ----------------------------------------------------------------------------------------------------------

    @Test
    fun textRoundTrips() {
        val buf = ByteArray(10)
        for (s in listOf("SACHA", "Léviator", "Nidoran♂", "Nidoran♀", "Mr. Mime", "ÄÖÜäöü")) {
            StringConverter3.setString(buf, 0, buf.size, s, 10, LanguageId.FRENCH)
            assertEquals(s, StringConverter3.getString(buf, 0, buf.size, LanguageId.FRENCH))
        }
        StringConverter3.setString(buf, 0, buf.size, "ポチエナ", 10, LanguageId.JAPANESE)
        assertEquals("ポチエナ", StringConverter3.getString(buf, 0, buf.size, LanguageId.JAPANESE))
        // Quotes depend on the language; unknown characters end the string.
        StringConverter3.setString(buf, 0, buf.size, "«A»", 10, LanguageId.FRENCH)
        assertEquals("“A”", StringConverter3.getString(buf, 0, buf.size, LanguageId.ENGLISH))
        assertFalse(StringConverter3.canEncode("A€", LanguageId.ENGLISH))
        assertTrue(StringConverter3.canEncode("A'B", LanguageId.ENGLISH))
    }

    // ---- detection -----------------------------------------------------------------------------------------------------

    @Test
    fun detectsTheThreeGameFamilies() {
        for (game in listOf(Game.RUBY_SAPPHIRE, Game.EMERALD, Game.FIRERED_LEAFGREEN)) {
            val bytes = SyntheticSaves3.build(game)
            val save = SaveFile.load(bytes)
            assertEquals(game, save.game)
            assertEquals(3, save.generation)
            assertEquals(Region.INTERNATIONAL, save.region)
            assertEquals(LanguageId.ENGLISH, save.language)
            assertTrue(save.checksumsValid)
            assertFalse(save.compactBoxes)
            assertEquals(14, save.boxCount)
            assertEquals(30, save.boxCapacity)
            assertEquals("ASH", save.trainer.name)
            assertEquals(12345, save.trainer.id)
            assertEquals(54321, save.trainer.secretId)
            assertEquals("BOX 3", save.boxName(2))
            assertArrayEquals(bytes, save.export())
            assertArrayEquals(bytes, (save as Sav3).exportLikePkhex())
        }
        val jp = SaveFile.load(SyntheticSaves3.build(Game.EMERALD, japanese = true, trainerName = "サトシ"))
        assertEquals(Region.JAPANESE, jp.region)
        assertEquals("サトシ", jp.trainer.name)
    }

    @Test
    fun rejectsIncompleteSectorSets() {
        val bytes = SyntheticSaves3.build(Game.EMERALD)
        bytes[3 * 0x1000 + 0xFF4] = 4 // two sectors claim id 4, id 3 is missing
        try {
            SaveFile.load(bytes)
            fail("expected UnsupportedSaveException")
        } catch (_: UnsupportedSaveException) {
        }
    }

    @Test
    fun theNewerSaveSlotIsActive() {
        val bytes = SyntheticSaves3.build(Game.RUBY_SAPPHIRE)
        // Copy slot 0 into slot 1 with a higher counter and another trainer ID: slot 1 must win.
        bytes.copyInto(bytes, 0xE000, 0, 0xE000)
        val small1 = 0xE000
        bytes[small1 + 0xA] = 7
        bytes[small1 + 0xB] = 0
        for (i in 0 until 14) bytes[0xE000 + i * 0x1000 + 0xFFC] = 2
        val c = Sav3.checksum32(bytes, small1, 0xF80)
        bytes[small1 + 0xFF6] = c.toByte()
        bytes[small1 + 0xFF7] = (c shr 8).toByte()
        val save = SaveFile.load(bytes)
        assertEquals(7, save.trainer.id)
        assertTrue(save.checksumsValid)
    }

    // ---- trainer -------------------------------------------------------------------------------------------------------

    @Test
    fun securityKeyMasksMoneyCoinsAndBag() {
        val bytes = SyntheticSaves3.build(Game.FIRERED_LEAFGREEN)
        val save = SaveFile.load(bytes)
        // Zeroed money XOR the key reads as the key.
        assertEquals(SyntheticSaves3.SECURITY_KEY, save.trainer.money)
        save.updateTrainer(save.trainer.copy(money = 123456, coins = 789, rivalName = "GARY", badges = 0b1010_0101, name = "RED"))
        save.setPocket(PocketType.ITEMS, listOf(ItemStack(13, 5))) // Potion
        val out = save.export()
        val again = SaveFile.load(out)
        assertTrue(again.checksumsValid)
        val t = again.trainer
        assertEquals(123456, t.money)
        assertEquals(789, t.coins)
        assertEquals("GARY", t.rivalName)
        assertEquals("RED", t.name)
        assertEquals(0b1010_0101, t.badges)
        assertEquals(listOf(ItemStack(13, 5)), again.inventory.pocket(PocketType.ITEMS)!!.items)
        // Raw: large block starts at sector id 1; money at large 0x290, quantity of the first item at 0x298+0x78+2.
        val money = (out[0x1000 + 0x290].toInt() and 0xFF) or ((out[0x1000 + 0x291].toInt() and 0xFF) shl 8) or
            ((out[0x1000 + 0x292].toInt() and 0xFF) shl 16) or ((out[0x1000 + 0x293].toInt() and 0xFF) shl 24)
        assertEquals(123456 xor SyntheticSaves3.SECURITY_KEY, money)
        val qty = (out[0x1000 + 0x298 + 0x78 + 2].toInt() and 0xFF) or ((out[0x1000 + 0x298 + 0x78 + 3].toInt() and 0xFF) shl 8)
        assertEquals(5 xor (SyntheticSaves3.SECURITY_KEY and 0xFFFF), qty)
        // The PC pocket is not masked.
        again.setPocket(PocketType.PC_ITEMS, listOf(ItemStack(13, 2)))
        val pcOut = again.export()
        assertEquals(2, pcOut[0x1000 + 0x298 + 2].toInt())
    }

    @Test
    fun rubySapphireHaveNoRivalNameAndNoKey() {
        val save = SaveFile.load(SyntheticSaves3.build(Game.RUBY_SAPPHIRE))
        assertNull(save.trainer.rivalName)
        save.updateTrainer(save.trainer.copy(money = 999_999, rivalName = "IGNORED"))
        val again = SaveFile.load(save.export())
        assertEquals(999_999, again.trainer.money)
        assertNull(again.trainer.rivalName)
        assertEquals(listOf(PocketType.ITEMS, PocketType.KEY_ITEMS, PocketType.BALLS, PocketType.TM_HM, PocketType.BERRIES, PocketType.PC_ITEMS), again.inventory.pockets.map { it.type })
        assertEquals(1, again.inventory.pocket(PocketType.TM_HM)!!.maxCountOf(339)) // HM01
    }

    // ---- slots ---------------------------------------------------------------------------------------------------------

    @Test
    fun boxesKeepGapsAndThePartyStaysPacked() {
        val save = SaveFile.load(SyntheticSaves3.build(Game.EMERALD))
        save.setPokemon(SlotRef.Box(0, 5), newPk(252), updatePokedex = true)
        save.setPokemon(SlotRef.Party(0), newPk(255), updatePokedex = true)
        save.setPokemon(SlotRef.Party(1), newPk(258), updatePokedex = true)
        assertEquals(30, save.boxes[0].slots.size)
        assertEquals(252, save.boxes[0].slots[5]!!.species)
        assertNull(save.boxes[0].slots[0])
        assertEquals(listOf(255, 258), save.party.map { it.species })
        assertTrue(save.isCaught(252) && save.isSeen(252) && save.isCaught(255))

        // Party Pokemon get their stats; box ones lose them.
        assertEquals(save.party[0].stats, save.party[0].storedStats)
        save.movePokemon(SlotRef.Party(0), SlotRef.Box(1, 10))
        assertEquals(listOf(258), save.party.map { it.species })
        assertEquals(0, save.boxes[1].slots[10]!!.storedStats.hp)
        save.movePokemon(SlotRef.Box(0, 5), SlotRef.Party(5)) // to the end of the party
        assertEquals(listOf(258, 252), save.party.map { it.species })
        assertNull(save.boxes[0].slots[5])
        save.movePokemon(SlotRef.Party(0), SlotRef.Party(1)) // swap
        assertEquals(listOf(252, 258), save.party.map { it.species })

        assertEquals(255, (save.removePokemon(SlotRef.Box(1, 10)) as Pk3).species)
        assertNull(save.boxes[1].slots[10])
        save.removePokemon(SlotRef.Party(0))
        assertEquals(listOf(258), save.party.map { it.species })

        assertEquals(SlotRef.Box(save.currentBox, 0), save.addPokemon(newPk(1), updatePokedex = false))

        val again = SaveFile.load(save.export())
        assertTrue(again.checksumsValid)
        assertEquals(listOf(258), again.party.map { it.species })
        assertEquals(1, again.boxes[save.currentBox].slots[0]!!.species)
        assertFalse(again.isCaught(1))
        // Seen flags are mirrored into the large block.
        assertTrue(again.isSeen(252))
    }

    @Test
    fun editedPokemonSurviveExport() {
        val save = SaveFile.load(SyntheticSaves3.build(Game.FIRERED_LEAFGREEN))
        save.setPokemon(SlotRef.Party(0), newPk(6, level = 36), updatePokedex = true)
        val pk = save.getPokemon(SlotRef.Party(0)) as Pk3
        pk.level = 70
        pk.heldItem = 13
        pk.setIv(3, 31)
        pk.setEv(1, 252)
        pk.nickname = "Blaze"
        pk.friendship = 255
        pk.resetPartyStats()
        save.setPokemon(SlotRef.Party(0), pk, updatePokedex = false)
        val again = SaveFile.load(save.export())
        val p = again.party[0]
        assertEquals(70, p.level)
        assertEquals("Blaze", p.nickname)
        assertEquals(13, p.heldItem)
        assertEquals(255, p.friendship)
        assertEquals(31, p.gen3!!.ivs.speed)
        assertEquals(252, p.gen3.evs.attack)
        assertEquals(p.stats, p.storedStats)
        assertFalse(p.gen3.isBadEgg)
    }

    @Test
    fun refusesOtherGenerations() {
        val save = SaveFile.load(SyntheticSaves3.build(Game.EMERALD))
        try {
            save.setPokemon(SlotRef.Party(0), org.noxeng.coincell.pkcore.gb.Pk2(), updatePokedex = false)
            fail("expected InvalidEditException")
        } catch (_: InvalidEditException) {
        }
        try {
            save.setPokemon(SlotRef.Party(2), newPk(1), updatePokedex = false)
            fail("the party is contiguous")
        } catch (_: InvalidEditException) {
        }
    }
}

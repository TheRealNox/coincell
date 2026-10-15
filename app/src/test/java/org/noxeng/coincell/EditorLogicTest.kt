package org.noxeng.coincell

import org.noxeng.coincell.editor.Badges
import org.noxeng.coincell.editor.Change
import org.noxeng.coincell.editor.DexCounts
import org.noxeng.coincell.editor.DvStat
import org.noxeng.coincell.editor.PokemonEdits
import org.noxeng.coincell.editor.PokemonSaveKind
import org.noxeng.coincell.editor.PokemonSaveProbe
import org.noxeng.coincell.editor.SaveDiff
import org.noxeng.coincell.editor.SaveEditor
import org.noxeng.coincell.editor.StatExpStat
import org.noxeng.coincell.editor.TextCheck
import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.gb.Pk1
import org.noxeng.coincell.pkcore.gb.Pk2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Validated Pokemon edits, the change summary and the entry-point probe: pure logic, no Android. */
class EditorLogicTest {
    private fun refused(block: () -> Unit): String {
        try {
            block()
        } catch (e: InvalidEditException) {
            return e.message!!
        }
        fail("expected the edit to be refused")
        return ""
    }

    private fun pk2() = EditorTestSaves.pk2(157, 37, "TYPHLOSION")

    // ---- validation through pkcore ------------------------------------------------------------------------------------

    @Test
    fun level_exp_species_ranges() {
        val pk = pk2()
        PokemonEdits.setLevel(pk, 50)
        assertEquals(50, pk.level)
        assertEquals(50, pk.storedLevel)
        assertEquals("The level must be between 1 and 100.", refused { PokemonEdits.setLevel(pk, 0) })
        assertEquals("The level must be between 1 and 100.", refused { PokemonEdits.setLevel(pk, 101) })
        refused { PokemonEdits.setExp(pk, -1) }
        refused { PokemonEdits.setExp(pk, PokemonEdits.maxExp(2, 157) + 1) }
        PokemonEdits.setExp(pk, 1000)
        assertEquals(1000, pk.exp)
        refused { PokemonEdits.setSpecies(pk, 252) }
        assertTrue(refused { PokemonEdits.setSpecies(Pk1(false), 152) }.contains("Generation 1"))
    }

    @Test
    fun speciesChange_keepsACustomNickname_resetsADefaultOne() {
        val named = EditorTestSaves.pk2(157, 37, "BOOM")
        PokemonEdits.setSpecies(named, 3)
        assertEquals("BOOM", named.nickname)
        val plain = EditorTestSaves.pk2(159, 20, "CROCONAW")
        plain.setNotNicknamed(2)
        PokemonEdits.setSpecies(plain, 160)
        assertEquals("FERALIGATR", plain.nickname)
    }

    @Test
    fun moves_fillFromTheTop_compact_andKeepOne() {
        val pk = pk2()
        assertEquals(listOf(33, 98, 0, 0), (0..3).map { pk.getMove(it) })
        PokemonEdits.setMove(pk, 3, 85) // asked for slot 4: lands in the first free slot
        assertEquals(listOf(33, 98, 85, 0), (0..3).map { pk.getMove(it) })
        assertEquals(pk.getMaxPp(85, 0), pk.getMovePp(2))
        PokemonEdits.clearMove(pk, 0)
        assertEquals(listOf(98, 85, 0, 0), (0..3).map { pk.getMove(it) })
        PokemonEdits.clearMove(pk, 0)
        assertEquals("A Pokemon needs at least one move.", refused { PokemonEdits.clearMove(pk, 0) })
        refused { PokemonEdits.setMove(pk, 0, 252) }
        refused { PokemonEdits.setMove(Pk1(false).also { it.species = 25 }, 0, 200) } // Gen 2 move in a Gen 1 Pokemon
    }

    @Test
    fun pp_andPpUps() {
        val pk = pk2()
        val base = pk.getMaxPp(33, 0)
        PokemonEdits.setPp(pk, 0, 5)
        assertEquals(5, pk.getMovePp(0))
        refused { PokemonEdits.setPp(pk, 0, base + 1) }
        PokemonEdits.setPp(pk, 0, base)
        PokemonEdits.setPpUps(pk, 0, 3)
        assertEquals(3, pk.getMovePpUps(0))
        assertEquals(pk.getMaxPp(33, 3), pk.getMovePp(0)) // was full, stays full
        refused { PokemonEdits.setPpUps(pk, 0, 4) }
        PokemonEdits.setPp(pk, 0, 10)
        PokemonEdits.setPpUps(pk, 0, 0)
        assertEquals(10, pk.getMovePp(0)) // not above the new maximum
        refused { PokemonEdits.setPp(pk, 3, 1) } // empty slot
    }

    @Test
    fun dvs_statExp_andGen2OnlyFields() {
        val pk = pk2()
        PokemonEdits.setDv(pk, DvStat.ATTACK, 3)
        PokemonEdits.setStatExp(pk, StatExpStat.SPECIAL, 65535)
        assertEquals(3, pk.ivAttack)
        assertEquals(65535, pk.evSpecial)
        refused { PokemonEdits.setDv(pk, DvStat.SPEED, 16) }
        refused { PokemonEdits.setStatExp(pk, StatExpStat.HP, 65536) }
        PokemonEdits.setHeldItem(pk, 234)
        assertEquals(234, pk.heldItem)
        PokemonEdits.setFriendship(pk, 255)
        PokemonEdits.setMetLevel(pk, 63)
        refused { PokemonEdits.setMetLevel(pk, 64) }
        refused { PokemonEdits.setMetLocation(pk, 128) }
        val gen1 = Pk1(false).also { it.species = 25 }
        assertTrue(refused { PokemonEdits.setHeldItem(gen1, 1) }.contains("Generation 2"))
    }

    @Test
    fun makeShiny_setsTheShinyDvs_andKeepsTheGender_orSaysWhyNot() {
        var done = 0
        var impossible = 0
        for (species in listOf(157, 25, 133, 201, 129, 113)) {
            for (attack in 0..15) {
                val pk = EditorTestSaves.pk2(species, 20, "X")
                pk.ivAttack = attack
                pk.ivDefense = 3
                pk.ivSpeed = 4
                pk.ivSpecial = 5
                val gender = pk.gender
                try {
                    PokemonEdits.makeShiny(pk)
                    assertTrue("species $species atk $attack", pk.isShiny)
                    assertEquals("gender kept for $species atk $attack", gender, pk.gender)
                    done++
                } catch (e: InvalidEditException) {
                    // Only when no shiny Attack DV keeps the gender (e.g. a female of a 7:1 male species: Atk would have to be 0 or 1).
                    impossible++
                    assertTrue(e.message!!, e.message!!.contains("can't exist in Generation 2"))
                    assertFalse(pk.isShiny)
                }
            }
        }
        assertTrue(done > 60)
        assertTrue(impossible > 0) // female Typhlosion with a low Attack DV
        assertTrue(refused { PokemonEdits.makeShiny(Pk1(false).also { it.species = 25 }) }.contains("Generation 2"))
    }

    @Test
    fun nicknameAndNameValidation_usesTheSaveCharset() {
        val pk = pk2()
        PokemonEdits.setNickname(pk, "TIFOUQUE")
        assertEquals("TIFOUQUE", pk.nickname)
        assertTrue(refused { PokemonEdits.setNickname(pk, "ABCDEFGHIJK") }.contains("at most 10"))
        assertTrue(refused { PokemonEdits.setNickname(pk, "NOあ") }.contains("can't store"))
        PokemonEdits.setNickname(pk, "") // back to the species name
        assertFalse(pk.isNicknamed)
        refused { PokemonEdits.setOtName(pk, "") }
        assertNull(TextCheck.check("RED", 7, 1, 2, false, "name"))
        assertNotNull(TextCheck.check("TOOLONGNAME", 7, 1, 2, false, "name"))
        assertTrue(TextCheck.allowedCharacters(2, 3, false).contains('é'))
        assertFalse(TextCheck.allowedCharacters(2, 2, false).contains('あ'))
    }

    @Test
    fun finalize_refreshesPartyStats_keepsStatus_andFullHpStaysFull() {
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        val before = editor.snapshot().party[0]
        editor.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 60) }
        val after = editor.snapshot().party[0]
        assertEquals(60, after.level)
        assertTrue(after.storedStats.hp > before.storedStats.hp)
        assertEquals(after.storedStats.hp, after.currentHp) // it was full
        editor.edit(SlotRef.Party(0)) { it.currentHp = 10; it.status = 8 }
        editor.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 70) }
        val hurt = editor.snapshot().party[0]
        assertEquals(10, hurt.currentHp)
        assertEquals(8, hurt.status)
    }

    // ---- change summary ---------------------------------------------------------------------------------------------

    @Test
    fun diff_levelAndMoney_matchesTheDocumentedNote() {
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        editor.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) }
        editor.updateTrainer(editor.snapshot().trainer.copy(money = 999_999))
        assertEquals("Edited: Typhlosion L37→L50; money 3000→999999", SaveDiff.note(editor.changes(), 140))
    }

    @Test
    fun diff_describesSeveralFieldsOfOnePokemon() {
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        editor.edit(SlotRef.Party(1)) {
            PokemonEdits.setLevel(it, 30)
            PokemonEdits.setMove(it, 1, 85)
            PokemonEdits.setDv(it, DvStat.SPEED, 1)
            PokemonEdits.setHeldItem(it, 234)
        }
        val texts = editor.changes().map { it.text }
        assertEquals(1, texts.size)
        assertTrue(texts[0], texts[0].startsWith("Pidgey: L12→L30, moves, DVs, item none→"))
    }

    @Test
    fun diff_speciesChangeAndNickname() {
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        editor.edit(SlotRef.Box(0, 0)) {
            PokemonEdits.setSpecies(it, 20)
            PokemonEdits.setNickname(it, "RATICATE")
        }
        // Raticate is new to the Pokedex: the species change marks it seen and caught, and the summary says so.
        assertEquals(listOf("Raticate: Rattata→Raticate, nickname", "Pokedex caught 3→4, seen 3→4"), editor.changes().map { it.text })
    }

    @Test
    fun diff_removalDoesNotReportTheShiftedPokemon() {
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        editor.addPokemon(SlotRef.Box(0, 5), 25)
        editor.addPokemon(SlotRef.Box(0, 5), 26)
        // start over from here as the baseline would be the loaded state; remove the first box Pokemon
        editor.removePokemon(SlotRef.Box(0, 0))
        val texts = editor.changes().map { it.text }
        assertTrue(texts.toString(), texts.contains("removed Rattata L4 (box 1)"))
        assertTrue(texts.toString(), texts.any { it.startsWith("added Pikachu") })
        assertTrue(texts.toString(), texts.any { it.startsWith("added Raichu") })
        assertTrue(texts.toString(), texts.contains("Pokedex caught 3→5, seen 3→5"))
        assertEquals(texts.toString(), 4, texts.size)
    }

    @Test
    fun dex_countsSeenAndCaughtOutOfTheGeneration() {
        val crystal = SaveEditor.open(EditorTestSaves.crystal()).snapshot().dex
        assertEquals(DexCounts(seen = 3, caught = 3, total = 251), crystal)
        val blue = SaveEditor.open(EditorTestSaves.blue()).snapshot().dex
        assertEquals(DexCounts(seen = 1, caught = 1, total = 151), blue)
        // an edit that does not touch the species leaves the Pokedex alone
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        editor.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) }
        assertEquals(crystal, editor.snapshot().dex)
        assertTrue(editor.changes().none { it.text.startsWith("Pokedex") })
    }

    @Test
    fun diff_bagAndRevertToEmpty() {
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        val items = editor.snapshot().inventory.pocket(org.noxeng.coincell.pkcore.PocketType.ITEMS)!!.items
        editor.setPocket(org.noxeng.coincell.pkcore.PocketType.ITEMS, listOf(org.noxeng.coincell.pkcore.ItemStack(items[0].id, 99)))
        assertEquals(listOf("Items: Potion 5→99"), editor.changes().map { it.text })
        editor.setPocket(org.noxeng.coincell.pkcore.PocketType.ITEMS, items)
        assertTrue(editor.changes().isEmpty())
        editor.setPocket(org.noxeng.coincell.pkcore.PocketType.ITEMS, emptyList())
        assertEquals(listOf("Items: -Potion"), editor.changes().map { it.text })
    }

    @Test
    fun note_isCappedAndSaysHowManyMore() {
        val many = List(30) { Change(Change.Area.POKEMON, "Pokemon number $it L1→L2") }
        val note = SaveDiff.note(many, 140)
        assertTrue(note.length <= 140)
        assertTrue(note, note.startsWith("Edited: Pokemon number 0 L1→L2; "))
        assertTrue(note, note.contains(" more)"))
        assertEquals("", SaveDiff.note(emptyList(), 140))
        assertEquals("Edited: a", SaveDiff.note(listOf(Change(Change.Area.TRAINER, "a")), 140))
        assertTrue(SaveDiff.note(listOf(Change(Change.Area.TRAINER, "x".repeat(300))), 140).length <= 140)
    }

    // ---- editor model ------------------------------------------------------------------------------------------------

    @Test
    fun exportChecked_isStableWhenUnchanged_andReloadsWhenEdited() {
        val bytes = EditorTestSaves.crystal()
        val editor = SaveEditor.open(bytes)
        assertTrue(bytes.contentEquals(editor.exportChecked()))
        editor.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 80) }
        val out = editor.exportChecked()
        assertEquals(bytes.size, out.size)
        assertFalse(bytes.contentEquals(out))
        editor.rebase(out)
        assertTrue(editor.changes().isEmpty())
        editor.revertAll()
        assertEquals(80, editor.snapshot().party[0].level)
    }

    @Test
    fun trainerEdits_andBadgeLayout() {
        val editor = SaveEditor.open(EditorTestSaves.crystal())
        val t = editor.snapshot().trainer
        editor.updateTrainer(t.copy(name = "ASH", money = 123_456, coins = 777, badges = 0x00FF))
        val after = editor.snapshot().trainer
        assertEquals("ASH", after.name)
        assertEquals(123_456, after.money)
        assertEquals(777, after.coins)
        assertEquals(8, after.badgeCount)
        assertEquals(16, Badges.list(2, 2).size)
        assertEquals(8, Badges.list(1, 2).size)
        assertEquals("Zéphyr", Badges.list(2, 3).first().name)
        assertEquals("Boulder", Badges.list(1, 2).first().name)
        val texts = editor.changes().map { it.text }
        assertTrue(texts.toString(), texts.containsAll(listOf("name GOLD→ASH", "money 3000→123456", "coins 0→777", "badges 0→8")))
    }

    // ---- entry-point probe ------------------------------------------------------------------------------------------

    @Test
    fun probe_gen12SaveIsRecognised_withTheChipText() {
        val kind = PokemonSaveProbe.probe(EditorTestSaves.crystalGame, EditorTestSaves.crystal())
        kind as PokemonSaveKind.Gen12
        assertEquals(Game.CRYSTAL, kind.info.game)
        assertEquals("Pokemon save · Gen 2 · Crystal English", kind.info.chipText)
        val blue = PokemonSaveProbe.probe(EditorTestSaves.blueGame, EditorTestSaves.blue()) as PokemonSaveKind.Gen12
        assertEquals("Pokemon save · Gen 1 · Red/Blue English", blue.info.chipText)
    }

    @Test
    fun probe_unreadableOrMissingBytes_isNotSupported_neverAnError() {
        assertEquals(PokemonSaveKind.NotSupported, PokemonSaveProbe.probe(EditorTestSaves.crystalGame, ByteArray(0x8000) { 0x55 }))
        assertEquals(PokemonSaveKind.NotSupported, PokemonSaveProbe.probe(EditorTestSaves.crystalGame, ByteArray(10)))
        assertEquals(PokemonSaveKind.NotSupported, PokemonSaveProbe.probe(EditorTestSaves.crystalGame, null))
    }

    @Test
    fun probe_gbaPokemonGames_sayTheEditorComesLater() {
        assertEquals(PokemonSaveKind.GbaLater, PokemonSaveProbe.byGame(EditorTestSaves.sapphire))
        for (code in listOf("AXVE", "AXPF", "BPRE", "BPGD", "BPEE")) {
            assertEquals(code, PokemonSaveKind.GbaLater, PokemonSaveProbe.byGame(EditorTestSaves.sapphire.copy(gameCode = code)))
        }
        assertEquals(PokemonSaveKind.NotSupported, PokemonSaveProbe.byGame(EditorTestSaves.sapphire.copy(gameCode = "AMKE", title = "MARIOKART")))
        assertNull(PokemonSaveProbe.byGame(EditorTestSaves.crystalGame)) // GB / GBC: the bytes decide
    }

    @Test
    fun realSaves_areRecognisedWithTheirLanguage() {
        val blue = EditorTestSaves.realBlueOrSkip()
        assertEquals("Pokemon save · Gen 1 · Red/Blue French", (PokemonSaveProbe.probeBytes(blue) as PokemonSaveKind.Gen12).info.chipText)
        val crystal = EditorTestSaves.realCrystalOrSkip()
        assertEquals("Pokemon save · Gen 2 · Crystal French", (PokemonSaveProbe.probeBytes(crystal) as PokemonSaveKind.Gen12).info.chipText)
    }

    @Test
    fun newPokemon_isCompleteForBothGenerations() {
        for (bytes in listOf(EditorTestSaves.crystal(), EditorTestSaves.blue())) {
            val editor = SaveEditor.open(bytes)
            val pk = editor.newPokemon(25, 5)
            assertEquals(25, pk.species)
            assertEquals(5, pk.level)
            assertEquals(33, pk.getMove(0))
            assertTrue(pk.getMovePp(0) > 0)
            assertEquals("GOLD".takeIf { editor.generation == 2 } ?: "RED", pk.otName)
            if (pk is Pk2) assertEquals(70, pk.friendship)
        }
    }
}

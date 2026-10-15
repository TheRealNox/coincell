package org.noxeng.coincell

import org.noxeng.coincell.editor.Badges
import org.noxeng.coincell.editor.Pickers
import org.noxeng.coincell.editor.PokemonEdits
import org.noxeng.coincell.editor.PokemonSaveKind
import org.noxeng.coincell.editor.PokemonSaveProbe
import org.noxeng.coincell.editor.SaveEditor
import org.noxeng.coincell.editor.Stat3
import org.noxeng.coincell.library.DisplayPlatform
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.ImportGuesses
import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.Gender
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.text.LanguageId
import org.noxeng.coincell.pkcore.text.StringConverter3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** The editor model on Generation 3 saves: a synthetic Emerald (no personal file needed) and the user's French Sapphire. */
class Gen3EditorTest {
    private val emeraldFr = GameInfo("AGB", "POKEMON EMER", "AGB_POKEMON-EMER_BPEF_00", "GBA", null, 0, 0, "BPEF")

    private fun open(bytes: ByteArray = Gen3Saves.emerald(), game: GameInfo? = emeraldFr) = SaveEditor.open(bytes, game, Random(7))

    private fun fails(block: () -> Unit) {
        try {
            block()
            fail("expected InvalidEditException")
        } catch (_: InvalidEditException) {
        }
    }

    // ---- probe / import ------------------------------------------------------------------------------------------------

    @Test
    fun probe_gen3SaveIsEditable_withTheLanguageOfTheGameCode() {
        val kind = PokemonSaveProbe.probe(emeraldFr, Gen3Saves.emerald()) as PokemonSaveKind.Editable
        assertEquals(Game.EMERALD, kind.info.game)
        assertEquals("Pokemon save · Gen 3 · Emerald French", kind.info.chipText)
        assertEquals(LanguageId.ENGLISH, PokemonSaveProbe.languageHint(emeraldFr.copy(gameCode = "BPEE")))
        assertEquals(LanguageId.GERMAN, PokemonSaveProbe.languageHint(emeraldFr.copy(gameCode = "BPRD")))
        assertNull(PokemonSaveProbe.languageHint(EditorTestSaves.crystalGame))
        // An imported file (no game code) still opens; the language then comes from the Pokemon (none here: English).
        val imported = PokemonSaveProbe.probe(emeraldFr.copy(gameCode = null), Gen3Saves.emerald()) as PokemonSaveKind.Editable
        assertEquals(LanguageId.ENGLISH, imported.info.language)
        assertEquals(PokemonSaveKind.NotSupported, PokemonSaveProbe.probe(emeraldFr, ByteArray(0x20000)))
    }

    @Test
    fun importGuess_namesAGen3SaveAndPicksGba() {
        val withFooter = Gen3Saves.emerald() + ByteArray(16)
        val guess = ImportGuesses.of("whatever.sav", withFooter)
        assertEquals(DisplayPlatform.GBA, guess.platform)
        assertEquals("Pokemon Emerald (English)", guess.name)
    }

    // ---- adding, moving, removing --------------------------------------------------------------------------------------

    @Test
    fun newPokemon_isOwnedByTheTrainer_withPerfectIvs() {
        val ed = open()
        assertEquals(LanguageId.FRENCH, ed.language)
        val placed = ed.addPokemon(SlotRef.Box(0, 7), 258) // Mudkip into an empty slot of a gappy box
        assertEquals(SlotRef.Box(0, 7), placed)
        val p = ed.snapshot().pokemonAt(placed)!!
        val g = p.gen3!!
        assertEquals("GOBOU", p.nickname) // French default name
        assertFalse(p.isNicknamed)
        assertEquals("BRENDAN", p.otName)
        assertEquals(12345, p.trainerId)
        assertEquals(54321, g.secretId)
        assertEquals(LanguageId.FRENCH, g.language)
        assertEquals(List(6) { 31 }, g.ivs.toList())
        assertFalse(p.isShiny)
        assertEquals(3, g.originGame) // Emerald, from the game code
        assertEquals(4, g.ball)
        assertEquals(5, p.level)
        assertTrue(ed.snapshot().dex.caught >= 1)

        // The party fills from the start whatever slot was tapped.
        assertEquals(SlotRef.Party(0), ed.addPokemon(SlotRef.Party(4), 255))
        val party = ed.snapshot().party[0]
        assertEquals(party.stats, party.storedStats)
        ed.exportChecked()
    }

    @Test
    fun boxSlotsKeepGaps_whenMovingAndRemoving() {
        val ed = open()
        ed.addPokemon(SlotRef.Party(0), 252)
        ed.addPokemon(SlotRef.Party(1), 255)
        ed.addPokemon(SlotRef.Box(2, 10), 258)
        ed.movePokemon(SlotRef.Party(1), SlotRef.Box(2, 20)) // an empty box slot takes it exactly there
        var s = ed.snapshot()
        assertEquals(255, s.pokemonAt(SlotRef.Box(2, 20))!!.species)
        assertEquals(1, s.party.size)
        ed.movePokemon(SlotRef.Box(2, 10), SlotRef.Party(5)) // the party appends
        s = ed.snapshot()
        assertEquals(listOf(252, 258), s.party.map { it.species })
        assertNull(s.pokemonAt(SlotRef.Box(2, 10)))
        ed.removePokemon(SlotRef.Box(2, 20))
        assertNull(ed.snapshot().pokemonAt(SlotRef.Box(2, 20)))
        assertEquals(0, ed.snapshot().boxedCount)
        val texts = ed.changes().map { it.text }
        assertTrue(texts.toString(), texts.any { it.startsWith("added") })
    }

    // ---- what comes from the PID ---------------------------------------------------------------------------------------

    @Test
    fun natureGenderShinyAndLetter_rerollThePidKeepingTheRest() {
        val ed = open()
        val slot = ed.addPokemon(SlotRef.Party(0), 261) // Poochyena: 50% female
        fun g() = ed.snapshot().pokemonAt(slot)!!
        ed.edit(slot) { PokemonEdits.setNature(it, 3, Random(1)) } // Adamant
        val gender = g().gender
        assertEquals(3, g().gen3!!.nature)
        ed.edit(slot) { PokemonEdits.makeShiny(it, Random(2)) }
        assertTrue(g().isShiny)
        assertEquals(3, g().gen3!!.nature)
        assertEquals(gender, g().gender)
        val other = if (gender == Gender.MALE) Gender.FEMALE else Gender.MALE
        ed.edit(slot) { PokemonEdits.setGender(it, other, Random(3)) }
        assertEquals(other, g().gender)
        assertTrue(g().isShiny)
        assertEquals(3, g().gen3!!.nature)
        val texts = ed.changes().joinToString { it.text }
        assertTrue(texts, texts.contains("added"))

        val unown = ed.addPokemon(SlotRef.Party(1), 201)
        ed.edit(unown) { PokemonEdits.setUnownForm(it, 26, Random(4)) } // "!"
        assertEquals(26, ed.snapshot().pokemonAt(unown)!!.form)
        fails { ed.edit(slot) { PokemonEdits.setUnownForm(it, 1) } }

        val zigzagoon = ed.addPokemon(SlotRef.Party(2), 129) // Magikarp: one ability
        fails { ed.edit(zigzagoon) { PokemonEdits.setAbilitySlot(it, true) } }
        val chansey = ed.addPokemon(SlotRef.Party(3), 113) // always female
        fails { ed.edit(chansey) { PokemonEdits.setGender(it, Gender.MALE) } }
        ed.exportChecked()
    }

    @Test
    fun ivsEvsAndMetData_areValidated() {
        val ed = open()
        val slot = ed.addPokemon(SlotRef.Party(0), 6)
        fails { ed.edit(slot) { PokemonEdits.setIv(it, Stat3.SPEED, 32) } }
        ed.edit(slot) { PokemonEdits.setEv(it, Stat3.ATTACK, 255) }
        ed.edit(slot) { PokemonEdits.setEv(it, Stat3.SPEED, 255) }
        fails { ed.edit(slot) { PokemonEdits.setEv(it, Stat3.HP, 1) } } // 510 already used
        ed.edit(slot) {
            PokemonEdits.setBall(it, 1)
            PokemonEdits.setMetLocation(it, 0)
            PokemonEdits.setMetLevel(it, 100)
            PokemonEdits.setOriginGame(it, 4)
            PokemonEdits.setHeldItem(it, 200) // Leftovers
            PokemonEdits.setFriendship(it, 255)
            PokemonEdits.setNickname(it, "Blaze")
        }
        fails { ed.edit(slot) { PokemonEdits.setBall(it, 13) } }
        fails { ed.edit(slot) { PokemonEdits.setOriginGame(it, 6) } }
        fails { ed.edit(slot) { PokemonEdits.setNickname(it, "€uro") } }
        val p = ed.snapshot().pokemonAt(slot)!!
        assertEquals(510, p.gen3!!.evs.total)
        assertEquals("Blaze", p.nickname)
        assertEquals(1, p.gen3!!.ball)
        assertEquals(200, p.heldItem)
        assertEquals(p.stats, p.storedStats)
        val texts = ed.changes().joinToString { it.text }
        assertTrue(texts, texts.contains("added"))
        val again = SaveFile.load(ed.exportChecked())
        assertEquals("Blaze", again.party[0].nickname)
    }

    @Test
    fun eggs_takeTheJapaneseNameAndHatchBack() {
        val ed = open()
        val slot = ed.addPokemon(SlotRef.Party(0), 258)
        ed.edit(slot) { PokemonEdits.setEgg(it, true) }
        var p = ed.snapshot().pokemonAt(slot)!!
        assertTrue(p.isEgg)
        assertEquals("タマゴ", p.nickname)
        assertEquals("BRENDAN", p.otName) // still decoded in the game's alphabet
        fails { ed.edit(slot) { PokemonEdits.setNickname(it, "Bob") } }
        ed.edit(slot) { PokemonEdits.setOtName(it, "MAXIMUS") } // 7 letters: the game's alphabet, not the Japanese limit
        assertEquals("MAXIMUS", ed.snapshot().pokemonAt(slot)!!.otName)
        ed.edit(slot) { PokemonEdits.setEgg(it, false) }
        p = ed.snapshot().pokemonAt(slot)!!
        assertFalse(p.isEgg)
        assertEquals("GOBOU", p.nickname)
        assertEquals(LanguageId.FRENCH, p.gen3!!.language)
    }

    @Test
    fun trainerAndBag_roundTrip() {
        val ed = open()
        val t = ed.snapshot().trainer
        assertNull(t.rivalName)
        ed.updateTrainer(t.copy(money = 999_999, coins = 9_999, badges = 0xFF, secretId = 1))
        ed.setPocket(PocketType.BERRIES, listOf(org.noxeng.coincell.pkcore.ItemStack(133, 999)))
        val texts = ed.changes().map { it.text }
        assertTrue(texts.toString(), texts.containsAll(listOf("money 0→999999", "coins 0→9999", "badges 0→8", "secret ID 54321→1", "Berries: +Baie Ceriz x999")))
        val again = SaveFile.load(ed.exportChecked())
        assertEquals(999_999, again.trainer.money)
        assertEquals(1, again.trainer.secretId)
        assertEquals("Roche", Badges.list(Game.EMERALD, LanguageId.FRENCH).first().name)
        assertEquals("Boulder", Badges.list(Game.FIRERED_LEAFGREEN, LanguageId.ENGLISH).first().name)
    }

    @Test
    fun pickers_coverGeneration3() {
        val held = Pickers.heldItems(3, LanguageId.ENGLISH).map { it.id }
        assertTrue(200 in held) // Leftovers
        assertFalse(339 in held) // HM01
        assertFalse(259 in held) // a key item
        assertEquals(25, Pickers.natures(LanguageId.FRENCH).size)
        assertEquals("Master Ball", Pickers.balls(LanguageId.ENGLISH).first().primary)
        assertTrue(Pickers.locations3(LanguageId.ENGLISH).any { it.id == 0 && it.primary == "Littleroot Town" })
        assertEquals(386, Pickers.species(3, LanguageId.ENGLISH).size)
        assertTrue(Pickers.moves(3, LanguageId.ENGLISH).any { it.id == 354 }) // Psycho Boost
    }

    // ---- the user's Sapphire -------------------------------------------------------------------------------------------

    @Test
    fun realSapphire_editsAndExports() {
        val bytes = Gen3Saves.realSapphireOrSkip()
        val ed = SaveEditor.open(bytes, EditorTestSaves.sapphire, Random(9))
        assertEquals(LanguageId.FRENCH, ed.language)
        assertEquals(Game.RUBY_SAPPHIRE, ed.game)
        assertFalse(ed.compactBoxes)
        assertTrue(ed.changes().isEmpty())
        ed.edit(SlotRef.Party(0)) { PokemonEdits.setLevel(it, 50) }
        ed.movePokemon(SlotRef.Party(5), SlotRef.Box(0, 0))
        val texts = ed.changes().map { it.text }
        assertTrue(texts.toString(), texts.contains("Léviator L40→L50"))
        assertTrue(texts.toString(), texts.contains("moved Magnéton party→box 1"))
        val out = ed.exportChecked()
        ed.rebase(out)
        assertTrue(ed.changes().isEmpty())
        assertNotNull(SaveFile.load(out).boxes[0].slots[0])
    }
}

/** Generation 3 saves for the app tests. */
object Gen3Saves {
    /** A blank Emerald (save slot 0 only): trainer BRENDAN, TID 12345 / SID 54321, no Pokemon. */
    fun emerald(): ByteArray {
        val data = ByteArray(0x20000)
        for (i in 0 until 14) {
            val ofs = i * 0x1000
            data[ofs + 0xFF4] = i.toByte()
            data[ofs + 0xFFC] = 1
        }
        val name = ByteArray(8) { 0xFF.toByte() }
        StringConverter3.setString(name, 0, 7, "BRENDAN", 7, LanguageId.ENGLISH)
        name.copyInto(data, 0)
        data[0xA] = 0x39
        data[0xB] = 0x30
        data[0xC] = 0x31
        data[0xD] = 0xD4.toByte()
        data[0xAC] = 0x5A // security key (Emerald) ...
        data[0xA00] = 1 // ... and data past the Ruby/Sapphire structure
        // Money and coins are masked with the key: store 0 for both.
        data[0x1000 + 0x490] = 0x5A
        data[0x1000 + 0x494] = 0x5A
        for (i in 0 until 14) {
            val ofs = i * 0x1000
            var chk = 0L
            for (j in ofs until ofs + 0xF80 step 4) {
                val w = (data[j].toLong() and 0xFF) or ((data[j + 1].toLong() and 0xFF) shl 8) or
                    ((data[j + 2].toLong() and 0xFF) shl 16) or ((data[j + 3].toLong() and 0xFF) shl 24)
                chk = (chk + w) and 0xFFFFFFFFL
            }
            val c = ((chk + (chk ushr 16)) and 0xFFFF).toInt()
            data[ofs + 0xFF6] = c.toByte()
            data[ofs + 0xFF7] = (c shr 8).toByte()
        }
        return data
    }

    private val localDir: File? = listOf(File("../pkcore/src/test/fixtures-local"), File("pkcore/src/test/fixtures-local"))
        .firstOrNull { it.isDirectory }

    /** French Sapphire, 128 KiB (FlashGBX's 16-byte RTC footer stripped); the test is skipped when the file is absent. */
    fun realSapphireOrSkip(): ByteArray {
        val bytes = localDir?.let { File(it, "Pokemon - Version Saphir (France).sav") }?.takeIf { it.isFile }?.readBytes()
        org.junit.Assume.assumeTrue("pkcore/src/test/fixtures-local/Pokemon - Version Saphir (France).sav not present", bytes != null)
        return bytes!!.copyOf(0x20000)
    }
}

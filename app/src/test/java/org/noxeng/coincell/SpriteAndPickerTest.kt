package org.noxeng.coincell

import org.noxeng.coincell.editor.Pickers
import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.Dvs
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.text.LanguageId
import org.noxeng.coincell.sprites.AssetSource
import org.noxeng.coincell.sprites.SpriteIndex
import org.noxeng.coincell.sprites.SpriteResolver
import org.noxeng.coincell.sprites.SpriteRules
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * The optional sprite pack: lookup rules (pkassets/README.md), the fallback when the pack or a file is absent, and the
 * searchable pickers. Nothing here needs the pack; the one test that checks the real files skips itself without it.
 */
class SpriteAndPickerTest {
    private val index = """
        {"version":1,
         "itemMaps":{"gen1":[0,1,2,3,4,442],"gen2":[0,1,2,213,3,4,0,450]},
         "entries":{
           "pokemon/box/25":"sprites/pokemon/box/25.png",
           "pokemon/box/25-female":"sprites/pokemon/box/25-female.png",
           "pokemon/box_shiny/25":"sprites/pokemon/box_shiny/25.png",
           "pokemon/box/201":"sprites/pokemon/box/201.png",
           "pokemon/box/201-3":"sprites/pokemon/box/201-d.png",
           "pokemon/box_shiny/201":"sprites/pokemon/box_shiny/201.png",
           "pokemon/box_shiny/201-3":"sprites/pokemon/box_shiny/201-d.png",
           "pokemon/box/157":"sprites/pokemon/box/157.png",
           "pokemon/box/egg":"sprites/pokemon/box/egg.png",
           "pokemon/box/unknown":"sprites/pokemon/box/unknown.png",
           "item/1":"sprites/items/ball/master.png",
           "item/450":"sprites/items/key-item/bicycle.png",
           "item_outline/450":"sprites/items_outline/key-item/bicycle.png",
           "item/442":"sprites/items/ball/poke.png"}}
    """.trimIndent()

    private val idx = SpriteIndex.parse(index)

    // ---- Pokemon rules ----------------------------------------------------------------------------------------------

    @Test
    fun pokemon_defaultShinyEggAndFallbackChain() {
        assertEquals(listOf("sprites/pokemon/box/157.png", "sprites/pokemon/box/unknown.png"), idx.pokemon(157, false, 0, false, false))
        // shiny: the shiny file first, the normal one as the fallback when the pack lacks it (157 has no shiny file here)
        assertEquals(listOf("sprites/pokemon/box_shiny/25.png", "sprites/pokemon/box/25.png", "sprites/pokemon/box/unknown.png"), idx.pokemon(25, true, 0, false, false))
        assertEquals(listOf("sprites/pokemon/box/157.png", "sprites/pokemon/box/unknown.png"), idx.pokemon(157, true, 0, false, false))
        // an egg is the egg sprite, whatever the species
        assertEquals(listOf("sprites/pokemon/box/egg.png"), idx.pokemon(157, true, 0, true, true))
        // unknown species: the "unknown" sprite
        assertEquals(listOf("sprites/pokemon/box/unknown.png"), idx.pokemon(999, false, 0, false, false))
    }

    @Test
    fun pokemon_femaleSpriteOnlyWhenThePackHasOne() {
        assertEquals("sprites/pokemon/box/25-female.png", idx.pokemon(25, false, 0, true, false).first())
        assertEquals("sprites/pokemon/box/25.png", idx.pokemon(25, false, 0, false, false).first())
        assertEquals("sprites/pokemon/box/157.png", idx.pokemon(157, false, 0, true, false).first()) // no female file: default
    }

    @Test
    fun pokemon_unownFormComesFromTheDvs_shinyAndNormal() {
        // Gen 2: letter = (Atk&6)<<5 | (Def&6)<<3 | (Spd&6)<<1 | (Spc&6)>>1, divided by 10
        assertEquals(0, SpriteRules.unownForm(Dvs(0, 0, 0, 0)))
        assertEquals(25, SpriteRules.unownForm(Dvs(15, 15, 15, 15))) // Z
        val d = SpriteRules.unownForm(Dvs(2, 0, 0, 0))
        assertEquals(64 / 10, d)
        assertEquals(
            listOf("sprites/pokemon/box/201-d.png", "sprites/pokemon/box/201.png", "sprites/pokemon/box/unknown.png"),
            idx.pokemon(201, false, 3, false, false),
        )
        assertEquals("sprites/pokemon/box_shiny/201-d.png", idx.pokemon(201, true, 3, false, false).first())
        // form 0 (letter A) uses the plain file
        assertEquals("sprites/pokemon/box/201.png", idx.pokemon(201, false, 0, false, false).first())
        // a letter the pack has no file for falls back to the default form
        assertEquals("sprites/pokemon/box/201.png", idx.pokemon(201, false, 9, false, false).first())
    }

    @Test
    fun keyOf_usesRealSummaries_shinyGenderAndUnown() {
        val save = SaveFile.load(EditorTestSaves.crystal(), 2)
        val pk = save.getPokemon(SlotRef.Party(0)) as org.noxeng.coincell.pkcore.gb.Pk2
        pk.species = 201
        pk.ivAttack = 2
        pk.ivDefense = 10
        pk.ivSpeed = 10
        pk.ivSpecial = 10
        val summary = pk.summary()
        val key = SpriteRules.keyOf(summary)
        assertEquals(201, key.species)
        assertTrue(key.shiny)
        assertFalse(key.egg)
        assertEquals(SpriteRules.unownForm(summary.dvs), key.form)
        pk.isEgg = true
        assertTrue(SpriteRules.keyOf(pk.summary()).egg)
    }

    @Test
    fun initials_forTheTextFallback() {
        assertEquals("TYP", SpriteRules.initials("Typhlosion"))
        assertEquals("MRM", SpriteRules.initials("MR. MIME"))
        assertEquals("?", SpriteRules.initials(""))
    }

    // ---- items ------------------------------------------------------------------------------------------------------

    @Test
    fun items_convertGameIdsAndPreferOutlinesOnDark() {
        // Gen 2 id 7 -> modern 450 (Bicycle), outline first on dark backgrounds
        assertEquals(listOf("sprites/items_outline/key-item/bicycle.png", "sprites/items/key-item/bicycle.png"), idx.item(2, 7, true))
        assertEquals(listOf("sprites/items/key-item/bicycle.png"), idx.item(2, 7, false))
        // Gen 1 bag ids use their own table (id 5 -> 442, a Poke Ball)
        assertEquals(listOf("sprites/items/ball/poke.png"), idx.item(1, 5, false))
        // no equivalent / not in the table / no file in the pack
        assertTrue(idx.item(2, 6, false).isEmpty())
        assertTrue(idx.item(2, 200, false).isEmpty())
        assertTrue(idx.item(1, 3, false).isEmpty())
    }

    // ---- absent pack: text and initials -----------------------------------------------------------------------------

    @Test
    fun absentPack_everyLookupIsEmpty_neverAnError() {
        val none = SpriteIndex.load(AssetSource { null })
        assertFalse(none.available)
        assertTrue(none.pokemon(25, true, 0, false, false).isEmpty())
        assertTrue(none.item(2, 1, true).isEmpty())
        val resolver = SpriteResolver(none, AssetSource { error("must not be asked for files") })
        assertFalse(resolver.available)
        assertNull(resolver.pokemon(25, false, 0, false, false))
        assertNull(resolver.item(2, 1, false))
    }

    @Test
    fun brokenOrEmptyIndex_isTheSameAsNoPack() {
        for (text in listOf("", "not json", "[]", "{}", """{"entries":42}""")) {
            val i = SpriteIndex.parse(text)
            assertFalse(text, i.available)
            assertTrue(i.pokemon(25, false, 0, false, false).isEmpty())
        }
        val throwing = SpriteIndex.load(AssetSource { throw java.io.IOException("asset error") })
        assertFalse(throwing.available)
    }

    @Test
    fun missingFile_movesOnToTheNextCandidate_thenGivesUp() {
        val files = mapOf("sprites/pokemon/box/25.png" to byteArrayOf(1, 2, 3), "sprites/pokemon/box/unknown.png" to byteArrayOf(9))
        val resolver = SpriteResolver(idx, AssetSource { path -> files[path]?.let(::ByteArrayInputStream) })
        // the shiny file is in the index but not on disk: the normal sprite is used
        val shiny = resolver.pokemon(25, true, 0, false, false)!!
        assertEquals("sprites/pokemon/box/25.png", shiny.path)
        assertArrayEquals(byteArrayOf(1, 2, 3), shiny.bytes)
        // species with no file at all: the "unknown" sprite
        assertEquals("sprites/pokemon/box/unknown.png", resolver.pokemon(157, false, 0, false, false)!!.path)
        // pack deleted after the index was built: nothing readable, the UI draws initials
        val gone = SpriteResolver(idx, AssetSource { null })
        assertTrue(gone.available)
        assertNull(gone.pokemon(25, false, 0, false, false))
        assertNull(gone.item(2, 7, true))
        // an empty file counts as missing
        val empty = SpriteResolver(idx, AssetSource { ByteArrayInputStream(ByteArray(0)) })
        assertNull(empty.pokemon(25, false, 0, false, false))
    }

    /** With the pack on disk (private builds only), every rule resolves to a real file; skipped otherwise. */
    @Test
    fun realPack_ifPresent_resolvesToFiles() {
        val assets = listOf(File("../pkassets/src/main/assets"), File("pkassets/src/main/assets")).firstOrNull { it.isDirectory }
        val manifest = listOf(File("../pkassets/manifest.json"), File("pkassets/manifest.json")).firstOrNull { it.isFile }
        Assume.assumeTrue("pkassets/ is not present", assets != null && manifest != null)
        val root = kotlinx.serialization.json.Json.parseToJsonElement(manifest!!.readText()).let { it as kotlinx.serialization.json.JsonObject }
        val entries = (root["entries"] as kotlinx.serialization.json.JsonObject)
        val slim = entries.entries.filter { (k, _) -> listOf("pokemon/box/", "pokemon/box_shiny/", "item/", "item_outline/").any { k.startsWith(it) } }
            .joinToString(",") { (k, v) ->
                val path = (v as kotlinx.serialization.json.JsonObject)["path"]!!.toString()
                "\"$k\":$path"
            }
        val maps = root["itemIdMaps"] as kotlinx.serialization.json.JsonObject
        val json = """{"itemMaps":{"gen1":${maps["gen1BagByName"]},"gen2":${maps["gen2"]}},"entries":{$slim}}"""
        val real = SpriteIndex.parse(json)
        assertTrue(real.available)
        val resolver = SpriteResolver(real, AssetSource { p -> File(assets, p).takeIf { it.isFile }?.inputStream() })
        // Typhlosion, a shiny Pikachu, Unown D (form 3) normal and shiny, an egg, the Potion (Gen 2 id 18), Master Ball (Gen 1 id 1)
        for ((species, shiny, form) in listOf(Triple(157, false, 0), Triple(25, true, 0), Triple(201, false, 3), Triple(201, true, 27), Triple(251, true, 0))) {
            val sprite = resolver.pokemon(species, shiny, form, false, false)
            assertNotNull("species $species shiny=$shiny form=$form", sprite)
            assertTrue(sprite!!.bytes.size > 8)
            // PNG signature
            assertEquals(0x89.toByte(), sprite.bytes[0])
        }
        assertEquals("sprites/pokemon/box_shiny/201-d.png", real.pokemon(201, true, 3, false, false).first())
        assertNotNull(resolver.pokemon(1, false, 0, false, true))
        assertNotNull(resolver.item(2, 18, false))
        assertNotNull(resolver.item(1, 1, true))
    }

    // ---- pickers (French and English) -------------------------------------------------------------------------------

    @Test
    fun speciesPicker_french_showsFrenchWithEnglishSecondary() {
        val entries = Pickers.species(2, LanguageId.FRENCH)
        assertEquals(251, entries.size)
        val typhlosion = entries.first { it.id == 157 }
        assertEquals("Typhlosion", typhlosion.primary)
        val rattata = entries.first { it.id == 19 }
        assertEquals("Rattata", rattata.primary)
        assertNull(rattata.secondary) // same in both languages
        val bulbi = entries.first { it.id == 1 }
        assertEquals("Bulbizarre", bulbi.primary)
        assertEquals("Bulbasaur", bulbi.secondary)
    }

    @Test
    fun speciesSearch_findsInFrenchAndEnglish_ignoringCaseAndAccents() {
        val entries = Pickers.species(2, LanguageId.FRENCH)
        assertEquals(1, Pickers.search(entries, "bulbi").first().id)
        assertEquals(1, Pickers.search(entries, "BULBASAUR").first().id) // English name also matches
        assertEquals(1, Pickers.search(entries, "1").first().id) // by number first
        assertTrue(Pickers.search(entries, "zzzz").isEmpty())
        // accents: "Évoli" is Eevee in French
        assertEquals(133, Pickers.search(entries, "evoli").first().id)
        assertEquals(133, Pickers.search(entries, "ÉVOLI").first().id)
        assertEquals(133, Pickers.search(entries, "eevee").first().id)
        // starts-with ranks before contains
        val ordered = Pickers.search(entries, "ros").map { it.primary }
        assertTrue(ordered.toString(), ordered.indexOfFirst { it.lowercase().startsWith("ros") }.let { it == -1 || it == 0 })
        // an empty query returns everything, untouched
        assertEquals(entries, Pickers.search(entries, "  "))
    }

    @Test
    fun movePicker_frenchAndEnglish() {
        val fr = Pickers.moves(2, LanguageId.FRENCH)
        val tackle = fr.first { it.id == 33 }
        assertEquals("Charge", tackle.primary)
        assertEquals("Tackle", tackle.secondary)
        assertEquals(33, Pickers.search(fr, "charge").first().id)
        assertEquals(33, Pickers.search(fr, "tackle").first { it.id == 33 }.id)
        // accents in the query or the name do not matter
        assertTrue(Pickers.search(fr, "Éclair").isNotEmpty())
        assertTrue(Pickers.search(fr, "eclair").map { it.id }.containsAll(Pickers.search(fr, "Éclair").map { it.id }))
        val en = Pickers.moves(2, LanguageId.ENGLISH)
        assertNull(en.first { it.id == 33 }.secondary)
        assertEquals(33, Pickers.search(en, "tackle").first().id)
        // Gen 1 has no move beyond 165, Gen 2 has 251
        assertEquals(165, Pickers.moves(1, LanguageId.ENGLISH).size)
        assertEquals(251, en.size)
    }

    @Test
    fun locationPicker_gen2Names_frenchAndEnglish() {
        val en = Pickers.locations(LanguageId.ENGLISH)
        // 1..95 are places, 96..125 are unused, 126 and 127 are "can't tell" and "event"
        assertEquals((1..95).toList() + listOf(126, 127), en.map { it.id })
        assertEquals("New Bark Town", en.first().primary)
        assertEquals("Fast Ship", en.first { it.id == 95 }.primary)
        val fr = Pickers.locations(LanguageId.FRENCH)
        assertEquals("Bourg Geon", fr.first { it.id == 1 }.primary)
        assertEquals("New Bark Town", fr.first { it.id == 1 }.secondary)
        assertEquals(1, Pickers.search(fr, "new bark").first().id)
        assertEquals("Location #100", GameStrings.forLanguage(LanguageId.ENGLISH).locationName(100))
        assertEquals("(None)", GameStrings.forLanguage(LanguageId.ENGLISH).locationName(0))
    }

    @Test
    fun itemPicker_usesTheSavesItemNames_andDropsPlaceholders() {
        val fr = Pickers.items(2, (1..255).toList(), LanguageId.FRENCH)
        assertTrue(fr.none { it.primary.startsWith("Teru-sama") || it.primary.startsWith("?") })
        val potion = fr.first { it.id == 18 }
        assertEquals("Potion", potion.primary)
        val hyper = fr.first { it.id == 2 }
        assertEquals("Hyper Ball", hyper.primary)
        assertEquals("Ultra Ball", hyper.secondary)
        assertEquals(2, Pickers.search(fr, "ultra ball").first().id) // the English name finds the French item
        assertEquals(2, Pickers.search(fr, "hyper ball").first().id)
        assertTrue(Pickers.search(fr, "bonbon").any { it.primary == "Super Bonbon" })
        // sorted by name for browsing
        assertEquals(fr.map { it.primary }.sortedBy { Pickers.normalize(it) }, fr.map { it.primary })
    }

    @Test
    fun normalize_removesAccentsAndSymbols() {
        assertEquals("pokeball", Pickers.normalize("Poké Ball"))
        assertEquals("mrmime", Pickers.normalize("Mr. Mime"))
        assertEquals("oeuf", Pickers.normalize("OEUF"))
    }
}

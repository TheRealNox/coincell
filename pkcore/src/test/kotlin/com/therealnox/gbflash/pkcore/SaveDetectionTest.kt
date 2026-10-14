package com.therealnox.gbflash.pkcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** Detection of game / region / generation, with the same rules as PKHeX, and rejection of anything else. */
class SaveDetectionTest {
    private fun assertUnsupported(bytes: ByteArray, messagePart: String) {
        try {
            SaveFile.load(bytes)
            fail("expected UnsupportedSaveException")
        } catch (e: UnsupportedSaveException) {
            assertTrue("message '${e.message}' should mention '$messagePart'", e.message!!.contains(messagePart, ignoreCase = true))
        }
    }

    @Test
    fun everyVariantIsDetected() {
        val expected = mapOf(
            Triple(Game.RED_BLUE, Region.INTERNATIONAL, 0x8000) to 1,
            Triple(Game.YELLOW, Region.INTERNATIONAL, 0x8000) to 1,
            Triple(Game.RED_BLUE, Region.JAPANESE, 0x8000) to 1,
            Triple(Game.YELLOW, Region.JAPANESE, 0x8000) to 1,
        )
        for ((variant, gen) in expected) {
            val (game, region, size) = variant
            val save = SaveFile.load(SyntheticSaves.blank(game, region, size, yellow = game == Game.YELLOW))
            assertEquals("$variant", game, save.game)
            assertEquals("$variant", region, save.region)
            assertEquals(gen, save.generation)
        }
        for ((game, region, size) in SyntheticSaves.allVariants.filter { it.first.generation == 2 }) {
            val save = SaveFile.load(SyntheticSaves.blank(game, region, size))
            assertEquals("$game $region $size", game, save.game)
            assertEquals("$game $region $size", region, save.region)
            assertEquals(2, save.generation)
        }
    }

    @Test
    fun yellowIsToldApartByTheStarterOrPikachuFriendship() {
        assertEquals(Game.RED_BLUE, SaveFile.load(SyntheticSaves.blank(Game.RED_BLUE)).game)
        assertEquals(Game.YELLOW, SaveFile.load(SyntheticSaves.blank(Game.RED_BLUE, yellow = true)).game)
        // no starter, but Pikachu has friendship -> Yellow
        val data = SyntheticSaves.blank(Game.RED_BLUE)
        data[0x271C] = 90
        assertEquals(Game.YELLOW, SaveFile.load(data).game)
        // a different starter wins over friendship
        data[0x29C3] = 0x99.toByte()
        assertEquals(Game.RED_BLUE, SaveFile.load(data).game)
    }

    @Test
    fun gameVersionAndGenerationDriveThePersonalTable() {
        assertEquals(1, SaveFile.load(SyntheticSaves.blank(Game.YELLOW, yellow = true)).generation)
        assertEquals(2, SaveFile.load(SyntheticSaves.blank(Game.CRYSTAL)).generation)
    }

    @Test
    fun wrongSizesAreRejectedWithAClearMessage() {
        assertUnsupported(ByteArray(0x8030), "size")
        assertUnsupported(ByteArray(0x4000), "size")
        assertUnsupported(ByteArray(0), "size")
        assertUnsupported(ByteArray(0x20000), "size")
    }

    @Test
    fun blankOrNoiseIsNotASave() {
        assertUnsupported(ByteArray(0x8000), "Not a recognised")
        assertUnsupported(ByteArray(0x8000) { 0xFF.toByte() }, "Not a recognised")
        assertUnsupported(ByteArray(0x10000), "Not a recognised")
        val rnd = Random(42)
        repeat(20) {
            val noise = ByteArray(0x8000).also { rnd.nextBytes(it) }
            // random data could in theory pass the list checks; the odds for these seeds are nil
            assertUnsupported(noise, "Not a recognised")
        }
    }

    @Test
    fun koreanGen2IsDetectedButNotSupported() {
        val data = ByteArray(0x8000)
        for (ofs in listOf(0x2DAE, 0x28CC)) {
            data[ofs] = 0
            data[ofs + 1] = 0xFF.toByte()
        }
        assertUnsupported(data, "Korean")
    }

    @Test
    fun aBackupWithAnRtcFooterMustBeStrippedByTheCaller() {
        val raw = SyntheticSaves.blank(Game.CRYSTAL)
        assertUnsupported(raw + ByteArray(48), "footer")
        assertEquals(Game.CRYSTAL, SaveFile.load(raw.copyOf(0x8000)).game)
    }

    @Test
    fun gen2SaveIsNotMistakenForGen1() {
        // Both families are 32 KiB; the Gen 1 lists are checked first and must not match an empty Gen 2 save.
        for (game in listOf(Game.GOLD_SILVER, Game.CRYSTAL)) {
            val save = SaveFile.load(SyntheticSaves.blank(game))
            assertEquals(2, save.generation)
            assertFalse(save.isModified)
        }
    }
}

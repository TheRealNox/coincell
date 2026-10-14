package com.therealnox.gbflash.pkcore

import com.therealnox.gbflash.pkcore.data.SpeciesName
import com.therealnox.gbflash.pkcore.gb.Pk1
import com.therealnox.gbflash.pkcore.gb.Pk2
import com.therealnox.gbflash.pkcore.text.LanguageId
import com.therealnox.gbflash.pkcore.text.StringConverter1
import com.therealnox.gbflash.pkcore.text.StringConverter2
import com.therealnox.gbflash.pkcore.text.StringOption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEncodingTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun encode1(text: String, size: Int = 11, jp: Boolean = false, option: StringOption = StringOption.CLEAR_50, max: Int = size - 1): ByteArray {
        val buf = ByteArray(size)
        StringConverter1.setString(buf, 0, size, text, max, jp, option)
        return buf
    }

    @Test
    fun gen1EnglishEncodesWithTerminatorFill() {
        assertArrayEquals(
            bytes(0x8D, 0x88, 0x82, 0x8A, 0x50, 0x50, 0x50, 0x50, 0x50, 0x50, 0x50),
            encode1("NICK"),
        )
    }

    @Test
    fun clearZeroFillsWithZeroesAfterTerminator() {
        assertArrayEquals(
            bytes(0x8D, 0x88, 0x82, 0x8A, 0x50, 0, 0, 0),
            encode1("NICK", size = 8, option = StringOption.CLEAR_ZERO),
        )
    }

    @Test
    fun clear7fAndNoneOptions() {
        val buf = bytes(1, 2, 3, 4, 5, 6)
        StringConverter1.setString(buf, 0, 6, "A", 5, false, StringOption.NONE)
        assertArrayEquals(bytes(0x80, 0x50, 3, 4, 5, 6), buf)
        StringConverter1.setString(buf, 0, 6, "B", 5, false, StringOption.CLEAR_7F)
        assertArrayEquals(bytes(0x81, 0x50, 0x7F, 0x7F, 0x7F, 0x7F), buf)
    }

    @Test
    fun decodeStopsAtTerminatorAndIgnoresTrash() {
        val data = bytes(0x8D, 0x88, 0x82, 0x8A, 0x50, 0x80, 0x81, 0x82)
        assertEquals("NICK", StringConverter1.getString(data, 0, data.size, false))
        // a zero byte is "no character" in the tables and ends the string too
        assertEquals("AB", StringConverter1.getString(bytes(0x80, 0x81, 0x00, 0x82), 0, 4, false))
    }

    @Test
    fun fullLengthStringHasNoTerminator() {
        val buf = ByteArray(3)
        val written = StringConverter1.setString(buf, 0, 3, "ABC", 3, false)
        assertEquals(3, written)
        assertArrayEquals(bytes(0x80, 0x81, 0x82), buf)
        assertEquals("ABC", StringConverter1.getString(buf, 0, 3, false))
    }

    @Test
    fun overlongInputIsCappedAtMaxLength() {
        assertEquals("ABCDE", StringConverter1.getString(encode1("ABCDEFGHIJKLMNOP", max = 5), 0, 11, false))
    }

    @Test
    fun unencodableCharacterEndsTheString() {
        // '€' is not in any table: the string stops there and is terminated
        val buf = encode1("AB€CD")
        assertEquals("AB", StringConverter1.getString(buf, 0, 11, false))
        assertEquals(0x50, buf[2].toInt() and 0xFF)
    }

    @Test
    fun tradeOtMarker() {
        val buf = encode1("*")
        assertEquals(0x5D, buf[0].toInt() and 0xFF)
        assertEquals(0x50, buf[1].toInt() and 0xFF)
        assertEquals("*", StringConverter1.getString(buf, 0, 11, false))
    }

    @Test
    fun gen1SpecialCharacters() {
        // Pk, Mn ligatures, male/female signs, yen, apostrophe-s
        val buf = encode1("{}♂♀¥")
        assertArrayEquals(bytes(0xE1, 0xE2, 0xEF, 0xF5, 0xF0, 0x50), buf.copyOf(6))
        assertEquals("{}♂♀¥", StringConverter1.getString(buf, 0, 11, false))
    }

    @Test
    fun japaneseTableRoundTrip() {
        val text = "ピカチュウ"
        val buf = encode1(text, size = 6, jp = true, max = 5)
        assertArrayEquals(bytes(0x41, 0x85, 0x90, 0xAE, 0x82, 0x50), buf)
        assertEquals(text, StringConverter1.getString(buf, 0, 6, true))
        // dakuten forms live in the first rows
        assertEquals("ガギ", StringConverter1.getString(bytes(0x05, 0x06, 0x50), 0, 3, true))
    }

    @Test
    fun hiraganaLookalikesAreRemappedToKatakana() {
        // べ ぺ へ り are typed as hiragana but the game uses the katakana glyph slots
        val buf = encode1("べぺへり", size = 6, jp = true, max = 5)
        assertEquals("ベペヘリ", StringConverter1.getString(buf, 0, 6, true))
    }

    @Test
    fun gen2FrenchTableHasCCedillaAndGermanSharesIt() {
        val buf = ByteArray(11)
        StringConverter2.setString(buf, 0, 11, "François", 10, LanguageId.FRENCH)
        assertEquals(0xBF, buf[4].toInt() and 0xFF) // ç
        assertEquals("François", StringConverter2.getString(buf, 0, 11, LanguageId.FRENCH))
        assertEquals("François", StringConverter2.getString(buf, 0, 11, LanguageId.GERMAN))
        // The English table has no ç at that slot: it decodes as a different letter (the Spanish/English 'Á')
        assertEquals('Á', StringConverter2.getString(buf, 0, 11, LanguageId.ENGLISH)[4])
    }

    @Test
    fun gen2LanguageTablesDifferWhereExpected() {
        // 0xE4: '+' (English/French/German), inverted question mark (Italian/Spanish)
        val one = bytes(0xE4, 0x50)
        assertEquals("+", StringConverter2.getString(one, 0, 2, LanguageId.ENGLISH))
        assertEquals("+", StringConverter2.getString(one, 0, 2, LanguageId.FRENCH))
        assertEquals("¿", StringConverter2.getString(one, 0, 2, LanguageId.SPANISH))
        // 0xB3 'ù' is shared; 0xC6 'È' (EN) vs 'ë' (FR)
        assertEquals("È", StringConverter2.getString(bytes(0xC6, 0x50), 0, 2, LanguageId.ENGLISH))
        assertEquals("ë", StringConverter2.getString(bytes(0xC6, 0x50), 0, 2, LanguageId.FRENCH))
    }

    @Test
    fun ligaturesInflateAndDeflate() {
        // English: apostrophe-before ('d 'l 'm 'r 's 't 'v) is stored as one glyph
        val e = StringConverter2.deflateLigatures("a'd", LanguageId.ENGLISH)
        assertEquals("a０", e)
        assertEquals("a’d", StringConverter2.inflateLigatures(e, LanguageId.ENGLISH))
        // French/German: apostrophe-after (c' d' j' l' m' n' p' s' t' u' y')
        val f = StringConverter2.deflateLigatures("d'Or", LanguageId.FRENCH)
        assertEquals("１Or", f)
        assertEquals("d’Or", StringConverter2.inflateLigatures(f, LanguageId.FRENCH))
    }

    @Test
    fun japaneseAndKoreanSkipLigatureProcessing() {
        assertEquals("a'd", StringConverter2.deflateLigatures("a'd", LanguageId.JAPANESE))
        assertEquals("a０", StringConverter2.inflateLigatures("a０", LanguageId.JAPANESE))
    }

    @Test
    fun germanGlyphDetection() {
        assertTrue(StringConverter1.isG12German(bytes(0x80, 0xC3, 0x50)))
        assertFalse(StringConverter1.isG12German(bytes(0x80, 0xC7, 0x50)))
    }

    // ---- default nicknames ------------------------------------------------------------------------------------

    @Test
    fun defaultSpeciesNamesFollowGen12Rules() {
        assertEquals("PIKACHU", SpeciesName.getGeneration12(25, LanguageId.ENGLISH))
        assertEquals("MR․MIME", SpeciesName.getGeneration12(122, LanguageId.ENGLISH)) // special period, no space
        assertEquals("EVOLI", SpeciesName.getGeneration12(133, LanguageId.FRENCH)) // diacritics stripped on E
        assertEquals("OEUF", SpeciesName.getGeneration12(0, LanguageId.FRENCH))
        assertEquals("EGG", SpeciesName.getGeneration12(0, LanguageId.ENGLISH))
        assertEquals("ピカチュウ", SpeciesName.getGeneration12(25, LanguageId.JAPANESE))
        assertEquals(LanguageId.FRENCH, SpeciesName.getSpeciesNameLanguage(1, "BULBIZARRE"))
        assertEquals(LanguageId.GERMAN, SpeciesName.getSpeciesNameLanguage(1, "BISASAM"))
        assertEquals(-1, SpeciesName.getSpeciesNameLanguage(1, "BOB"))
    }

    @Test
    fun nicknameSetterKeepsTerminatorStyleAndTrash() {
        val pk = SyntheticSaves.pk1(species = 25, nick = null)
        assertFalse(pk.isNicknamed)
        assertEquals("PIKACHU", pk.nickname)

        pk.nickname = "SPARKY"
        assertTrue(pk.isNicknamed)
        assertEquals("SPARKY", pk.nickname)
        assertArrayEquals(
            bytes(0x92, 0x8F, 0x80, 0x91, 0x8A, 0x98, 0x50, 0x50, 0x50, 0x50, 0x50),
            pk.nickTrash,
        )

        // A buffer already using 0x00 padding keeps using it.
        val zeroPadded = Pk1(ByteArray(Pk1.PARTY_SIZE), pk.otTrash, bytes(0x80, 0x81, 0x50, 0, 0, 0, 0, 0, 0, 0, 0))
        zeroPadded.nickname = "C"
        assertArrayEquals(bytes(0x82, 0x50, 0, 0, 0, 0, 0, 0, 0, 0, 0), zeroPadded.nickTrash)

        pk.setNotNicknamed()
        assertFalse(pk.isNicknamed)
        assertEquals("PIKACHU", pk.nickname)
    }

    @Test
    fun nicknamedDetectionComparesTheWholeBuffer() {
        val pk = SyntheticSaves.pk1(species = 25)
        assertFalse(pk.isNicknamed)
        // trash after the terminator makes the buffer differ from the default -> counts as nicknamed (as in PKHeX)
        pk.nickTrash[10] = 0x41
        assertTrue(pk.isNicknamed)
    }

    @Test
    fun gen2LanguageIsGuessedFromTheDefaultNickname() {
        val pk = Pk2(false)
        pk.species = 1
        pk.setNotNicknamed(LanguageId.FRENCH)
        assertEquals("BULBIZARRE", pk.nickname)
        assertEquals(LanguageId.FRENCH, pk.language)
        pk.setNotNicknamed(LanguageId.ENGLISH)
        assertEquals(LanguageId.ENGLISH, pk.language)
    }
}

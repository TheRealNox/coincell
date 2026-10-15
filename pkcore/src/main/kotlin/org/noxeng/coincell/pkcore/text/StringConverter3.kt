// Ported from PKHeX.Core/PKM/Strings/StringConverter3.cs and the gender-symbol helpers of StringConverter4Util.cs
// (PKHeX, GPL-3.0, commit 641c26b). The character tables are generated into data/CharTables.kt.
package org.noxeng.coincell.pkcore.text

import org.noxeng.coincell.pkcore.data.CharTables

/** Generation 3 (GBA) text encoding: one byte per character, 0xFF terminates. */
public object StringConverter3 {
    public const val TERMINATOR_BYTE: Int = 0xFF
    private const val TERMINATOR = 'ÿ'
    private const val QUOTE_LEFT_BYTE = 0xB1
    private const val QUOTE_RIGHT_BYTE = 0xB2
    private const val APOSTROPHE_BYTE = 0xB4

    // The international table stores the gender symbols as these "halfwidth" stand-ins (StringConverter4Util.HGM/HGF).
    private const val HGM = '⑭'
    private const val HGF = '⑮'

    private fun table(language: Int): String =
        if (language == LanguageId.JAPANESE) CharTables.gen3Japanese else CharTables.gen3International

    /** Decodes [length] bytes of [data] from [offset], stopping at the terminator. */
    public fun getString(data: ByteArray, offset: Int, length: Int, language: Int): String {
        val table = table(language)
        val sb = StringBuilder(length)
        for (i in offset until offset + length) {
            val value = data[i].toInt() and 0xFF
            val c = when (value) {
                QUOTE_LEFT_BYTE -> quoteLeft(language)
                QUOTE_RIGHT_BYTE -> quoteRight(language)
                else -> table[value]
            }
            if (c == TERMINATOR) break // Stop if Terminator/Invalid
            sb.append(normalizeGenderSymbol(c))
        }
        return sb.toString()
    }

    public fun getString(data: ByteArray, offset: Int, length: Int, japanese: Boolean): String =
        getString(data, offset, length, if (japanese) LanguageId.JAPANESE else LanguageId.ENGLISH)

    /**
     * Encodes [value] (cut to [maxLength]) into `dest[offset until offset + length]`, adding a terminator when there is
     * room. Characters the table lacks end the string, as in PKHeX. Returns the number of bytes written.
     */
    public fun setString(
        dest: ByteArray,
        offset: Int,
        length: Int,
        value: String,
        maxLength: Int,
        language: Int,
        option: StringOption = StringOption.CLEAR_FF,
    ): Int {
        val text = if (value.length > maxLength) value.substring(0, maxLength) else value // Hard cap
        when (option) {
            StringOption.CLEAR_FF -> dest.fill(TERMINATOR_BYTE.toByte(), offset, offset + length)
            StringOption.CLEAR_ZERO -> dest.fill(0, offset, offset + length)
            else -> Unit
        }
        val jp = language == LanguageId.JAPANESE
        val table = table(language)
        var i = 0
        while (i < text.length && i < length) {
            var chr = text[i]
            if (!jp) chr = unNormalizeGenderSymbol(chr)
            val b = indexOf(table, chr, language) ?: break
            dest[offset + i] = b.toByte()
            i++
        }
        var count = i
        if (count < length) dest[offset + count++] = TERMINATOR_BYTE.toByte()
        return count
    }

    /** True when every character of [text] exists in the table of [language]. */
    public fun canEncode(text: String, language: Int): Boolean {
        val jp = language == LanguageId.JAPANESE
        val table = table(language)
        return text.all { c -> indexOf(table, if (jp) c else unNormalizeGenderSymbol(c), language) != null }
    }

    private fun indexOf(table: String, c: Char, language: Int): Int? {
        val index = table.indexOf(c)
        if (index == -1 || c == '“') return userFriendlyRemap(c, language)
        return if (index != TERMINATOR_BYTE) index else null
    }

    // Quotation marks are displayed differently based on the Gen3 game language.
    public fun quoteLeft(language: Int): Char = when (language) {
        LanguageId.ENGLISH, LanguageId.ITALIAN, LanguageId.SPANISH -> '“'
        LanguageId.FRENCH -> '«'
        LanguageId.GERMAN -> '„'
        else -> '『' // Invalid languages use JP quote
    }

    public fun quoteRight(language: Int): Char = when (language) {
        LanguageId.ENGLISH, LanguageId.ITALIAN, LanguageId.SPANISH -> '”'
        LanguageId.FRENCH -> '»'
        LanguageId.GERMAN -> '“'
        else -> '』' // Invalid languages use JP quote
    }

    /** Tries to remap the user input to a valid character. */
    private fun userFriendlyRemap(c: Char, language: Int): Int? = when (c) {
        '’' -> APOSTROPHE_BYTE // ’ -> '
        '“' -> if (language != LanguageId.GERMAN) QUOTE_LEFT_BYTE else QUOTE_RIGHT_BYTE
        '”' -> QUOTE_RIGHT_BYTE
        '«' -> QUOTE_LEFT_BYTE
        '»' -> QUOTE_RIGHT_BYTE
        '„' -> QUOTE_LEFT_BYTE
        '『' -> QUOTE_LEFT_BYTE
        '』' -> QUOTE_RIGHT_BYTE
        else -> null
    }

    private fun normalizeGenderSymbol(c: Char): Char = when (c) {
        HGM -> '♂'
        HGF -> '♀'
        else -> c
    }

    private fun unNormalizeGenderSymbol(c: Char): Char = when (c) {
        '♂' -> HGM
        '♀' -> HGF
        else -> c
    }
}

// Ported from PKHeX.Core/PKM/Strings/StringConverter2.cs (PKHeX, GPL-3.0, commit 641c26b).
// Not ported: Korean (Hangul) support, and the Mail encode/decode helpers (mail is not edited here).
package org.noxeng.coincell.pkcore.text

import org.noxeng.coincell.pkcore.data.CharTables

/** Generation 2 text encoding: per-language tables, ligature (de)flation for box names. */
public object StringConverter2 {
    private const val TERMINATOR = '\u0000'
    private const val LAP = '’' // Apostrophe

    // 'd 'l 'm 'r 's 't 'v : all are apostrophe-before.
    private const val LIGATURE_ENG = "dlmrstv"

    // c' d' j' l' m' n' p' s' 's t' u' y' : all are apostrophe-after besides index 8 ('s).
    private const val LIGATURE_FRE = "cdjlmnpsstuy"

    // Full-width digits/letters used as ligature glyphs, in index order.
    private const val LIGATURE_LIST = "０１２３４５６７８９ＡＢ"

    internal val tableEn: CharArray = CharTables.gen2English.toCharArray()
    internal val tableFre: CharArray = CharTables.gen2French.toCharArray()
    internal val tableIta: CharArray = CharTables.gen2Italian.toCharArray()
    internal val tableJp: CharArray = CharTables.gen2Japanese.toCharArray()

    private fun dict(language: Int): CharArray = when (language) {
        LanguageId.JAPANESE -> tableJp
        LanguageId.FRENCH, LanguageId.GERMAN -> tableFre
        LanguageId.ITALIAN, LanguageId.SPANISH -> tableIta
        else -> tableEn
    }

    /** Converts Generation 2 encoded data into a string. */
    public fun getString(data: ByteArray, offset: Int, length: Int, language: Int): String {
        val result = CharArray(length)
        val count = loadString(data, offset, length, result, language)
        return String(result, 0, count)
    }

    /** Decodes into [result]; returns the character count loaded. */
    public fun loadString(data: ByteArray, offset: Int, length: Int, result: CharArray, language: Int): Int {
        if (length == 0) return 0
        if ((data[offset].toInt() and 0xFF) == StringConverter1.TRADE_OT_CODE) { // In-game Trade
            result[0] = StringConverter1.TRADE_OT
            return 1
        }
        val dict = dict(language)
        var i = 0
        while (i < length) {
            val c = dict[data[offset + i].toInt() and 0xFF]
            if (c == TERMINATOR) break
            result[i] = c
            i++
        }
        return i
    }

    /** Converts a string to Generation 2 encoded data, writing into `dest[offset until offset+length]`. */
    public fun setString(
        dest: ByteArray,
        offset: Int,
        length: Int,
        value: CharSequence,
        maxLength: Int,
        language: Int,
        option: StringOption = StringOption.CLEAR_50,
    ): Int {
        StringConverter1.condition(dest, offset, length, option)
        if (value.isEmpty()) return 0
        if (value[0] == StringConverter1.TRADE_OT) {
            dest[offset] = StringConverter1.TRADE_OT_CODE.toByte()
            dest[offset + 1] = StringConverter1.TERMINATOR_CODE.toByte()
            return 2
        }
        // Deviation: also capped at the destination length (the C# would throw on overflow).
        val text = if (value.length > maxLength) value.subSequence(0, maxLength) else value
        val dict = dict(language)
        var i = 0
        while (i < text.length && i < length) {
            val index = StringConverter1.tryGetIndex(dict, text[i])
            if (index < 0) break
            dest[offset + i] = index.toByte()
            i++
        }
        if (i == length) return i
        dest[offset + i] = StringConverter1.TERMINATOR_CODE.toByte()
        return i + 1
    }

    /** Expands the ligature glyphs (e.g. full-width digits) into apostrophe pairs for display. */
    public fun inflateLigatures(result: String, language: Int): String {
        if (language == LanguageId.JAPANESE || language == LanguageId.KOREAN) return result
        val after = language == LanguageId.FRENCH || language == LanguageId.GERMAN
        val ligatures = if (after) LIGATURE_FRE else LIGATURE_ENG
        val sb = StringBuilder(result.length * 2)
        for (c in result) {
            val i = LIGATURE_LIST.indexOf(c)
            if (i == -1 || i >= ligatures.length) {
                sb.append(c)
                continue
            }
            val ligature = ligatures[i]
            if (after && i != 8) sb.append(ligature).append(LAP) else sb.append(LAP).append(ligature)
        }
        return if (sb.length == result.length) result else sb.toString()
    }

    /** Collapses apostrophe pairs into the single ligature glyph used by the game. */
    public fun deflateLigatures(value: CharSequence, language: Int): String {
        if (language == LanguageId.JAPANESE || language == LanguageId.KOREAN) return value.toString()
        val after = language == LanguageId.FRENCH || language == LanguageId.GERMAN
        val ligatures = if (after) LIGATURE_FRE else LIGATURE_ENG
        val result = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != LAP && c != '\'') {
                result.append(c)
                i++
                continue
            }
            if (after && result.isNotEmpty()) {
                val prev = result[result.length - 1]
                val ligatureIndex = ligatures.indexOf(prev)
                if (ligatureIndex != -1) {
                    // Deviation: the C# assigns `prev = ligatures[ligatureIndex]` (a no-op that just drops the
                    // apostrophe); the ligature glyph is what the game stores, so write that instead.
                    result[result.length - 1] = LIGATURE_LIST[ligatureIndex]
                    i++
                    continue
                }
            }
            if (i < value.length - 1) {
                val ligatureIndex = ligatures.indexOf(value[i + 1])
                if (ligatureIndex != -1) {
                    result.append(LIGATURE_LIST[ligatureIndex])
                    i += 2
                    continue
                }
            }
            result.append(c)
            i++
        }
        return result.toString()
    }
}

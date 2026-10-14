// Ported from PKHeX.Core/PKM/Strings/StringConverter1.cs (PKHeX, GPL-3.0, commit 641c26b).
// Korean (Hangul) handling of the original is intentionally not ported; see docs in the module README.
package com.therealnox.gbflash.pkcore.text

import com.therealnox.gbflash.pkcore.data.CharTables

/** Language ids used by the games (PKHeX `LanguageID`). */
public object LanguageId {
    public const val NONE: Int = 0
    public const val JAPANESE: Int = 1
    public const val ENGLISH: Int = 2
    public const val FRENCH: Int = 3
    public const val ITALIAN: Int = 4
    public const val GERMAN: Int = 5
    public const val SPANISH: Int = 7
    public const val KOREAN: Int = 8
}

/** How a destination buffer is pre-filled before a string is written (PKHeX `StringConverterOption`). */
public enum class StringOption {
    /** Leave the existing bytes untouched. */
    NONE,

    /** Fill with 0x00 first. */
    CLEAR_ZERO,

    /** Fill with the 0x50 terminator first. */
    CLEAR_50,

    /** Fill with 0x7F (space) first. */
    CLEAR_7F,
}

/** Generation 1 text encoding (also the base of [StringConverter2]). */
public object StringConverter1 {
    public const val TERMINATOR_CODE: Int = 0x50
    public const val TERMINATOR_ZERO: Int = 0x00
    public const val TRADE_OT_CODE: Int = 0x5D
    public const val SPACE_CODE: Int = 0x7F

    private const val TERMINATOR = '\u0000'
    internal const val TRADE_OT = '*'

    /** MR.MIME's "." (U+2024, not U+002E). */
    internal const val DOT = '․'

    internal const val HIRAGANA = "べぺへり"

    internal val tableEn: CharArray = CharTables.gen1English.toCharArray()
    internal val tableJp: CharArray = CharTables.gen1Japanese.toCharArray()

    /** Checks if the data is definitely of German origin (any byte 0xC0..0xC6, i.e. ÄÖÜäöüÈ). */
    public fun isG12German(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean {
        for (i in offset until offset + length) {
            if (isGermanicGlyph(data[i].toInt() and 0xFF)) return true
        }
        return false
    }

    private fun isGermanicGlyph(b: Int): Boolean = (b - 0xC0) in 0..6

    /** Converts Generation 1 encoded data into a string. */
    public fun getString(data: ByteArray, offset: Int, length: Int, jp: Boolean): String {
        val result = CharArray(length)
        val count = loadString(data, offset, length, result, jp)
        return String(result, 0, count)
    }

    /** Decodes into [result]; returns the character count loaded. */
    public fun loadString(data: ByteArray, offset: Int, length: Int, result: CharArray, jp: Boolean): Int {
        if (length == 0) return 0
        if ((data[offset].toInt() and 0xFF) == TRADE_OT_CODE) { // In-game Trade
            result[0] = TRADE_OT
            return 1
        }
        val dict = if (jp) tableJp else tableEn
        var i = 0
        while (i < length) {
            val c = dict[data[offset + i].toInt() and 0xFF]
            if (c == TERMINATOR) break
            result[i] = c
            i++
        }
        return i
    }

    /**
     * Converts a string to Generation 1 encoded data, writing into `dest[offset until offset+length]`.
     * @return bytes written (including the terminator when one was written)
     */
    public fun setString(
        dest: ByteArray,
        offset: Int,
        length: Int,
        value: CharSequence,
        maxLength: Int,
        jp: Boolean,
        option: StringOption = StringOption.CLEAR_50,
    ): Int {
        condition(dest, offset, length, option)
        if (value.isEmpty()) return 0
        if (value[0] == TRADE_OT) { // Handle "[TRAINER]"
            dest[offset] = TRADE_OT_CODE.toByte()
            dest[offset + 1] = TERMINATOR_CODE.toByte()
            return 2
        }
        // Deviation: also capped at the destination length (the C# would throw on overflow).
        val text = if (value.length > maxLength) value.subSequence(0, maxLength) else value
        val dict = if (jp) tableJp else tableEn
        var i = 0
        while (i < text.length && i < length) {
            val index = tryGetIndex(dict, text[i])
            if (index < 0) break
            dest[offset + i] = index.toByte()
            i++
        }
        if (i == length) return i
        dest[offset + i] = TERMINATOR_CODE.toByte()
        return i + 1
    }

    internal fun condition(dest: ByteArray, offset: Int, length: Int, option: StringOption) {
        when (option) {
            StringOption.CLEAR_ZERO -> dest.fill(0, offset, offset + length)
            StringOption.CLEAR_50 -> dest.fill(TERMINATOR_CODE.toByte(), offset, offset + length)
            StringOption.CLEAR_7F -> dest.fill(SPACE_CODE.toByte(), offset, offset + length)
            StringOption.NONE -> Unit
        }
    }

    /** Index of [c] in [dict], or -1 if it can't be encoded. Index 0 is never user-enterable. */
    internal fun tryGetIndex(dict: CharArray, c: Char): Int {
        val index = dict.indexOf(c)
        if (index == -1) return tryGetUserFriendlyRemap(dict, c)
        return if (index != 0) index else -1
    }

    // べ (U+3079), ぺ (U+307A), へ (U+3078), and り (U+308A) -> katakana equivalents.
    // Deviation: PKHeX would write 0xFF if the katakana isn't in the (non-Japanese) table; we reject instead.
    private fun tryGetUserFriendlyRemap(dict: CharArray, c: Char): Int {
        if (!HIRAGANA.contains(c)) return -1
        return dict.indexOf((c.code + 0x60).toChar())
    }
}

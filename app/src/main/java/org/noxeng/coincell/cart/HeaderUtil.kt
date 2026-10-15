package org.noxeng.coincell.cart

import java.security.MessageDigest

internal fun sha1Hex(data: ByteArray, from: Int = 0, to: Int = data.size): String =
    MessageDigest.getInstance("SHA-1")
        .digest(data.copyOfRange(from, to))
        .joinToString("") { "%02x".format(it) }

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

/** Hex dump, 16 bytes per line with offsets, for the debug view. */
fun ByteArray.hexDump(): String = buildString {
    for (row in indices step 16) {
        append("%04X  ".format(row))
        val end = minOf(row + 16, size)
        for (i in row until end) append("%02X ".format(this@hexDump[i]))
        append(" ".repeat((16 - (end - row)) * 3))
        append(" ")
        for (i in row until end) {
            val c = this@hexDump[i].toInt() and 0xFF
            append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
        append('\n')
    }
}

private val COLLAPSE = Regex("""(_)_+|(\u0000)\u0000+|(\s)\s+""")

/**
 * Title cleanup of RFD:105-111 / RFA: ASCII decode with replacement, strip trailing NULs, collapse
 * runs of `_`, NUL and whitespace, NUL -> `_`, keep only printable ASCII (Python `string.printable`).
 */
internal fun cleanTitle(raw: ByteArray): String {
    val ascii = buildString {
        for (b in raw) {
            val c = b.toInt() and 0xFF
            append(if (c < 0x80) c.toChar() else '�')
        }
    }.trimEnd('\u0000')
    val collapsed = COLLAPSE.replace(ascii) { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
    return collapsed.replace('\u0000', '_').filter { it.code in 0x20..0x7E || it in "\t\n\r\u000B\u000C" }
}

/** Save memory descriptor shown in the UI; [typeId] follows FlashGBX's tables (spec 5.3 / 5.6). */
data class SaveInfo(
    val status: Status,
    val typeId: Int?,
    val label: String,
    val sizeBytes: Int?,
    val note: String? = null,
) {
    enum class Status {
        /** Taken directly from the cartridge header. */
        FROM_HEADER,

        /** Header says "no save"; may still be wrong, ask the user. */
        NONE,

        /** Could not be decided from header alone. */
        UNRESOLVED,

        /** Header value not understood. */
        UNKNOWN,
    }
}

/** Common view of a parsed cartridge header. */
sealed interface CartHeader {
    val raw: ByteArray
    val title: String
    val logoValid: Boolean
    val headerChecksumValid: Boolean
    /** Logo area is blank or all-equal (open bus / nothing inserted). */
    val likelyNoCart: Boolean
    val save: SaveInfo
}

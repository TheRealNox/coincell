// Ported from PKHeX.Core/Util/BinaryCodedDecimal.cs plus the System.Buffers.Binary helpers it relies on
// (PKHeX, GPL-3.0, commit 641c26b).
package org.noxeng.coincell.pkcore.gb

internal fun ByteArray.u8(ofs: Int): Int = this[ofs].toInt() and 0xFF
internal fun ByteArray.setU8(ofs: Int, value: Int) {
    this[ofs] = value.toByte()
}

internal fun ByteArray.u16be(ofs: Int): Int = (u8(ofs) shl 8) or u8(ofs + 1)
internal fun ByteArray.setU16be(ofs: Int, value: Int) {
    this[ofs] = (value shr 8).toByte()
    this[ofs + 1] = value.toByte()
}

internal fun ByteArray.u16le(ofs: Int): Int = u8(ofs) or (u8(ofs + 1) shl 8)
internal fun ByteArray.setU16le(ofs: Int, value: Int) {
    this[ofs] = value.toByte()
    this[ofs + 1] = (value shr 8).toByte()
}

/** Big-endian 24 bit integer (Gen 2 money). */
internal fun ByteArray.u24be(ofs: Int): Int = (u8(ofs) shl 16) or (u8(ofs + 1) shl 8) or u8(ofs + 2)

/** 4-bit decimal encoding used by some Generation 1 values, most significant digit pair first. */
internal object Bcd {
    fun readBigEndian(data: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        for (i in offset until offset + length) {
            val b = data.u8(i)
            result = (result * 100) + (10 * (b shr 4)) + (b and 0xF)
        }
        return result
    }

    fun writeBigEndian(data: ByteArray, offset: Int, length: Int, value: Long) {
        var v = value
        for (i in offset + length - 1 downTo offset) {
            data[i] = ((((v / 10) % 10) shl 4) or (v % 10)).toByte()
            v /= 100
        }
    }
}

/** Byte-sum checksums used by Gen 1/2. */
internal object GbChecksum {
    /** Gen 1: complemented 8-bit sum of `data[start until end]` (PKHeX `SAV1.GetRBYChecksum`). */
    fun gen1(data: ByteArray, start: Int, end: Int): Int {
        var result = 0
        for (i in start until end) result += data.u8(i)
        return (result and 0xFF).inv() and 0xFF
    }

    /** Gen 2: 16-bit sum of `data[start..endInclusive]` (PKHeX `SAV2.GetChecksum`). */
    fun gen2(data: ByteArray, start: Int, endInclusive: Int): Int {
        var sum = 0
        for (i in start..endInclusive) sum += data.u8(i)
        return sum and 0xFFFF
    }
}

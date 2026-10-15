// Ported from PKHeX.Core/Saves/Util/SaveUtil.cs (GetTypeInfo, IsG1/IsG2, HasListAt, IsListValidG12) and
// SAV1.IsYellow* in Saves/SAV1.cs (PKHeX, GPL-3.0, commit 641c26b). Same rules, same order.
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.Region
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.UnsupportedSaveException

/** Result of save detection. */
internal data class SaveTypeInfo(val game: Game, val region: Region) {
    val generation: Int get() = game.generation
}

internal object SaveDetector {
    const val SIZE_G1_RAW = 0x8000
    const val SIZE_G2_RAW_U = 0x8000
    const val SIZE_G2_RAW_J = 0x10000

    /** @throws UnsupportedSaveException when the data is not a recognised Gen 1/2 save. */
    fun detect(data: ByteArray): SaveTypeInfo {
        if (data.size != SIZE_G1_RAW && data.size != SIZE_G2_RAW_J) {
            throw UnsupportedSaveException(
                "Unsupported save size: ${data.size} bytes (expected 32768, or 65536 for some Japanese Gen 2 saves). " +
                    "Pass the raw SRAM without any RTC footer.",
            )
        }
        isG1(data)?.let { return it }
        isG2(data)?.let { return it }
        throw UnsupportedSaveException(
            "Not a recognised Generation 1/2 Pokemon save: no valid party/box lists were found at the known offsets.",
        )
    }

    private fun isG1Int(d: ByteArray) = hasListAt(d, 0x2F2C, 0x30C0, 20)
    private fun isG1Jpn(d: ByteArray) = hasListAt(d, 0x2ED5, 0x302D, 30)
    private fun isG2GsInt(d: ByteArray) = hasListAt(d, 0x288A, 0x2D6C, 20)
    private fun isG2GsJpn(d: ByteArray) = hasListAt(d, 0x2D10, 0x283E, 30)
    private fun isG2GsKor(d: ByteArray) = hasListAt(d, 0x2DAE, 0x28CC, 20)
    private fun isG2CrystalInt(d: ByteArray) = hasListAt(d, 0x2865, 0x2D10, 20)
    private fun isG2CrystalJpn(d: ByteArray) = hasListAt(d, 0x2D10, 0x281A, 30)

    private fun hasListAt(data: ByteArray, offset1: Int, offset2: Int, maxCount: Int): Boolean =
        isListValidG12(data, offset1, maxCount) && isListValidG12(data, offset2, maxCount)

    /** A list is valid if its count is in range and the species list is capped by 0xFF. */
    private fun isListValidG12(data: ByteArray, offset: Int, maxCount: Int): Boolean {
        val count = data[offset].toInt() and 0xFF
        return count <= maxCount && (data[offset + 1 + count].toInt() and 0xFF) == 0xFF
    }

    private fun isG1(data: ByteArray): SaveTypeInfo? {
        if (data.size != SIZE_G1_RAW) return null
        if (isG1Jpn(data)) return SaveTypeInfo(if (isYellowJpn(data)) Game.YELLOW else Game.RED_BLUE, Region.JAPANESE)
        if (isG1Int(data)) return SaveTypeInfo(if (isYellowInt(data)) Game.YELLOW else Game.RED_BLUE, Region.INTERNATIONAL)
        return null
    }

    private fun isG2(data: ByteArray): SaveTypeInfo? {
        if (data.size != SIZE_G2_RAW_U && data.size != SIZE_G2_RAW_J) return null
        if (isG2GsInt(data)) return SaveTypeInfo(Game.GOLD_SILVER, Region.INTERNATIONAL)
        if (isG2CrystalInt(data)) return SaveTypeInfo(Game.CRYSTAL, Region.INTERNATIONAL)
        if (isG2GsJpn(data)) return SaveTypeInfo(Game.GOLD_SILVER, Region.JAPANESE)
        if (isG2CrystalJpn(data)) return SaveTypeInfo(Game.CRYSTAL, Region.JAPANESE)
        if (isG2GsKor(data)) return SaveTypeInfo(Game.GOLD_SILVER, Region.KOREAN)
        return null
    }

    fun isYellowInt(data: ByteArray): Boolean = isYellow(data[0x29C3].toInt() and 0xFF, data[0x271C].toInt() and 0xFF)
    fun isYellowJpn(data: ByteArray): Boolean = isYellow(data[0x29B9].toInt() and 0xFF, data[0x2712].toInt() and 0xFF)

    private fun isYellow(starter: Int, friendship: Int): Boolean {
        if (starter != 0) return starter == 0x54 // Pikachu
        return friendship != 0 // Initial Pikachu friendship is non-zero
    }
}

/** Creates the [SaveFile] matching the detected type. */
internal object SaveLoader {
    fun load(bytes: ByteArray, language: Int?): SaveFile {
        val info = SaveDetector.detect(bytes)
        if (info.region == Region.KOREAN) {
            throw UnsupportedSaveException("Korean Generation 2 saves are detected but not supported yet.")
        }
        return when (info.generation) {
            1 -> Sav1(bytes, info, language)
            else -> Sav2(bytes, info, language)
        }
    }
}

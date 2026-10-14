package com.therealnox.gbflash.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Parsed `QUERY_FW_INFO` reply (spec 2.2). */
data class FirmwareInfo(
    val cfwId: Char,
    val fwVer: Int,
    val pcbVer: Int,
    /** Unix seconds, unsigned 32 bit. */
    val fwTimestamp: Long,
    val deviceName: String?,
    val cartPowerCtrl: Boolean,
    val cartPresenceSwitch: Boolean,
    val cartModeSwitch: Boolean,
    val bootloaderReset: Boolean,
    val unregistered: Boolean,
) {
    val versionString: String get() = "$cfwId$fwVer"

    val pcbLabel: String
        get() = when (pcbVer) {
            5 -> "v1.0/v1.1"
            12 -> "v1.2"
            13 -> "v1.3"
            else -> "unknown ($pcbVer)"
        }

    val timestampString: String get() = Instant.ofEpochSecond(fwTimestamp).toString()

    /** Null if the device is usable, otherwise why it is rejected (spec 2.1 steps 4 and 7). */
    fun incompatibility(): String? = when {
        cfwId != 'L' -> "Not a GBFlash firmware (id '$cfwId', expected 'L')"
        pcbVer !in SUPPORTED_PCBS -> "Unsupported PCB version $pcbVer"
        fwVer < 1 -> "Invalid firmware version $fwVer"
        pcbVer == 5 || fwTimestamp < MIN_FW_TIMESTAMP -> "Firmware update required (build $timestampString)"
        fwVer < MIN_SUPPORTED_FW -> "Firmware $versionString is too old; this app needs L$MIN_SUPPORTED_FW or newer"
        else -> null
    }

    companion object {
        val SUPPORTED_PCBS = setOf(5, 12, 13)

        /** `FirmwareUpdateAvailable` cut-off: 2024-11-03 (HW:183-190). */
        const val MIN_FW_TIMESTAMP = 1730592000L

        /** Conservative: pre-12 firmware lacks ACKs, name and flags (spec 9.2 Q4) and is not ported. */
        const val MIN_SUPPORTED_FW = 12

        /**
         * Reads the reply to [Op.QUERY_FW_INFO] from [transport]: size byte (must be 8), 8 info bytes
         * (`>cHBI`), then for fw>=12 a length-prefixed name and two flag bytes.
         */
        fun read(transport: Transport, firstByteTimeoutMs: Long): FirmwareInfo {
            val size = transport.readByte(firstByteTimeoutMs)
            if (size != 8) throw UnexpectedResponseException("Firmware info size byte is $size, expected 8")
            val info = ByteBuffer.wrap(transport.readExact(8)) // big endian by default
            val cfwId = (info.get().toInt() and 0xFF).toChar()
            val fwVer = info.short.toInt() and 0xFFFF
            val pcbVer = info.get().toInt() and 0xFF
            val fwTs = info.int.toLong() and 0xFFFFFFFFL

            var name: String? = null
            var flags1 = 0
            var flags2 = 0
            if (cfwId == 'L' && fwVer >= 12) {
                val n = transport.readByte()
                name = decodeName(transport.readExact(n))
                flags1 = transport.readByte()
                flags2 = transport.readByte()
            }
            return FirmwareInfo(
                cfwId = cfwId,
                fwVer = fwVer,
                pcbVer = pcbVer,
                fwTimestamp = fwTs,
                deviceName = name?.ifEmpty { null },
                cartPowerCtrl = flags1 and 0x01 != 0,
                cartPresenceSwitch = flags1 and 0x02 != 0,
                cartModeSwitch = flags1 and 0x04 != 0,
                bootloaderReset = flags2 and 0x01 != 0,
                unregistered = flags2 and 0x80 != 0,
            )
        }

        private fun decodeName(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .replace("\u0000", "")
                .trim()
        } catch (_: CharacterCodingException) {
            "Unnamed Device"
        }
    }
}

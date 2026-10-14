package com.therealnox.gbflash.protocol

import com.therealnox.gbflash.cart.AgbCartIo
import com.therealnox.gbflash.cart.AgbCartWriter

/**
 * The GBA save write path: SRAM (`C4`), EEPROM (`C6`), Flash data (`C7`), the Flash sector erase sequence and the
 * GPIO clock register writes. This file is the only place in the app that knows these opcodes and the erase command
 * values; they are not part of [Op] on purpose. Obtained solely through [Commands.agbCartWriter], which only the
 * restore operation in [DeviceSession] calls.
 *
 * Like the DMG writer, every request carries its own setup (`TRANSFER_SIZE`, `ADDRESS`).
 */
internal class AgbCartWriteAccess(
    private val commands: Commands,
    private val transport: Transport,
) : AgbCartWriter {
    override fun writeSram(address: Int, data: ByteArray) {
        require(data.size in 1..AgbCartWriter.MAX_REQUEST) { "SRAM write of ${data.size} bytes is not allowed" }
        require(address >= 0 && address + data.size <= WINDOW) { "SRAM write 0x%X+${data.size} is outside the 64 KiB save window".format(address) }
        commands.setVar(FwVar.TRANSFER_SIZE, data.size.toLong())
        commands.setVar(FwVar.ADDRESS, address.toLong())
        transport.write(AGB_CART_WRITE_SRAM)
        transport.write(data)
        transport.waitAck()
    }

    override fun writeEeprom(eepromType: Int, blockAddress: Int, data: ByteArray) {
        require(eepromType == 1 || eepromType == 2) { "EEPROM type $eepromType" }
        require(data.isNotEmpty() && data.size % 8 == 0 && data.size <= MAX_EEPROM_REQUEST) { "EEPROM write of ${data.size} bytes is not allowed" }
        commands.setVar(FwVar.TRANSFER_SIZE, data.size.toLong())
        commands.setVar(FwVar.ADDRESS, blockAddress.toLong())
        transport.write(byteArrayOf(AGB_CART_WRITE_EEPROM.toByte(), eepromType.toByte()))
        transport.write(data)
        transport.waitAck()
    }

    override fun writeFlash(address: Int, data: ByteArray, atmelPages: Boolean) {
        require(data.size in 1..AgbCartWriter.MAX_REQUEST) { "Flash write of ${data.size} bytes is not allowed" }
        require(address >= 0 && address + data.size <= WINDOW) { "Flash write 0x%X+${data.size} is outside the 64 KiB save window".format(address) }
        if (atmelPages) require(address % ATMEL_PAGE == 0 && data.size % ATMEL_PAGE == 0) { "Atmel writes are whole 128 byte pages" }
        commands.setVar(FwVar.TRANSFER_SIZE, data.size.toLong())
        // The Atmel path addresses 128 byte pages (LK:3718), the others bytes.
        commands.setVar(FwVar.ADDRESS, (if (atmelPages) address / ATMEL_PAGE else address).toLong())
        transport.write(byteArrayOf(AGB_CART_WRITE_FLASH_DATA.toByte(), if (atmelPages) 2 else 1))
        transport.write(data)
        val previous = transport.timeoutMs
        transport.timeoutMs = FLASH_ACK_TIMEOUT_MS // the firmware ACKs after the page is programmed
        try {
            transport.waitAck()
        } finally {
            transport.timeoutMs = previous
        }
    }

    override fun eraseFlashSector(sectorAddress: Int) {
        require(sectorAddress in 0 until WINDOW) { "Sector 0x%X is outside the 64 KiB save window".format(sectorAddress) }
        flashCommands(
            0x5555 to 0xAA, 0x2AAA to 0x55, 0x5555 to ERASE_SETUP,
            0x5555 to 0xAA, 0x2AAA to 0x55, sectorAddress to SECTOR_ERASE,
        )
    }

    override fun writeGpio(address: Int, value: Int) {
        require(address in AgbCartIo.GPIO_ADDRESSES) { "0x%X is not a GPIO register".format(address) }
        val a = address shr 1
        transport.tryWrite(
            byteArrayOf(
                AGB_CART_WRITE.toByte(),
                (a ushr 24).toByte(), (a ushr 16).toByte(), (a ushr 8).toByte(), a.toByte(),
                (value ushr 8).toByte(), value.toByte(),
            ),
        )
    }

    private fun flashCommands(vararg cmds: Pair<Int, Int>) {
        val buf = ByteArray(3 + cmds.size * 6)
        buf[0] = CART_WRITE_FLASH_CMD.toByte()
        buf[1] = 0
        buf[2] = cmds.size.toByte()
        cmds.forEachIndexed { i, (addr, value) ->
            val o = 3 + i * 6
            buf[o] = (addr ushr 24).toByte()
            buf[o + 1] = (addr ushr 16).toByte()
            buf[o + 2] = (addr ushr 8).toByte()
            buf[o + 3] = addr.toByte()
            buf[o + 4] = (value ushr 8).toByte()
            buf[o + 5] = value.toByte()
        }
        transport.write(buf)
        val reply = transport.readByte()
        if (reply != 0x01) throw UnexpectedResponseException("Flash command reply 0x%02X, expected 0x01".format(reply))
    }

    private companion object {
        /** `AGB_CART_WRITE` (LK:59). */
        const val AGB_CART_WRITE = 0xC2

        /** `AGB_CART_WRITE_SRAM` (LK:61). */
        const val AGB_CART_WRITE_SRAM = 0xC4

        /** `AGB_CART_WRITE_EEPROM` (LK:63). */
        const val AGB_CART_WRITE_EEPROM = 0xC6

        /** `AGB_CART_WRITE_FLASH_DATA` (LK:64). */
        const val AGB_CART_WRITE_FLASH_DATA = 0xC7

        /** `CART_WRITE_FLASH_CMD` (LK:71). */
        const val CART_WRITE_FLASH_CMD = 0xD4

        /** JEDEC erase setup and sector erase command values (LK:3724-3731). */
        const val ERASE_SETUP = 0x80
        const val SECTOR_ERASE = 0x30

        const val WINDOW = 0x10000
        const val MAX_EEPROM_REQUEST = 0x100
        const val ATMEL_PAGE = 128
        const val FLASH_ACK_TIMEOUT_MS = 3000L
    }
}

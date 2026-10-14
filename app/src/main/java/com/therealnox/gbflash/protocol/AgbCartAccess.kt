package com.therealnox.gbflash.protocol

import com.therealnox.gbflash.cart.AgbCartIo

/**
 * Backup / detection view of a powered GBA cartridge (see [AgbCartIo] for the exact list of what may be written).
 * Every read request carries its own full setup (`TRANSFER_SIZE`, `ADDRESS`), so nothing depends on the firmware
 * advancing `ADDRESS` between requests.
 *
 * The cartridge write opcodes `C2` (ROM-space writes: GPIO clock registers and the bootleg bank register) and `D4`
 * (Flash command lists) are private to this file and only ever sent with the fixed sequences below. The save data
 * writers (`C4`, `C6`, `C7`) and the erase / program sequences do not exist here; they are in `AgbCartWriteAccess`.
 */
internal class AgbCartAccess(
    private val commands: Commands,
    private val transport: Transport,
) : AgbCartIo {
    /**
     * AGB prelude (spec 6.1.2, LK:3446-3450): liveness, cartridge power, status register mask / value, then mode and
     * voltage re-asserted from the platform (always 3.3 V, the only voltage code in the app that can be used here).
     */
    fun prepare() {
        check(commands.platform == CartPlatform.GBA) { "GBA mode is not active" }
        commands.checkActive()
        commands.cartPowerOn()
        commands.setVar(FwVar.STATUS_REGISTER_MASK, 0x80)
        commands.setVar(FwVar.STATUS_REGISTER_VALUE, 0x80)
        transport.writeAck(CartPlatform.GBA.modeOpcode)
        transport.writeAck(CartPlatform.GBA.voltageOpcode)
    }

    override fun saveRead(address: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val n = minOf(AgbCartIo.MAX_READ_REQUEST, length - pos)
            commands.setVar(FwVar.TRANSFER_SIZE, n.toLong())
            commands.setVar(FwVar.ADDRESS, (address + pos).toLong())
            transport.write(Op.AGB_CART_READ_SRAM)
            transport.readExact(n).copyInto(out, pos)
            pos += n
        }
        return out
    }

    override fun eepromRead(eepromType: Int, blockAddress: Int, length: Int): ByteArray {
        require(eepromType == 1 || eepromType == 2) { "EEPROM type $eepromType" }
        commands.setVar(FwVar.TRANSFER_SIZE, length.toLong())
        commands.setVar(FwVar.ADDRESS, blockAddress.toLong())
        transport.write(byteArrayOf(Op.AGB_CART_READ_EEPROM.toByte(), eepromType.toByte()))
        return transport.readExact(length)
    }

    override fun romRead(byteAddress: Int, length: Int): ByteArray {
        commands.setVar(FwVar.TRANSFER_SIZE, length.toLong())
        commands.setVar(FwVar.ADDRESS, (byteAddress shr 1).toLong())
        transport.write(Op.AGB_CART_READ)
        return transport.readExact(length)
    }

    // ---- bank select -------------------------------------------------------------------------

    override fun selectFlashBank(bank: Int) {
        require(bank in 0..1) { "Flash bank $bank" }
        flashCommands(0x5555 to 0xAA, 0x2AAA to 0x55, 0x5555 to 0xB0, 0x0000 to bank)
        transport.sleep(BANK_SETTLE_MS)
    }

    override fun selectBootlegBank(bank: Int) {
        require(bank in 0..1) { "Bootleg bank $bank" }
        romSpaceWrite(BOOTLEG_BANK_REGISTER, bank)
        transport.sleep(BANK_SETTLE_MS)
    }

    // ---- Flash identify ----------------------------------------------------------------------

    override fun flashIdProbeFlip(original: Int) = flashCommands(0x0004 to ((original xor 0xFF) and 0xFF))

    override fun flashIdProbeRestore(original: Int) = flashCommands(0x0004 to (original and 0xFF))

    override fun flashIdEnter() = flashCommands(0x5555 to 0xAA, 0x2AAA to 0x55, 0x5555 to 0x90)

    override fun flashIdExit() {
        flashCommands(0x5555 to 0xAA, 0x2AAA to 0x55, 0x5555 to 0xF0)
        transport.sleep(ID_SETTLE_MS)
        flashCommands(0x0000 to 0xF0)
        transport.sleep(ID_SETTLE_MS)
    }

    override fun flashIdRestoreBytes(at5555: Int, at2AAA: Int, at0000: Int) =
        flashCommands(0x5555 to (at5555 and 0xFF), 0x2AAA to (at2AAA and 0xFF), 0x0000 to (at0000 and 0xFF))

    // ---- GPIO clock --------------------------------------------------------------------------

    override fun gpioWrite(address: Int, value: Int) {
        require(address in AgbCartIo.GPIO_ADDRESSES) {
            "0x%X is not a GPIO register; backups must not write cartridge memory".format(address)
        }
        romSpaceWrite(address, value)
    }

    override fun readGpioRtc(): ByteArray {
        transport.write(Op.AGB_READ_GPIO_RTC)
        return transport.readExact(8)
    }

    override fun sleep(ms: Long) = transport.sleep(ms)

    // ---- the two write primitives, private ---------------------------------------------------

    /** `C2 <byteAddress >> 1 u32> <value u16>` with ACK and resync (spec 3.1 `agbCartWrite`). */
    private fun romSpaceWrite(byteAddress: Int, value: Int) {
        val a = byteAddress shr 1
        transport.tryWrite(
            byteArrayOf(
                AGB_CART_WRITE.toByte(),
                (a ushr 24).toByte(), (a ushr 16).toByte(), (a ushr 8).toByte(), a.toByte(),
                (value ushr 8).toByte(), value.toByte(),
            ),
        )
    }

    /**
     * `D4 00 <n> n x (<addr u32> <value u16>)`, save-memory addressing (`flash` = 0), reply byte must be `01`
     * (LK:721-756). Only the callers above exist, so the values sent are exactly the identify and bank sequences.
     */
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

        /** `CART_WRITE_FLASH_CMD` (LK:71). */
        const val CART_WRITE_FLASH_CMD = 0xD4

        const val BOOTLEG_BANK_REGISTER = 0x1000000
        const val BANK_SETTLE_MS = 50L
        const val ID_SETTLE_MS = 10L
    }
}

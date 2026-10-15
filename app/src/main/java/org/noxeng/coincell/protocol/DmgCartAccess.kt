package org.noxeng.coincell.protocol

import org.noxeng.coincell.cart.DmgCartIo

/**
 * Save-backup view of a powered DMG cartridge: register writes (below 0x8000 only), clock toggles
 * and RAM reads. There is intentionally no SRAM write path (no `B3`): that is `DmgCartWriteAccess`,
 * which this class neither extends nor can reach.
 */
internal class DmgCartAccess(
    private val commands: Commands,
    private val transport: Transport,
) : DmgCartIo {
    /** Spec 6.1 steps 0-4 (without the dev-cart probe, which only matters for flash carts). */
    fun prepare() {
        check(commands.platform == CartPlatform.GB_GBC) { "GB / GBC mode is not active" }
        commands.checkActive()
        commands.cartPowerOn()
        commands.setVar(FwVar.STATUS_REGISTER_MASK, 0x80)
        commands.setVar(FwVar.STATUS_REGISTER_VALUE, 0x80)
        transport.writeAck(Op.DMG_MBC_RESET)
        transport.writeAck(CartPlatform.GB_GBC.modeOpcode)
        commands.setVar(FwVar.DMG_WRITE_CS_PULSE, 0)
        commands.setVar(FwVar.DMG_READ_CS_PULSE, 0)
    }

    override fun writeRegister(address: Int, value: Int) {
        require(address in 0..MAX_REGISTER_ADDRESS) {
            "0x%X is not a mapper register; backups must not write cartridge memory".format(address)
        }
        transport.tryWrite(
            byteArrayOf(
                Op.DMG_CART_WRITE.toByte(),
                (address ushr 24).toByte(), (address ushr 16).toByte(), (address ushr 8).toByte(), address.toByte(),
                value.toByte(),
            ),
        )
    }

    override fun clockToggle(count: Int) {
        transport.writeAck(byteArrayOf(Op.CLK_TOGGLE.toByte(), 0, 0, (count ushr 8).toByte(), count.toByte()))
    }

    /**
     * `dmgRamRead` (spec 3.1): the full setup is repeated for every call, so nothing depends on the
     * firmware auto-incrementing ADDRESS in RAM access mode.
     */
    override fun ramRead(offset: Int, length: Int): ByteArray {
        commands.setVar(FwVar.TRANSFER_SIZE, length.toLong())
        commands.setVar(FwVar.ADDRESS, 0xA000L + offset)
        commands.setVar(FwVar.DMG_ACCESS_MODE, 3)
        commands.setVar(FwVar.DMG_READ_CS_PULSE, 1)
        transport.write(Op.DMG_CART_READ)
        val data = transport.readExact(length)
        commands.setVar(FwVar.DMG_READ_CS_PULSE, 0)
        return data
    }

    /** Spec 6.1.5 tail: read pulse off, address lines to inputs (hot-unplug safety). */
    fun releaseBus() {
        commands.setVar(FwVar.DMG_READ_CS_PULSE, 0)
        transport.writeAck(Op.SET_ADDR_AS_INPUTS)
    }

    private companion object {
        const val MAX_REGISTER_ADDRESS = 0x7FFF
    }
}

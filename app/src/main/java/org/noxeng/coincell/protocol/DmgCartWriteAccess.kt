package org.noxeng.coincell.protocol

import org.noxeng.coincell.cart.DmgCartWriter

/**
 * SRAM write path (`B3`, spec 3.1 `dmgRamWrite`). This file is the only place in the app that knows
 * the opcode; it is not part of [Op] on purpose. Obtained solely through [Commands.dmgCartWriter],
 * which only the restore operation in [DeviceSession] calls.
 */
internal class DmgCartWriteAccess(
    private val commands: Commands,
    private val transport: Transport,
) : DmgCartWriter {
    /**
     * One request per call with the full setup every time (nothing depends on the firmware
     * auto-incrementing ADDRESS): TRANSFER_SIZE, ADDRESS, DMG_ACCESS_MODE=4, DMG_WRITE_CS_PULSE=1,
     * `B3`, data, ACK; afterwards ADDRESS=0 and DMG_WRITE_CS_PULSE=0 (spec 3.1, 6.2).
     */
    override fun writeRam(offset: Int, data: ByteArray) {
        require(data.size in 1..DmgCartWriter.MAX_REQUEST) { "SRAM write of ${data.size} bytes is not allowed" }
        require(offset in 0 until DmgCartWriter.RAM_WINDOW && offset + data.size <= DmgCartWriter.RAM_WINDOW) {
            "SRAM write 0x%X+${data.size} is outside the 0xA000-0xBFFF RAM window".format(offset)
        }
        commands.setVar(FwVar.TRANSFER_SIZE, data.size.toLong())
        commands.setVar(FwVar.ADDRESS, RAM_BASE + offset)
        commands.setVar(FwVar.DMG_ACCESS_MODE, 4)
        commands.setVar(FwVar.DMG_WRITE_CS_PULSE, 1)
        transport.write(DMG_CART_WRITE_SRAM)
        transport.write(data)
        transport.waitAck()
        commands.setVar(FwVar.ADDRESS, 0)
        commands.setVar(FwVar.DMG_WRITE_CS_PULSE, 0)
    }

    override fun release() {
        commands.setVar(FwVar.DMG_WRITE_CS_PULSE, 0)
        commands.setVar(FwVar.ADDRESS, 0)
    }

    private companion object {
        /** `DMG_CART_WRITE_SRAM` (LK:31-80). */
        const val DMG_CART_WRITE_SRAM = 0xB3
        const val RAM_BASE = 0xA000L
    }
}

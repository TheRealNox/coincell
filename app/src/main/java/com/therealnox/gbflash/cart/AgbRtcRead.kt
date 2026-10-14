package com.therealnox.gbflash.cart

/**
 * Reading the GBA GPIO real-time clock (Mapper.py `AGB_GPIO.HasRTC`, `RTCReadStatus`; LK:1220-1242, 3833-3847).
 * Everything here only reads, apart from the GPIO register writes of the read-status command (ROM address space
 * 0xC4 / 0xC6 / 0xC8) which go through [AgbCartIo.gpioWrite]. The commands that WRITE the clock are in
 * [AgbRtcWrite], reachable from the restore engine only.
 */
internal object AgbRtcRead {
    /** S-3511A command: read the status register (Mapper.py:1779). */
    private const val CMD_READ_STATUS = 0x63

    sealed interface Probe {
        /** [reply] is the 8 byte `CA` answer `[status, YY, MM, DD, WW, HH, MI, SS]`. */
        class Present(val reply: ByteArray) : Probe

        class Absent(val reason: String) : Probe
    }

    /**
     * `HasRTC(buffer)` for firmware >= 12: `CA`, then the clock counts as present if the status register's power
     * flag (bit 7) is clear and the first 6 clock bytes differ from the 6 ROM bytes at 0xC4 (which is what a cart
     * without a clock shows through the same window).
     */
    fun probe(io: AgbCartIo, log: (String) -> Unit = {}): Probe {
        val reply = io.readGpioRtc()
        require(reply.size == 8) { "CA answered ${reply.size} bytes" }
        val status = reply[0].toInt() and 0xFF
        log("RTC status register 0x%02X, date %s".format(status, reply.copyOfRange(1, 8).joinToString(" ") { "%02X".format(it) }))
        if (status and 0x80 != 0) return Probe.Absent("The clock's power flag is set (empty battery or never set), so it is reported as absent.")
        val rom = io.romRead(AgbCartIo.GPIO_DAT, 6)
        if (rom.contentEquals(reply.copyOfRange(1, 7))) return Probe.Absent("The cartridge returns the same bytes with and without the clock mapped.")
        return Probe.Present(reply)
    }

    /** The footer snapshot of a [Probe.Present]: the 7 date bytes, the status register read over GPIO, [nowSeconds]. */
    fun snapshot(io: AgbCartIo, present: Probe.Present, nowSeconds: Long): AgbRtcSnapshot =
        AgbRtcSnapshot(present.reply.copyOfRange(1, 8), readStatus(io), nowSeconds)

    /** `RTCReadStatus` (Mapper.py:1829-1846): the S-3511A status register, read bit by bit through the GPIO port. */
    fun readStatus(io: AgbCartIo): Int {
        io.gpioWrite(AgbCartIo.GPIO_RE, 1) // enable RTC mapping
        io.gpioWrite(AgbCartIo.GPIO_DAT, 1)
        io.gpioWrite(AgbCartIo.GPIO_DAT, 5)
        io.gpioWrite(AgbCartIo.GPIO_CNT, 7) // write enable
        sendCommand(io, CMD_READ_STATUS)
        io.gpioWrite(AgbCartIo.GPIO_CNT, 5) // read enable
        val data = readByte(io)
        io.gpioWrite(AgbCartIo.GPIO_DAT, 1)
        io.gpioWrite(AgbCartIo.GPIO_DAT, 1)
        io.gpioWrite(AgbCartIo.GPIO_RE, 0) // disable RTC mapping
        return data
    }

    /** `RTCCommand`: 8 bits, MSB first, 3 x data with clock low then clock high. */
    private fun sendCommand(io: AgbCartIo, command: Int) {
        for (i in 0 until 8) {
            val bit = (command shr (7 - i)) and 1
            repeat(3) { io.gpioWrite(AgbCartIo.GPIO_DAT, 4 or (bit shl 1)) }
            io.gpioWrite(AgbCartIo.GPIO_DAT, 5 or (bit shl 1))
        }
    }

    /** `RTCReadData`: 8 bits, LSB first; the bit is bit 1 of the data port after the clock rises. */
    private fun readByte(io: AgbCartIo): Int {
        var data = 0
        for (i in 0 until 8) {
            repeat(5) { io.gpioWrite(AgbCartIo.GPIO_DAT, 4) }
            io.gpioWrite(AgbCartIo.GPIO_DAT, 5)
            val port = io.romRead(AgbCartIo.GPIO_DAT, 2)[0].toInt() and 0xFF
            val bit = (port and 2) shr 1
            data = (data shr 1) or (bit shl 7)
        }
        return data
    }
}

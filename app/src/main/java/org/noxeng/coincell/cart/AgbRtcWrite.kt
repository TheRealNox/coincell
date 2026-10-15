package org.noxeng.coincell.cart

/**
 * Writing the GBA GPIO real-time clock (Mapper.py `WriteRTCDict`, `RTCWriteStatus`, `RTCWriteData`). This is the
 * only code that sends the S-3511A WRITE commands, and it only runs inside the restore engine, through
 * [AgbCartWriter.writeGpio] (ROM address space 0xC4 / 0xC6 / 0xC8).
 */
internal object AgbRtcWrite {
    private const val CMD_WRITE_STATUS = 0x62
    private const val CMD_WRITE_DATE = 0x64

    /** Writes the date (7 BCD bytes) and then the status register, the order FlashGBX uses (Mapper.py:2033-2038). */
    fun write(writer: AgbCartWriter, time: AgbRtcTime) {
        begin(writer)
        sendCommand(writer, CMD_WRITE_DATE)
        for (b in time.dateBytes()) sendData(writer, b.toInt() and 0xFF)
        end(writer)

        begin(writer)
        sendCommand(writer, CMD_WRITE_STATUS)
        sendData(writer, time.status)
        end(writer)
    }

    private fun begin(w: AgbCartWriter) {
        w.writeGpio(AgbCartIo.GPIO_RE, 1) // enable RTC mapping
        w.writeGpio(AgbCartIo.GPIO_DAT, 1)
        w.writeGpio(AgbCartIo.GPIO_DAT, 5)
        w.writeGpio(AgbCartIo.GPIO_CNT, 7) // write enable
    }

    private fun end(w: AgbCartWriter) {
        w.writeGpio(AgbCartIo.GPIO_DAT, 1)
        w.writeGpio(AgbCartIo.GPIO_DAT, 1)
        w.writeGpio(AgbCartIo.GPIO_RE, 0) // disable RTC mapping
    }

    /** MSB first. */
    private fun sendCommand(w: AgbCartWriter, command: Int) {
        for (i in 0 until 8) bit(w, (command shr (7 - i)) and 1)
    }

    /** LSB first. */
    private fun sendData(w: AgbCartWriter, data: Int) {
        for (i in 0 until 8) bit(w, (data shr i) and 1)
    }

    private fun bit(w: AgbCartWriter, bit: Int) {
        repeat(3) { w.writeGpio(AgbCartIo.GPIO_DAT, 4 or (bit shl 1)) }
        w.writeGpio(AgbCartIo.GPIO_DAT, 5 or (bit shl 1))
    }
}

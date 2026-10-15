package org.noxeng.coincell.cart

/** Clock values to write into the MBC3 registers S, M, H, DL, DH (spec 6.4 `WriteRTC`). */
class RtcTime(
    val seconds: Int,
    val minutes: Int,
    val hours: Int,
    /** 0..511 */
    val days: Int,
    /** The 9-bit day counter overflowed while advancing; written as DH bit 7. */
    val carry: Boolean,
) : ClockDescription {
    /** Register values in the order they are written (8 = S .. 12 = DH). The halt bit is always 0. */
    fun registers(): IntArray = intArrayOf(
        seconds % 60,
        minutes % 60,
        hours % 24,
        days and 0xFF,
        ((days shr 8) and 1) or (if (carry) 0x80 else 0),
    )

    override fun describe(): String = "${days}d %02d:%02d:%02d".format(hours, minutes, seconds) + if (carry) " (day counter overflowed)" else ""
}

/** Pure clock arithmetic for "restore clock from backup". */
object RtcAdvance {
    private const val DAY = 86_400L

    /**
     * The clock to write for [footer] (the 48-byte backup footer) at wall-clock time [nowSeconds].
     *
     * If [advance] and the footer timestamp lies in the past, the elapsed seconds are added to the
     * backed up `days:h:m:s`. This is the "Kotlin may instead" formula of spec 6.4 step 2, not
     * FlashGBX's calendar-date arithmetic: the two differ only in how many days are counted around
     * local midnight (FlashGBX counts calendar-date changes, this counts 24 h blocks of real elapsed
     * time, which is what a running clock would have done). When the day counter exceeds 511 it wraps
     * and [RtcTime.carry] is set. A footer of 48 zero bytes means "reset to 0d 00:00:00" and is never
     * advanced. The carry and halt bits stored in the backup are not written back (spec 6.4).
     */
    fun compute(footer: ByteArray, nowSeconds: Long, advance: Boolean): RtcTime {
        val snap = RtcSnapshot.fromFooter(footer)
        if (footer.all { it == 0.toByte() }) return RtcTime(0, 0, 0, 0, false)
        val base = ((snap.days.toLong() * 24 + snap.hours) * 60 + snap.minutes) * 60 + snap.seconds
        val elapsed = if (advance && snap.timestamp > 0 && nowSeconds > snap.timestamp) nowSeconds - snap.timestamp else 0L
        val total = base + elapsed
        val days = total / DAY
        val rem = (total % DAY).toInt()
        val overflow = days >= 512
        return RtcTime(rem % 60, rem / 60 % 60, rem / 3600, (days % 512).toInt(), overflow)
    }
}

/** MBC3 clock write (spec 6.4 `WriteRTCDict`), the only RTC code that writes data. */
internal object Mbc3RtcWrite {
    private const val TOGGLES = 50

    /**
     * latch, halt (DH = 0x40), then S, M, H, DL, DH one by one (the DH write also clears the halt
     * bit), latch again, deselect the register and disable RAM. RAM data writes (`0xA000`) go through
     * [writer]; everything else through [io].
     */
    fun write(io: DmgCartIo, writer: DmgCartWriter, time: RtcTime) {
        val regs = time.registers()
        fun sram(value: Int) = writer.writeRam(0, byteArrayOf(value.toByte()))
        io.writeRegister(0x0000, 0x0A)
        io.clockToggle(TOGGLES)
        io.writeRegister(0x0000, 0x0A)
        io.clockToggle(TOGGLES)
        io.writeRegister(0x6000, 0x00)
        io.clockToggle(TOGGLES)
        io.writeRegister(0x6000, 0x01)
        io.clockToggle(TOGGLES)
        io.writeRegister(0x4000, 0x0C)
        io.clockToggle(TOGGLES)
        sram(0x40)
        for (i in 8..12) {
            io.clockToggle(TOGGLES)
            io.writeRegister(0x4000, i)
            io.clockToggle(TOGGLES)
            sram(regs[i - 8])
        }
        io.clockToggle(TOGGLES)
        io.writeRegister(0x6000, 0x00)
        io.clockToggle(TOGGLES)
        io.writeRegister(0x6000, 0x01)
        io.writeRegister(0x4000, 0x00)
        io.writeRegister(0x0000, 0x00)
    }
}

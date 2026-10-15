package org.noxeng.coincell.cart

import org.noxeng.coincell.protocol.ReadTimeoutException

/** Latched MBC3 clock registers (S, M, H, DL, DH) plus the host time of the read (spec 6.4 / 7). */
class RtcSnapshot(
    val seconds: Int,
    val minutes: Int,
    val hours: Int,
    val dayLow: Int,
    val dayHigh: Int,
    /** Unix seconds when the registers were read. */
    override val timestamp: Long,
) : ClockValues {
    val days: Int get() = dayLow or ((dayHigh and 0x01) shl 8)
    val halted: Boolean get() = dayHigh and 0x40 != 0
    val dayCarry: Boolean get() = dayHigh and 0x80 != 0
    override val plausible: Boolean get() = seconds < 60 && minutes < 60 && hours < 24

    /**
     * The 48-byte footer shared with FlashGBX / VBA-M style emulators: S,M,H,DL,DH as u32 LE, the
     * same 20 bytes again, then the u64 LE timestamp.
     */
    fun toFooter(): ByteArray {
        val out = ByteArray(FOOTER_SIZE)
        val regs = intArrayOf(seconds, minutes, hours, dayLow, dayHigh)
        for (copy in 0..1) for (i in regs.indices) out[copy * 20 + i * 4] = regs[i].toByte()
        for (i in 0 until 8) out[40 + i] = (timestamp ushr (8 * i)).toByte()
        return out
    }

    override fun describe(): String = buildString {
        append("${days}d %02d:%02d:%02d".format(hours, minutes, seconds))
        append(if (halted) ", halted" else ", running")
        if (dayCarry) append(", day counter carry set")
        if (!plausible) append(" (values out of range)")
    }

    companion object {
        const val FOOTER_SIZE = 0x30

        fun fromFooter(footer: ByteArray): RtcSnapshot {
            require(footer.size == FOOTER_SIZE) { "RTC footer must be $FOOTER_SIZE bytes" }
            fun reg(i: Int) = footer[i * 4].toInt() and 0xFF
            val ts = (0 until 8).fold(0L) { acc, i -> acc or ((footer[40 + i].toLong() and 0xFF) shl (8 * i)) }
            return RtcSnapshot(reg(0), reg(1), reg(2), reg(3), reg(4), ts)
        }
    }
}

/** MBC3 real-time clock access (spec 5.4 and 6.4). Register writes only, plus reads. */
internal object Mbc3Rtc {
    private const val TOGGLES = 60

    private fun enable(io: DmgCartIo) = io.writeRegister(0x0000, 0x0A)

    /** `latch()`: unlock + 0 -> 1 on 0x6000 (spec 6.4). */
    fun latch(io: DmgCartIo) {
        io.clockToggle(TOGGLES)
        io.writeRegister(0x0000, 0x0A)
        io.clockToggle(TOGGLES)
        io.writeRegister(0x6000, 0x00)
        io.clockToggle(TOGGLES)
        io.writeRegister(0x6000, 0x01)
    }

    /**
     * Presence probe of MAP:422-446: with RTC registers 8..12 selected, a 256 byte page at 0xA880
     * must be one repeated, non-0x00/0xFF value. A freshly reset RTC (all 0) therefore looks absent.
     */
    fun probePresent(io: DmgCartIo): Boolean {
        var found = false
        try {
            io.writeRegister(0x0000, 0x00)
            enable(io)
            latch(io)
            for (reg in 8..12) {
                io.clockToggle(TOGGLES)
                io.writeRegister(0x4000, reg)
                val page = io.ramRead(0x880, 0x100)
                val first = page[0].toInt() and 0xFF
                if (first == 0x00 || first == 0xFF) continue
                found = true
                if (page.any { it != page[0] }) {
                    found = false
                    break
                }
            }
        } catch (_: ReadTimeoutException) {
            return false // spec: an empty read means "no RTC"
        }
        io.writeRegister(0x0000, 0x00)
        io.writeRegister(0x4000, 0x00)
        return found
    }

    /** `readRTC()`: latch, then read S, M, H, DL, DH one byte each. */
    fun read(io: DmgCartIo, nowSeconds: Long): RtcSnapshot {
        latch(io)
        enable(io)
        val regs = IntArray(5)
        for (i in 0..4) {
            io.clockToggle(TOGGLES)
            io.writeRegister(0x4000, 8 + i)
            regs[i] = io.ramRead(0, 1)[0].toInt() and 0xFF
        }
        io.writeRegister(0x0000, 0x00)
        io.writeRegister(0x4000, 0x00)
        return RtcSnapshot(regs[0], regs[1], regs[2], regs[3], regs[4], nowSeconds)
    }
}

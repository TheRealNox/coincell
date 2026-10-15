package org.noxeng.coincell.cart

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Clock values that can be shown to the user, whatever the platform. */
interface ClockDescription {
    fun describe(): String
}

/** What the app can say about a stored cartridge clock, whatever the platform. */
interface ClockValues : ClockDescription {
    val timestamp: Long
    val plausible: Boolean
    override fun describe(): String
}

/**
 * GBA GPIO real-time clock values as FlashGBX stores them in a `.sav` footer (Mapper.py:1893-1903,
 * LK:3833-3841): 7 BCD bytes `YY MM DD WW HH MI SS`, one status byte (S-3511A status register: `0x40` = 24 hour
 * mode, `0x80` = power / reset flag) and the u64 little-endian Unix time of the backup. 16 bytes.
 *
 * The hour byte keeps the PM flag in bit 7 exactly as the chip reports it.
 */
class AgbRtcSnapshot(
    /** The 7 BCD bytes, in footer order. */
    val bcd: ByteArray,
    val status: Int,
    /** Unix seconds when the clock was read. */
    override val timestamp: Long,
) : ClockValues {
    init {
        require(bcd.size == 7) { "GBA RTC date is 7 BCD bytes" }
    }

    val year: Int get() = AgbBcd.decode(bcd[0].toInt())
    val month: Int get() = AgbBcd.decode(bcd[1].toInt())
    val day: Int get() = AgbBcd.decode(bcd[2].toInt())
    val weekday: Int get() = AgbBcd.decode(bcd[3].toInt())
    val hour: Int get() = AgbBcd.decode(bcd[4].toInt() and 0x7F)
    val minute: Int get() = AgbBcd.decode(bcd[5].toInt())
    val second: Int get() = AgbBcd.decode(bcd[6].toInt())
    val pm: Boolean get() = bcd[4].toInt() and 0x80 != 0
    val mode24h: Boolean get() = status and 0x40 != 0

    /** FlashGBX's "reset the clock to 2000-01-01" footer: sixteen 0xFF bytes (Mapper.py:1948). */
    val isReset: Boolean get() = bcd.all { it == 0xFF.toByte() } && status == 0xFF

    /** Every nibble a decimal digit and every field in range; an all-zero or garbage clock is not plausible. */
    override val plausible: Boolean
        get() = isReset || plausibleDate

    private val plausibleDate: Boolean
        get() = AgbBcd.valid(bcd[0].toInt()) && AgbBcd.valid(bcd[1].toInt()) && AgbBcd.valid(bcd[2].toInt()) &&
            AgbBcd.valid(bcd[3].toInt()) && AgbBcd.valid(bcd[4].toInt() and 0x7F) && AgbBcd.valid(bcd[5].toInt()) &&
            AgbBcd.valid(bcd[6].toInt()) && month in 1..12 && day in 1..31 && weekday < 7 && hour < 24 && minute < 60 && second < 60

    /** The 16 byte footer. */
    fun toFooter(): ByteArray {
        val out = ByteArray(FOOTER_SIZE)
        bcd.copyInto(out)
        out[7] = status.toByte()
        for (i in 0 until 8) out[8 + i] = (timestamp ushr (8 * i)).toByte()
        return out
    }

    override fun describe(): String = buildString {
        if (isReset) {
            append("reset to 2000-01-01 00:00:00")
            return@buildString
        }
        append("20%02d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second))
        append(if (mode24h) ", 24 h" else ", 12 h")
        if (status and 0x80 != 0) append(", power flag set")
        if (!plausibleDate) append(" (values out of range)")
    }

    companion object {
        const val FOOTER_SIZE = 0x10

        fun fromFooter(footer: ByteArray): AgbRtcSnapshot {
            require(footer.size == FOOTER_SIZE) { "GBA RTC footer must be $FOOTER_SIZE bytes" }
            val ts = (0 until 8).fold(0L) { acc, i -> acc or ((footer[8 + i].toLong() and 0xFF) shl (8 * i)) }
            return AgbRtcSnapshot(footer.copyOfRange(0, 7), footer[7].toInt() and 0xFF, ts)
        }
    }
}

/** Outcome of looking for a GPIO clock; [note] says why none was found. */
class ClockProbeResult(val present: Boolean, val note: String?)

/** BCD helpers of Mapper.py:11-18. */
internal object AgbBcd {
    fun encode(value: Int): Int = (value / 10 shl 4) or (value % 10)

    fun decode(value: Int): Int = (value and 0x0F) + ((value and 0xFF) shr 4) * 10

    fun valid(value: Int): Boolean = (value and 0x0F) <= 9 && ((value and 0xFF) shr 4) <= 9
}

/** The clock values to write into the cartridge RTC (Mapper.py:1976-2040 `WriteRTC` / `WriteRTCDict`). */
class AgbRtcTime(
    /** 0..99, 2000 + this. */
    val years: Int,
    val months: Int,
    val days: Int,
    /** 0..6 */
    val weekday: Int,
    /** 0..23 */
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    /** The status register value written after the date (`0x40` = 24 hour mode). */
    val status: Int,
) : ClockDescription {
    /** The seven BCD bytes in write order. The PM flag (bit 7 of the hour) is set for hours >= 12. */
    fun dateBytes(): ByteArray = byteArrayOf(
        AgbBcd.encode(years % 100).toByte(),
        AgbBcd.encode(months).toByte(),
        AgbBcd.encode(days).toByte(),
        AgbBcd.encode(weekday % 7).toByte(),
        (AgbBcd.encode(hours) or (if (hours >= 12) 0x80 else 0)).toByte(),
        AgbBcd.encode(minutes).toByte(),
        AgbBcd.encode(seconds).toByte(),
    )

    override fun describe(): String = "20%02d-%02d-%02d %02d:%02d:%02d".format(years, months, days, hours, minutes, seconds)
}

/** Pure clock arithmetic for "restore clock from backup" on a GBA cartridge. */
object AgbRtcAdvance {
    /**
     * The clock to write for [footer] (the 16 byte backup footer) at wall-clock time [nowSeconds].
     *
     * Port of `AGB_GPIO.WriteRTC` (Mapper.py:1976-2038):
     *  - a footer of 16 x 0xFF means "reset": 2000-01-01 (a Saturday = weekday 6) 00:00:00, status 24 h mode, and it is
     *    never advanced;
     *  - a stored status of `0x01` is an old dump convention for `0x40`;
     *  - with [advance] and a footer timestamp in the past, the stored date is moved forward by the calendar
     *    difference between the backup time and now (python-dateutil `relativedelta(now, then)` in local time
     *    [zone]), and the weekday by the number of calendar days that passed. Values that do not form a valid date
     *    leave the stored clock unadvanced, as FlashGBX does after its error message.
     *
     * One deliberate difference: the PM flag is set for hours >= 12 (see [AgbRtcTime.dateBytes]); FlashGBX tests the
     * BCD-encoded hour (`>= 12` is true for 10 and 11 too).
     */
    fun compute(footer: ByteArray, nowSeconds: Long, advance: Boolean, zone: ZoneId = ZoneId.systemDefault()): AgbRtcTime {
        require(footer.size == AgbRtcSnapshot.FOOTER_SIZE) { "GBA RTC footer must be 16 bytes" }
        if (footer.all { it == 0xFF.toByte() }) return AgbRtcTime(0, 1, 1, 6, 0, 0, 0, 0x40)
        fun b(i: Int) = footer[i].toInt() and 0xFF
        var years = AgbBcd.decode(b(0))
        var months = AgbBcd.decode(b(1))
        var days = AgbBcd.decode(b(2))
        var weekday = AgbBcd.decode(b(3)) % 7
        var hours = AgbBcd.decode(b(4) and 0x7F)
        var minutes = AgbBcd.decode(b(5))
        var seconds = AgbBcd.decode(b(6))
        var status = b(7)
        if (status == 0x01) status = 0x40
        val snap = AgbRtcSnapshot.fromFooter(footer)
        if (advance && snap.timestamp in 0 until nowSeconds) {
            try {
                // strptime("%Y-%m-%d %H:%M:%S") of the stored values, with FlashGBX's modulo guards (Mapper.py:1998).
                val stored = LocalDateTime.of(years + 2000, months % 13, days % 32, hours % 60, minutes % 60, seconds % 60)
                val then = LocalDateTime.ofInstant(Instant.ofEpochSecond(snap.timestamp), zone)
                val now = LocalDateTime.ofInstant(Instant.ofEpochSecond(nowSeconds), zone)
                val moved = addRelativeDelta(stored, now, then)
                years = moved.year - 2000
                months = moved.monthValue
                days = moved.dayOfMonth
                val passed = ChronoUnit.DAYS.between(stored.toLocalDate(), moved.toLocalDate()).toInt()
                weekday = Math.floorMod(weekday + passed, 7)
                hours = moved.hour
                minutes = moved.minute
                seconds = moved.second
            } catch (_: java.time.DateTimeException) {
                // unadvanced, like FlashGBX
            }
        }
        return AgbRtcTime(years, months, days, weekday, hours, minutes, seconds, status)
    }

    /**
     * `stored + relativedelta(now, then)` for `then <= now` (dateutil: whole months first, with an overshoot
     * correction, then days, hours, minutes and seconds).
     */
    private fun addRelativeDelta(stored: LocalDateTime, now: LocalDateTime, then: LocalDateTime): LocalDateTime {
        var months = (now.year - then.year) * 12 + (now.monthValue - then.monthValue)
        var anchor = then.plusMonths(months.toLong())
        while (now < anchor) {
            months--
            anchor = then.plusMonths(months.toLong())
        }
        val rest = Duration.between(anchor, now).seconds
        return stored.plusMonths(months.toLong()).plusDays(rest / 86_400).plusSeconds(rest % 86_400)
    }
}

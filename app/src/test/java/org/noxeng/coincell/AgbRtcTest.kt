package org.noxeng.coincell

import org.noxeng.coincell.cart.AgbBcd
import org.noxeng.coincell.cart.AgbRtcAdvance
import org.noxeng.coincell.cart.AgbRtcSnapshot
import org.noxeng.coincell.cart.AgbRtcTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class AgbRtcTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).toEpochSecond(ZoneOffset.UTC)

    /** The footer FlashGBX wrote for the French Pokemon Sapphire test cartridge. */
    private val reference = hex("00 05 07 01 04 33 30 40 db 7a cf 6a 00 00 00 00")

    @Test
    fun bcd_roundTrips() {
        for (v in 0..99) assertEquals(v, AgbBcd.decode(AgbBcd.encode(v)))
        assertEquals(0x59, AgbBcd.encode(59))
        assertEquals(33, AgbBcd.decode(0x33))
        assertEquals(12, AgbBcd.decode(0x92 and 0x7F)) // PM flag masked off
        assertTrue(AgbBcd.valid(0x59))
        assertFalse(AgbBcd.valid(0x5A))
    }

    @Test
    fun referenceFooter_decodesFieldByField() {
        val r = AgbRtcSnapshot.fromFooter(reference)
        assertEquals(0, r.year)
        assertEquals(5, r.month)
        assertEquals(7, r.day)
        assertEquals(1, r.weekday)
        assertEquals(4, r.hour)
        assertFalse(r.pm)
        assertEquals(33, r.minute)
        assertEquals(30, r.second)
        assertEquals(0x40, r.status)
        assertTrue(r.mode24h)
        assertEquals(0x6ACF7ADBL, r.timestamp)
        assertTrue(r.plausible)
        assertEquals("2000-05-07 04:33:30, 24 h", r.describe())
    }

    @Test
    fun footer_roundTripsByteForByte() {
        assertArrayEquals(reference, AgbRtcSnapshot.fromFooter(reference).toFooter())
        val built = AgbRtcSnapshot(hex("00 05 07 01 04 33 30"), 0x40, 0x6ACF7ADBL).toFooter()
        assertEquals(16, built.size)
        assertArrayEquals(reference, built)
    }

    @Test
    fun footer_layout_isSevenBcd_status_thenU64LittleEndianTimestamp() {
        val f = AgbRtcSnapshot(hex("26 12 31 06 A3 59 58"), 0xC0, 0x0102030405060708L).toFooter()
        assertArrayEquals(hex("26 12 31 06 A3 59 58 C0 08 07 06 05 04 03 02 01"), f)
        val back = AgbRtcSnapshot.fromFooter(f)
        assertTrue(back.pm)
        assertEquals(23, back.hour)
        assertEquals(0xC0, back.status)
        assertTrue(back.describe().contains("power flag"))
    }

    @Test
    fun resetFooter_isAllFF_andWritesTheYear2000() {
        val ff = ByteArray(16) { 0xFF.toByte() }
        assertTrue(AgbRtcSnapshot.fromFooter(ff).isReset)
        assertTrue(AgbRtcSnapshot.fromFooter(ff).plausible)
        val t = AgbRtcAdvance.compute(ff, 1_700_000_000L, advance = true, zone = utc)
        assertEquals("2000-01-01 00:00:00", t.describe())
        assertEquals(6, t.weekday)
        assertEquals(0x40, t.status)
    }

    @Test
    fun oldDumpStatusOne_becomes24hMode() {
        val f = reference.copyOf().also { it[7] = 1 }
        assertEquals(0x40, AgbRtcAdvance.compute(f, 0, advance = false, zone = utc).status)
    }

    @Test
    fun withoutAdjust_theStoredValuesAreWrittenExactly() {
        val t = AgbRtcAdvance.compute(reference, 1_800_000_000L, advance = false, zone = utc)
        assertArrayEquals(hex("00 05 07 01 04 33 30"), t.dateBytes())
        assertEquals(0x40, t.status)
    }

    private fun footerAt(y: Int, mo: Int, d: Int, wd: Int, h: Int, mi: Int, s: Int, then: Long): ByteArray {
        val hour = AgbBcd.encode(h) or (if (h >= 12) 0x80 else 0)
        return byteArrayOf(
            AgbBcd.encode(y).toByte(), AgbBcd.encode(mo).toByte(), AgbBcd.encode(d).toByte(), wd.toByte(),
            hour.toByte(), AgbBcd.encode(mi).toByte(), AgbBcd.encode(s).toByte(), 0x40,
        ) + ByteArray(8) { (then ushr (8 * it)).toByte() }
    }

    @Test
    fun advance_addsTheCalendarDifference() {
        val then = epoch(2026, 1, 31, 23, 0, 0)
        val now = epoch(2026, 3, 2, 1, 30, 15)
        val t = AgbRtcAdvance.compute(footerAt(0, 5, 7, 1, 4, 33, 30, then), now, advance = true, zone = utc)
        // relativedelta(2026-03-02 01:30:15, 2026-01-31 23:00:00) = 1 month, 1 day, 2 h 30 min 15 s
        assertEquals("2000-06-08 07:03:45", t.describe())
        assertEquals((1 + 32) % 7, t.weekday) // 32 calendar days from 2000-05-07 to 2000-06-08
    }

    @Test
    fun advance_acrossAYear_andMonthEndClamp() {
        val then = epoch(2025, 12, 31, 12, 0, 0)
        val now = epoch(2027, 2, 28, 12, 0, 0)
        // relativedelta: 1 year 2 months (2025-12-31 + 14 months clamps to 2027-02-28) 0 days
        val t = AgbRtcAdvance.compute(footerAt(0, 1, 31, 0, 8, 0, 0, then), now, advance = true, zone = utc)
        assertEquals("2001-03-31 08:00:00", t.describe())
    }

    @Test
    fun advance_isSkippedWhenTheBackupIsFromTheFuture_orTheStoredDateIsInvalid() {
        val now = epoch(2026, 1, 1, 0, 0, 0)
        val future = footerAt(0, 5, 7, 1, 4, 33, 30, now + 100)
        assertEquals("2000-05-07 04:33:30", AgbRtcAdvance.compute(future, now, advance = true, zone = utc).describe())
        val invalid = footerAt(0, 2, 31, 1, 4, 33, 30, now - 1000)
        assertEquals("2000-02-31 04:33:30", AgbRtcAdvance.compute(invalid, now, advance = true, zone = utc).describe())
    }

    @Test
    fun pmFlag_isSetForHoursFromTwelve() {
        fun hourByte(h: Int) = AgbRtcTime(0, 1, 1, 0, h, 0, 0, 0x40).dateBytes()[4].toInt() and 0xFF
        assertEquals(0x09, hourByte(9))
        assertEquals(0x10, hourByte(10)) // FlashGBX would also set bit 7 here; the chip's PM flag is for hours >= 12
        assertEquals(0x92, hourByte(12))
        assertEquals(0xA3, hourByte(23))
    }
}

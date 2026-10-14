package com.therealnox.gbflash

import com.therealnox.gbflash.cart.DmgCartIo
import com.therealnox.gbflash.cart.DmgCartWriter
import com.therealnox.gbflash.cart.Mbc3RtcWrite
import com.therealnox.gbflash.cart.RtcAdvance
import com.therealnox.gbflash.cart.RtcSnapshot
import com.therealnox.gbflash.cart.RtcTime
import com.therealnox.gbflash.save.RestoreOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MBC3 clock restore (spec 6.4): write sequence, advance arithmetic, and "off by default". */
class RestoreClockTest {
    private val t0 = 1_700_000_000L

    private fun footer(s: Int, m: Int, h: Int, dl: Int, dh: Int, ts: Long = t0) = RtcSnapshot(s, m, h, dl, dh, ts).toFooter()

    private fun advance(f: ByteArray, now: Long, advance: Boolean = true) = RtcAdvance.compute(f, now, advance)

    private fun RtcTime.asList() = listOf(days, hours, minutes, seconds, carry)

    // ---- advance arithmetic ------------------------------------------------------------------

    @Test
    fun advance_addsElapsedSeconds_carryingIntoMinutesHoursDays() {
        // 5d 03:02:01 plus 1 day, 2 hours, 3 minutes, 4 seconds
        val t = advance(footer(1, 2, 3, 5, 0), t0 + 86_400 + 2 * 3600 + 3 * 60 + 4)
        assertEquals(listOf(6, 5, 5, 5, false), t.asList())
    }

    @Test
    fun advance_wrapsAtMidnightAndMinuteBoundaries() {
        val t = advance(footer(59, 59, 23, 0, 0), t0 + 1)
        assertEquals(listOf(1, 0, 0, 0, false), t.asList())
    }

    @Test
    fun advance_usesNinthDayBit_fromDayHigh() {
        // DH bit 0 is bit 8 of the day counter: 256 days + 1 day
        val t = advance(footer(0, 0, 0, 0, 0x01), t0 + 86_400)
        assertEquals(257, t.days)
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 1), t.registers())
    }

    @Test
    fun advance_overflowPast511Days_wrapsAndSetsCarry() {
        val t = advance(footer(0, 0, 0, 0xFF, 0x01), t0 + 86_400 + 5) // day 511 + 1 day + 5 s
        assertEquals(listOf(0, 0, 0, 5, true), t.asList())
        assertArrayEquals(intArrayOf(5, 0, 0, 0, 0x80), t.registers())
    }

    @Test
    fun advance_off_writesTheBackedUpValuesUnchanged() {
        val t = advance(footer(1, 2, 3, 5, 0), t0 + 10_000_000, advance = false)
        assertEquals(listOf(5, 3, 2, 1, false), t.asList())
    }

    @Test
    fun advance_neverGoesBackwards_whenTheTimestampIsInTheFutureOrMissing() {
        assertEquals(listOf(5, 3, 2, 1, false), advance(footer(1, 2, 3, 5, 0), t0 - 100).asList())
        assertEquals(listOf(5, 3, 2, 1, false), advance(footer(1, 2, 3, 5, 0, ts = 0), t0).asList())
    }

    @Test
    fun advance_allZeroFooter_meansResetToZero_andIsNotAdvanced() {
        val t = advance(ByteArray(48), t0)
        assertEquals(listOf(0, 0, 0, 0, false), t.asList())
    }

    @Test
    fun advance_doesNotCopyBackedUpHaltOrCarryBits() {
        // DH = halt (0x40) + carry (0x80) + day bit 8: only the day bit survives (spec 6.4: carry is read, not written back)
        val t = advance(footer(0, 0, 0, 7, 0xC1), t0)
        assertArrayEquals(intArrayOf(0, 0, 0, 7, 1), t.registers())
    }

    @Test
    fun advance_isElapsedTimeNotCalendarDates() {
        // Documented choice: 23:00 + 2 h is the next day 01:00 whatever the wall clock midnight is.
        val t = advance(footer(0, 0, 23, 10, 0), t0 + 2 * 3600)
        assertEquals(listOf(11, 1, 0, 0, false), t.asList())
    }

    // ---- exact register write sequence -------------------------------------------------------

    private class Recorder : DmgCartIo, DmgCartWriter {
        val events = mutableListOf<String>()
        override fun writeRegister(address: Int, value: Int) {
            events += "REG %04X=%02X".format(address, value)
        }

        override fun clockToggle(count: Int) {
            events += "CLK $count"
        }

        override fun ramRead(offset: Int, length: Int): ByteArray = error("the clock write must not read")

        override fun writeRam(offset: Int, data: ByteArray) {
            events += "RAM %04X<-%s".format(0xA000 + offset, data.joinToString("") { "%02X".format(it) })
        }

        override fun release() = error("not part of the clock write")
    }

    @Test
    fun clockWrite_sequence_isExactlySpec64() {
        val rec = Recorder()
        Mbc3RtcWrite.write(rec, rec, RtcTime(seconds = 1, minutes = 2, hours = 3, days = 0x105, carry = false))
        val expected = buildList {
            add("REG 0000=0A") // enableRAM(true)
            // unlock + latch
            add("CLK 50"); add("REG 0000=0A"); add("CLK 50"); add("REG 6000=00"); add("CLK 50"); add("REG 6000=01")
            // halt: select DH, write 0x40
            add("CLK 50"); add("REG 4000=0C"); add("CLK 50"); add("RAM A000<-40")
            // S, M, H, DL, DH (the DH write clears the halt bit)
            val regs = listOf(1, 2, 3, 0x05, 0x01)
            for ((i, v) in regs.withIndex()) {
                add("CLK 50"); add("REG 4000=%02X".format(8 + i)); add("CLK 50"); add("RAM A000<-%02X".format(v))
            }
            // latch again, deselect, RAM off
            add("CLK 50"); add("REG 6000=00"); add("CLK 50"); add("REG 6000=01")
            add("REG 4000=00"); add("REG 0000=00")
        }
        assertEquals(expected, rec.events)
    }

    @Test
    fun clockWrite_withCarry_setsDayHighBit7() {
        val rec = Recorder()
        Mbc3RtcWrite.write(rec, rec, RtcTime(0, 0, 0, 3, carry = true))
        assertEquals("RAM A000<-80", rec.events.last { it.startsWith("RAM") })
    }

    @Test
    fun registers_areReducedModuloTheirRange() {
        assertArrayEquals(intArrayOf(5, 6, 7, 0xFF, 1), RtcTime(65, 66, 31, 0x1FF, false).registers())
    }

    // ---- through the fake cartridge ----------------------------------------------------------

    private fun clockRig(): RestoreRig = RestoreRig(0x10, 3, FakeMapper.MBC3, 0x8000, hasRtc = true).also {
        intArrayOf(9, 9, 9, 9, 0).copyInto(it.cart.liveRtc) // a clock that is running at some other time
    }

    @Test
    fun clockOff_byDefault_noRtcRegisterIsTouched() {
        val r = clockRig()
        val data = testSave(0x8000, 3)
        val o = r.restoreRaw(data, clockFooter = null)
        o as RestoreOutcome.Verified
        assertNull(o.rtcWritten)
        assertArrayEquals(data, r.cart.sram)
        assertTrue("no RTC data write", r.cart.rtcWrites.isEmpty())
        assertEquals("no clock toggles at all", 0, r.fw.clockToggles)
        assertTrue("no latch / RTC select", r.cart.registerWrites.none { it.first == 0x6000 || (it.first == 0x4000 && it.second >= 8) })
        assertEquals(listOf(9, 9, 9, 9, 0), r.cart.liveRtc.toList())
    }

    @Test
    fun clockOn_writesAdvancedValues_andReadsThemBack() {
        val r = clockRig()
        val data = testSave(0x8000, 3)
        // 5d 03:02:01 at t0, restored 1 day 1 hour 1 minute 1 second later
        val o = r.restoreRaw(data, clockFooter = footer(1, 2, 3, 5, 0), now = t0 + 86_400 + 3600 + 60 + 1)
        o as RestoreOutcome.Verified
        assertNotNull(o.rtcWritten)
        assertNull(o.rtcError)
        assertEquals(listOf(2, 3, 4, 6, 0), r.cart.liveRtc.toList()) // S M H DL DH: 6d 04:03:02
        // halt first, then S, M, H, DL, DH
        assertEquals(listOf(12 to 0x40, 8 to 2, 9 to 3, 10 to 4, 11 to 6, 12 to 0), r.cart.rtcWrites)
        assertTrue(r.fw.clockToggles > 0)
        val back = o.rtcReadBack!!
        assertEquals("6d 04:03:02", back.describe().substringBefore(","))
        assertArrayEquals(data, r.cart.sram)
        // the save was still verified byte for byte
        assertEquals(SaveLibraryCrc.of(data), o.crc32)
        assertFalse(r.cart.ramEnabled)
        // clock bytes are single-byte B3 requests after all save data
        assertEquals(0x8000 / 256 + 6, r.fw.b3Count)
        assertTrue(r.fw.violations.isEmpty())
    }

    @Test
    fun clockOn_withoutAdjust_writesTheStoredValuesExactly() {
        val r = clockRig()
        val o = r.restoreRaw(testSave(0x8000, 3), clockFooter = footer(1, 2, 3, 5, 0), adjust = false, now = t0 + 10_000_000)
        o as RestoreOutcome.Verified
        assertEquals(listOf(1, 2, 3, 5, 0), r.cart.liveRtc.toList())
        assertEquals(listOf(12 to 0x40, 8 to 1, 9 to 2, 10 to 3, 11 to 5, 12 to 0), r.cart.rtcWrites)
    }

    @Test
    fun clockOn_allZeroFooter_resetsClock() {
        val r = clockRig()
        val o = r.restoreRaw(testSave(0x8000, 3), clockFooter = ByteArray(48), now = t0)
        o as RestoreOutcome.Verified
        assertEquals(listOf(0, 0, 0, 0, 0), r.cart.liveRtc.toList())
    }

    @Test
    fun clockWriteFailure_isReportedButSaveIsStillVerified() {
        val r = clockRig()
        // the request after the halt write (clock register S) never gets its ACK
        r.fw.ramWriteFilter = { _, _, d ->
            if (d.size == 1 && r.cart.rtcWrites.size == 1) r.fw.swallowReplies = 1
            d
        }
        val o = r.restoreRaw(testSave(0x8000, 3), clockFooter = footer(1, 2, 3, 5, 0))
        o as RestoreOutcome.Verified
        assertNotNull(o.rtcError)
        assertNull(o.rtcWritten)
    }
}

private object SaveLibraryCrc {
    fun of(b: ByteArray): Long = com.therealnox.gbflash.library.SaveLibrary.crc32Of(b)
}

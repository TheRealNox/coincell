package com.therealnox.gbflash

import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.HeaderChangedException
import com.therealnox.gbflash.save.RestoreOutcome
import com.therealnox.gbflash.save.RestorePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

/** The cartridge side of a restore: write, read back, compare, clean up (spec 6.1.4, 6.2 - 6.5, 6.8). */
class RestoreEngineTest {
    private fun crc(b: ByteArray) = CRC32().also { it.update(b) }.value

    private fun verified(o: RestoreOutcome): RestoreOutcome.Verified {
        assertTrue("expected Verified but was ${o::class.simpleName}: " + describe(o), o is RestoreOutcome.Verified)
        return o as RestoreOutcome.Verified
    }

    private fun describe(o: RestoreOutcome) = when (o) {
        is RestoreOutcome.Failed -> o.message
        is RestoreOutcome.Mismatch -> "mismatch bank ${o.bank} offset ${o.offset}"
        else -> ""
    }

    // ---- per mapper: write then read back ----------------------------------------------------

    private fun roundTrip(r: RestoreRig, seed: Int = 99): ByteArray {
        val data = testSave(r.sramSize, seed)
        assertFalse(r.sramBefore.contentEquals(data))
        val out = verified(r.restoreRaw(data))
        assertArrayEquals("cartridge must hold the restored bytes", data, r.cart.sram)
        assertEquals(crc(data), out.crc32)
        assertTrue(r.fw.violations.isEmpty())
        assertFalse("RAM left enabled", r.cart.ramEnabled)
        return data
    }

    @Test
    fun mbc5_32k_writesAllBanksAndVerifies() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        roundTrip(r)
        assertEquals(0x8000 / 256, r.fw.b3Count)
        val banks = r.cart.registerWrites.filter { it.first == 0x4000 }.map { it.second }
        // 4 banks for writing, bank 0 again to start the read-back, 4 banks read, cleanup bank 0
        assertEquals(listOf(0, 1, 2, 3, 0, 0, 1, 2, 3, 0), banks)
        assertEquals(0, r.cart.ramBankReg)
    }

    @Test
    fun mbc5_128k_sixteenBanks() {
        roundTrip(RestoreRig(0x1B, 4, FakeMapper.MBC5, 0x20000))
    }

    @Test
    fun mbc1_usesBankingMode1() {
        val r = RestoreRig(0x03, 3, FakeMapper.MBC1, 0x8000)
        roundTrip(r)
        assertTrue(r.cart.registerWrites.contains(0x6000 to 0x01))
        assertFalse("banking mode left on", r.cart.mode1)
    }

    @Test
    fun mbc3_32k_withoutClock() {
        val r = RestoreRig(0x13, 3, FakeMapper.MBC3, 0x8000)
        roundTrip(r)
    }

    @Test
    fun mbc30_64k_eightBanks() {
        val r = RestoreRig(0x10, 5, FakeMapper.MBC3, 0x10000, hasRtc = false)
        roundTrip(r)
        assertEquals(0x10000 / 256, r.fw.b3Count)
    }

    @Test
    fun noMbc_8k() {
        roundTrip(RestoreRig(0x08, 2, FakeMapper.NONE, 0x2000))
    }

    @Test
    fun smallSram_2k_writesOnlyDeclaredSize() {
        val r = RestoreRig(0x03, 1, FakeMapper.MBC1, 0x800)
        roundTrip(r)
        assertEquals(8, r.fw.b3Count)
    }

    // ---- MBC2 nibbles ------------------------------------------------------------------------

    @Test
    fun mbc2_fileWithOnlyLowNibbles_isWrittenAsStoredAndVerified() {
        val r = RestoreRig(0x06, 0, FakeMapper.MBC2, 0x200)
        val data = ByteArray(0x200) { (it * 5 and 0x0F).toByte() }
        val out = verified(r.restoreRaw(data))
        assertArrayEquals(data, r.cart.sram)
        assertEquals(crc(data), out.crc32)
        // spec 6.3: no bank register, two 256 byte requests carrying the file bytes unchanged
        assertTrue(r.cart.registerWrites.none { it.first == 0x4000 })
        assertEquals(2, r.fw.ramWrites.size)
        assertArrayEquals(data.copyOfRange(0, 256), r.fw.ramWrites[0].second)
        assertArrayEquals(data.copyOfRange(256, 512), r.fw.ramWrites[1].second)
    }

    @Test
    fun mbc2_fileWithUpperNibbles_comparesOnlyLowNibbles() {
        // An emulator save may carry junk in the upper nibble. The cart latches 4 bits; both sides are masked (spec 6.3 / 6.8).
        val r = RestoreRig(0x06, 0, FakeMapper.MBC2, 0x200)
        val data = ByteArray(0x200) { (0xA0 + (it and 0x0F)).toByte() }
        val out = verified(r.restoreRaw(data))
        assertArrayEquals(ByteArray(0x200) { (it and 0x0F).toByte() }, r.cart.sram)
        assertEquals(crc(ByteArray(0x200) { (it and 0x0F).toByte() }), out.crc32)
        assertArrayEquals("bytes go out as stored", data.copyOfRange(0, 256), r.fw.ramWrites[0].second)
    }

    // ---- write protocol ----------------------------------------------------------------------

    @Test
    fun firstChunk_wireSequence_matchesSpec() {
        val r = RestoreRig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        r.link.writes.clear()
        val data = testSave(0x2000, 5)
        verified(r.restoreRaw(data))
        val w = r.link.writes
        val b3 = w.indexOfFirst { it.size == 1 && (it[0].toInt() and 0xFF) == 0xB3 }
        assertTrue(b3 > 0)
        assertArrayEquals(hex("A6 02 00000000 00000100"), w[b3 - 4]) // TRANSFER_SIZE 256
        assertArrayEquals(hex("A6 04 00000000 0000A000"), w[b3 - 3]) // ADDRESS 0xA000
        assertArrayEquals(hex("A6 01 00000001 00000004"), w[b3 - 2]) // DMG_ACCESS_MODE 4
        assertArrayEquals(hex("A6 01 00000009 00000001"), w[b3 - 1]) // DMG_WRITE_CS_PULSE 1
        assertArrayEquals("data is a second write", data.copyOfRange(0, 256), w[b3 + 1])
        assertArrayEquals(hex("A6 04 00000000 00000000"), w[b3 + 2]) // ADDRESS 0 afterwards
        assertArrayEquals(hex("A6 01 00000009 00000000"), w[b3 + 3]) // DMG_WRITE_CS_PULSE 0
        // the second request goes to 0xA100 with the same full setup
        assertTrue(w.any { it.contentEquals(hex("A6 04 00000000 0000A100")) })
        assertEquals(listOf(0xA000, 0xA100), r.fw.ramWrites.map { it.first }.take(2))
    }

    @Test
    fun writeProgress_reachesTotal_thenVerifyCountsBothReads() {
        val r = RestoreRig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        val seen = mutableListOf<Triple<RestorePhase, Long, Long>>()
        verified(r.restoreRaw(testSave(0x2000, 3), progress = { p, d, t -> seen += Triple(p, d, t) }))
        val write = seen.filter { it.first == RestorePhase.WRITE }
        val verify = seen.filter { it.first == RestorePhase.VERIFY }
        assertEquals(0x2000L, write.last().second)
        assertEquals(0x2000L, write.last().third)
        assertEquals(0x4000L, verify.last().second)
        assertEquals(0x4000L, verify.last().third)
        assertEquals("phases never interleave", seen.map { it.first }.sorted(), seen.map { it.first })
    }

    @Test
    fun readBack_usesTheDoubleRead() {
        val r = RestoreRig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        verified(r.restoreRaw(testSave(0x2000, 3)))
        assertEquals(16 * 2, r.fw.ramReadCount) // 16 chunks of 0x200, each read twice
    }

    // ---- read-back mismatch ------------------------------------------------------------------

    @Test
    fun readBackMismatch_isFailure_withBankAndOffsetOfFirstDifference() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        val data = testSave(0x8000, 11)
        // The cartridge stores a wrong byte in the 3rd request of bank 2 (0x4000 + 0x200 + 0x10).
        val target = 2 * 0x2000 + 0x210
        r.fw.ramWriteFilter = { _, addr, d ->
            if (addr == 0xA200 && r.cart.ramBankReg == 2) d.copyOf().also { it[0x10] = (it[0x10] + 1).toByte() } else d
        }
        val o = r.restoreRaw(data)
        assertTrue("must not report success", o is RestoreOutcome.Mismatch)
        o as RestoreOutcome.Mismatch
        assertEquals(2, o.bank)
        assertEquals(0x210, o.offset)
        assertEquals(1, o.differingBytes)
        assertEquals(data[target].toInt() and 0xFF, o.expected)
        assertEquals((data[target] + 1) and 0xFF, o.actual)
        assertFalse("cleanup still ran", r.cart.ramEnabled)
    }

    @Test
    fun readBackMismatch_inMbc2_ignoresUpperNibbleButCatchesLowNibble() {
        val r = RestoreRig(0x06, 0, FakeMapper.MBC2, 0x200)
        r.fw.ramWriteFilter = { n, _, d -> if (n == 2) d.copyOf().also { it[3] = (it[3].toInt() xor 0x01).toByte() } else d }
        val o = r.restoreRaw(ByteArray(0x200) { (it and 0x0F).toByte() })
        o as RestoreOutcome.Mismatch
        assertEquals(0, o.bank)
        assertEquals(256 + 3, o.offset)
    }

    @Test
    fun unstableReadBack_isFailure_notSuccess() {
        val r = RestoreRig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        var counter = 0
        r.fw.ramReadFilter = { _, addr, d -> if (addr == 0xA400) d.copyOf().also { it[0] = (counter++).toByte() } else d }
        val o = r.restoreRaw(testSave(0x2000, 8))
        o as RestoreOutcome.Failed
        assertTrue(o.wroteData)
        assertEquals(0, o.bank)
        assertEquals(0x400, o.offset)
        assertTrue(o.message.contains("uncertain"))
        assertFalse(r.cart.ramEnabled)
    }

    @Test
    fun writeAckLost_isFailureWithWroteData_andCleansUp() {
        val r = RestoreRig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        r.fw.ramWriteFilter = { n, _, d ->
            if (n == 3) r.fw.swallowReplies = 1 // the firmware never ACKs the 3rd request
            d
        }
        val o = r.restoreRaw(testSave(0x2000, 8))
        o as RestoreOutcome.Failed
        assertTrue(o.wroteData)
        assertEquals(0, o.bank)
        assertEquals(0x200, o.offset)
        assertFalse("RAM left enabled", r.cart.ramEnabled)
        assertEquals(0, r.cart.ramBankReg)
    }

    // ---- cancel / cleanup --------------------------------------------------------------------

    @Test
    fun cancelDuringWrite_stopsAndStillRunsCleanup() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        val data = testSave(0x8000, 21)
        val o = r.restoreRaw(data, cancel = { r.fw.b3Count >= 40 })
        o as RestoreOutcome.Cancelled
        assertTrue("the cartridge is partly written", o.wroteData)
        assertEquals(40, r.fw.b3Count)
        assertFalse("RAM left enabled after cancel", r.cart.ramEnabled)
        assertEquals("bank register not reset", 0, r.cart.ramBankReg)
        // first 40 requests are new, the rest still old
        assertArrayEquals(data.copyOf(40 * 256), r.cart.sram.copyOf(40 * 256))
        assertArrayEquals(r.sramBefore.copyOfRange(40 * 256, 0x8000), r.cart.sram.copyOfRange(40 * 256, 0x8000))
        // cleanup: write pulse off + address 0, bank 0, RAM off, read pulse off, address lines to inputs
        assertEquals(listOf(0xA6, 0xA6, 0xB2, 0xB2, 0xA6, 0xA8), r.link.opcodes().takeLast(6))
        assertEquals(listOf(0x4000 to 0, 0x0000 to 0), r.cart.registerWrites.takeLast(2))
        assertEquals("no read-back after a cancel", 0, r.fw.ramReadCount)
    }

    @Test
    fun cancelBeforeFirstWrite_writesNothing() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        val o = r.restoreRaw(testSave(0x8000, 21), cancel = { true })
        o as RestoreOutcome.Cancelled
        assertFalse(o.wroteData)
        assertEquals(0, r.fw.b3Count)
        assertArrayEquals(r.sramBefore, r.cart.sram)
        assertFalse(r.cart.ramEnabled)
    }

    @Test
    fun cleanupOnSuccess_mbc1_leavesBankingModeOff_andWritePulseOff() {
        val r = RestoreRig(0x03, 3, FakeMapper.MBC1, 0x8000)
        verified(r.restoreRaw(testSave(0x8000, 4)))
        assertEquals(listOf(0x0000 to 0, 0x6000 to 0), r.cart.registerWrites.takeLast(2))
        assertEquals(0xA8, r.link.opcodes().last())
        assertTrue(r.link.writes.any { it.contentEquals(hex("A6 01 00000009 00000000")) })
    }

    // ---- guards ------------------------------------------------------------------------------

    @Test
    fun headerChangedSinceReview_abortsBeforeAnyWrite() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        val other = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000, title = "OTHERGAME", sram = testSave(0x8000, 77))
        r.swapCart(other.cart)
        r.link.writes.clear()
        assertThrows(HeaderChangedException::class.java) { r.restoreRaw(testSave(0x8000, 5)) }
        assertEquals(0, r.fw.b3Count)
        assertTrue(r.link.writes.none { it.size == 6 && (it[0].toInt() and 0xFF) == 0xB2 })
        assertArrayEquals(testSave(0x8000, 77), other.cart.sram)
    }

    @Test
    fun sameTitleButOtherChecksum_isAlsoAChangedHeader() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000, globalChecksum = 0x1111)
        val other = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000, globalChecksum = 0x2222)
        r.swapCart(other.cart)
        assertThrows(HeaderChangedException::class.java) { r.restoreRaw(testSave(0x8000, 5)) }
        assertEquals(0, r.fw.b3Count)
    }

    @Test
    fun restoreRefusedInGbaMode_andSendsNothing() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        r.fw.rom = agbHeader()
        runBlocking { r.session.readHeader(CartPlatform.GBA) }
        r.link.writes.clear()
        assertThrows(IllegalStateException::class.java) { r.restoreRaw(testSave(0x8000, 5)) }
        assertTrue(r.link.writes.isEmpty())
        assertEquals(0, r.fw.b3Count)
    }

    @Test
    fun wrongSizedData_isRejectedBeforeTouchingTheCartridge() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        r.link.writes.clear()
        assertThrows(IllegalArgumentException::class.java) { r.restoreRaw(ByteArray(0x2000)) }
        assertEquals(0, r.fw.b3Count)
        assertTrue(r.link.writes.none { it.size == 6 && (it[0].toInt() and 0xFF) == 0xB2 })
    }

    @Test
    fun clockFooterForNonMbc3_isRejected() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        assertThrows(IllegalArgumentException::class.java) { r.restoreRaw(testSave(0x8000, 5), clockFooter = ByteArray(48)) }
        assertEquals(0, r.fw.b3Count)
    }

    @Test
    fun voltageRuleHolds_duringRestore() {
        val r = RestoreRig(0x13, 3, FakeMapper.MBC3, 0x8000)
        verified(r.restoreRaw(testSave(0x8000, 6)))
        val ops = r.link.opcodes()
        assertFalse(0xA4 in ops)
        assertFalse(0xA2 in ops)
        assertFalse(r.fw.voltageChangedWhilePowered)
        assertEquals("5V", r.fw.voltage)
        assertTrue(r.fw.voltageAtPowerOn.all { it == "5V" })
    }

    @Test
    fun identicalBytes_changeNothing() {
        val r = RestoreRig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        val out = verified(r.restoreRaw(r.sramBefore.copyOf()))
        assertArrayEquals(r.sramBefore, r.cart.sram)
        assertEquals(crc(r.sramBefore), out.crc32)
        assertNull(out.rtcWritten)
    }
}

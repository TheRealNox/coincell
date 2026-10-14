package com.therealnox.gbflash

import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.RtcSnapshot
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.Commands
import com.therealnox.gbflash.protocol.DeviceSession
import com.therealnox.gbflash.protocol.FirmwareInfo
import com.therealnox.gbflash.protocol.Transport
import com.therealnox.gbflash.save.BackupCancelledException
import com.therealnox.gbflash.save.BackupPlan
import com.therealnox.gbflash.save.DmgBackupResult
import com.therealnox.gbflash.save.SaveCheck
import com.therealnox.gbflash.save.VerificationFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Test rig: fake firmware + fake cartridge + a connected session that has read the header. */
private class Rig(
    cartType: Int,
    ramCode: Int,
    val mapper: FakeMapper,
    sramSize: Int,
    hasRtc: Boolean = false,
    title: String = "TESTGAME",
    sram: ByteArray? = null,
) {
    val link = FakeSerialLink()
    val fw = FakeFirmware(link)
    val cart = FakeDmgCart(
        mapper,
        dmgHeader(title = title.toByteArray(), cartType = cartType, ramCode = ramCode).copyOf(0x8000 * 2),
        sram ?: ByteArray(sramSize) { ((it * 7) + (it shr 8) * 13 + 1).toByte() },
        hasRtc,
    )
    val sramBefore = cart.sram.copyOf()
    val session: DeviceSession
    val header: DmgHeader
    val plan: BackupPlan

    init {
        fw.cart = cart
        session = runBlocking { DeviceSession.open(link, clock = link, io = Dispatchers.Unconfined) }
        header = runBlocking { session.readHeader(CartPlatform.GB_GBC) } as DmgHeader
        plan = BackupPlan.forHeader(header)
    }

    fun ready() = plan as BackupPlan.Ready

    fun backup(
        cancel: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
        now: Long = 1_700_000_000L,
    ): DmgBackupResult = runBlocking { session.backupDmgSave(ready(), cancel, progress, nowSeconds = { now }) }
}

class SaveBackupTest {
    // ---- mapper bank switching and output bytes ----------------------------------------------

    @Test
    fun mbc5_32k_readsAllFourBanks() {
        val r = Rig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        val result = r.backup()
        assertArrayEquals(r.sramBefore, result.data)
        assertNull(result.rtc)
        assertNull(result.rtcProbePresent)
        // 64 chunks of 0x200, each read twice.
        assertEquals(128, r.fw.ramReadCount)
        assertTrue(r.fw.violations.isEmpty())
        val bankWrites = r.cart.registerWrites.filter { it.first == 0x4000 }.map { it.second }
        assertEquals(listOf(0, 1, 2, 3, 0), bankWrites)
    }

    @Test
    fun mbc5_128k_sixteenBanks() {
        val r = Rig(0x1B, 4, FakeMapper.MBC5, 0x20000)
        assertArrayEquals(r.sramBefore, r.backup().data)
    }

    @Test
    fun mbc1_needsBankingMode1_andReadsAllBanks() {
        val r = Rig(0x03, 3, FakeMapper.MBC1, 0x8000)
        val result = r.backup()
        // The fake only honours the RAM bank register in banking mode 1, so this proves the 0x6000 write.
        assertArrayEquals(r.sramBefore, result.data)
        assertTrue(r.cart.registerWrites.contains(0x6000 to 0x01))
        assertFalse("banking mode left on", r.cart.mode1)
    }

    @Test
    fun mbc3_32k_withoutRtc() {
        val r = Rig(0x13, 3, FakeMapper.MBC3, 0x8000)
        val result = r.backup()
        assertArrayEquals(r.sramBefore, result.data)
        assertNull(result.rtc)
        assertEquals(false, result.rtcProbePresent)
        assertFalse(result.rtcDeclared)
    }

    @Test
    fun mbc30_64k_eightBanks() {
        val r = Rig(0x10, 5, FakeMapper.MBC3, 0x10000, hasRtc = false)
        assertEquals(0x110, r.header.mapperRaw)
        val result = r.backup()
        assertArrayEquals(r.sramBefore, result.data)
        val banks = r.cart.registerWrites.filter { it.first == 0x4000 && it.second < 8 }.map { it.second }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 0), banks.take(9))
    }

    @Test
    fun noMbc_8k() {
        val r = Rig(0x08, 2, FakeMapper.NONE, 0x2000)
        assertArrayEquals(r.sramBefore, r.backup().data)
    }

    @Test
    fun smallSram_2k_readsOnlyDeclaredSize() {
        val r = Rig(0x03, 1, FakeMapper.MBC1, 0x800)
        val result = r.backup()
        assertEquals(0x800, result.data.size)
        assertArrayEquals(r.sramBefore, result.data)
        assertEquals(8, r.fw.ramReadCount) // 4 chunks x 2
    }

    // ---- MBC2 --------------------------------------------------------------------------------

    @Test
    fun mbc2_keepsOnlyLowNibble_perSpec() {
        // Cart stores arbitrary bytes but only the low nibble is real; the bus reports 0xF0 | nibble.
        val sram = ByteArray(0x200) { (0xA0 + (it and 0x0F)).toByte() }
        val r = Rig(0x06, 0, FakeMapper.MBC2, 0x200, sram = sram)
        assertEquals(0x100, r.header.save.typeId)
        val result = r.backup()
        assertEquals(0x200, result.data.size)
        // FlashGBX (spec 6.3 / 7) masks every byte with 0x0F: upper nibbles are 0 in the .sav.
        assertArrayEquals(ByteArray(0x200) { (it and 0x0F).toByte() }, result.data)
        assertTrue(result.data.all { it.toInt() and 0xF0 == 0 })
        // MBC2 has no RAM bank register.
        assertTrue(r.cart.registerWrites.none { it.first == 0x4000 })
    }

    // ---- planning / unsupported --------------------------------------------------------------

    private fun planFor(cartType: Int, ramCode: Int, logo: ByteArray = DMG_LOGO): BackupPlan =
        BackupPlan.forHeader(DmgHeader.parse(dmgHeader(cartType = cartType, ramCode = ramCode, logo = logo)))

    @Test
    fun unsupportedMappers_sayNotSupportedYet() {
        for (type in listOf(0x20, 0x22, 0xFC, 0xFD, 0xFE, 0xFF, 0x0B, 0x1234 and 0xFF)) {
            val p = planFor(type, 3)
            assertTrue("type %02X".format(type), p is BackupPlan.Unavailable)
            assertTrue((p as BackupPlan.Unavailable).reason.contains("not supported yet"))
        }
    }

    @Test
    fun noRamDeclared_saysSo() {
        val p = planFor(0x00, 0) as BackupPlan.Unavailable
        assertTrue(p.reason.contains("no cartridge RAM"))
        assertTrue((planFor(0x19, 0) as BackupPlan.Unavailable).reason.contains("no cartridge RAM"))
        // MBC2 without battery keeps RAM code 0
        assertTrue(planFor(0x05, 0) is BackupPlan.Unavailable)
    }

    @Test
    fun invalidLogo_blocksBackup() {
        val bad = DMG_LOGO.copyOf().also { it[3] = 0 }
        assertTrue(planFor(0x1B, 3, bad) is BackupPlan.Unavailable)
    }

    @Test
    fun supportedPlans() {
        for ((type, ram) in listOf(0x00 to 2, 0x01 to 2, 0x03 to 3, 0x06 to 0, 0x0F to 3, 0x13 to 3, 0x19 to 2, 0x1E to 4)) {
            assertTrue("type %02X".format(type), planFor(type, ram) is BackupPlan.Ready)
        }
    }

    // ---- RTC ---------------------------------------------------------------------------------

    @Test
    fun rtcFooter_byteLayout_matchesHandComputedArray() {
        // S=21, M=42, H=13, DL=123, DH=0x41 (halt + day bit 8), timestamp 1700000000 = 0x6553F100.
        val rtc = RtcSnapshot(21, 42, 13, 123, 0x41, 1_700_000_000L)
        val regs = "15000000 2A000000 0D000000 7B000000 41000000"
        val expected = hex("$regs $regs 00F15365 00000000")
        assertEquals(48, expected.size)
        assertArrayEquals(expected, rtc.toFooter())
        assertEquals(379, rtc.days)
        assertTrue(rtc.halted)
        assertFalse(rtc.dayCarry)
        assertEquals("379d 13:42:21, halted", rtc.describe())
        val back = RtcSnapshot.fromFooter(expected)
        assertEquals(21, back.seconds)
        assertEquals(0x41, back.dayHigh)
        assertEquals(1_700_000_000L, back.timestamp)
    }

    @Test
    fun rtcDecoding_carryAndPlausibility() {
        val rtc = RtcSnapshot(0, 0, 0, 0xFF, 0x81, 0)
        assertEquals(511, rtc.days)
        assertTrue(rtc.dayCarry)
        assertFalse(rtc.halted)
        assertFalse(RtcSnapshot(61, 0, 0, 0, 0, 0).plausible)
    }

    @Test
    fun mbc3Rtc_present_appendsFooterFromLatchedRegisters() {
        val r = Rig(0x10, 3, FakeMapper.MBC3, 0x8000, hasRtc = true)
        intArrayOf(21, 42, 13, 123, 0x41).copyInto(r.cart.liveRtc)
        val result = r.backup(now = 1_700_000_000L)
        assertArrayEquals("save data must not contain the footer", r.sramBefore, result.data)
        assertEquals(true, result.rtcProbePresent)
        assertTrue(result.rtcDeclared)
        val regs = "15000000 2A000000 0D000000 7B000000 41000000"
        assertArrayEquals(hex("$regs $regs 00F15365 00000000"), result.rtc!!.toFooter())
        // latch = 0x6000 <- 0 then 1, registers selected 8..12 in order
        val w = r.cart.registerWrites
        assertTrue(w.windowed(2).any { it[0] == (0x6000 to 0) && it[1] == (0x6000 to 1) })
        assertEquals((8..12).toList(), w.filter { it.first == 0x4000 && it.second >= 8 }.map { it.second }.takeLast(5))
        assertTrue(r.fw.clockToggles > 0)
        assertTrue(r.fw.violations.isEmpty())
    }

    @Test
    fun mbc3Rtc_headerDeclaresTimer_butFreshlyResetClockLooksAbsent() {
        val r = Rig(0x10, 3, FakeMapper.MBC3, 0x8000, hasRtc = true) // live registers all 0
        val result = r.backup(now = 5)
        assertEquals(false, result.rtcProbePresent)
        assertTrue(result.rtcDeclared)
        val rtc = result.rtc!!
        assertEquals("0d 00:00:00, running", rtc.describe())
        assertArrayEquals(ByteArray(40) + hex("0500000000000000"), rtc.toFooter())
    }

    @Test
    fun mbc3Rtc_notDeclaredAndNotProbed_noRtc() {
        val r = Rig(0x13, 3, FakeMapper.MBC3, 0x8000, hasRtc = false)
        val result = r.backup()
        assertNull(result.rtc)
        assertEquals(false, result.rtcProbePresent)
        assertFalse(result.rtcDeclared)
    }

    @Test
    fun mbc3Rtc_probeFindsClockEvenWhenHeaderSaysNoTimer() {
        val r = Rig(0x13, 3, FakeMapper.MBC3, 0x8000, hasRtc = true)
        intArrayOf(1, 2, 3, 4, 0).copyInto(r.cart.liveRtc)
        val result = r.backup()
        assertEquals(true, result.rtcProbePresent)
        assertEquals("4d 03:02:01", result.rtc!!.describe().substringBefore(","))
    }

    // ---- verification ------------------------------------------------------------------------

    @Test
    fun verify_mismatchOnce_isRetriedAndSucceeds() {
        val r = Rig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        r.fw.ramReadFilter = { n, _, data -> if (n == 3) data.copyOf().also { it[0] = (it[0] + 1).toByte() } else data }
        val result = r.backup()
        assertArrayEquals(r.sramBefore, result.data)
        assertEquals(16 * 2 + 2, r.fw.ramReadCount) // one chunk was read one extra time (2 reads)
    }

    @Test
    fun verify_persistentMismatch_failsWithoutResult_andStillCleansUp() {
        val r = Rig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        // Every read of the 2nd chunk returns different bytes.
        var counter = 0
        r.fw.ramReadFilter = { _, addr, data ->
            if (addr == 0xA200) data.copyOf().also { it[5] = (counter++).toByte() } else data
        }
        val e = assertThrows(VerificationFailedException::class.java) { r.backup() }
        assertTrue(e.message!!.contains("offset 0x0200"))
        assertTrue(e.message!!.contains("Nothing was saved"))
        assertFalse("RAM left enabled", r.cart.ramEnabled)
        assertEquals(0, r.cart.ramBankReg)
        assertEquals(0xA8, r.link.opcodes().last())
    }

    @Test
    fun verify_shortRead_isRetriedThenFails_neverAcceptedSilently() {
        val r = Rig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        r.fw.ramReadFilter = { n, _, data -> if (n == 2) data.copyOf(100) else data }
        assertArrayEquals(r.sramBefore, r.backup().data)

        val r2 = Rig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        r2.fw.ramReadFilter = { _, addr, data -> if (addr == 0xA400) data.copyOf(100) else data }
        val e = assertThrows(VerificationFailedException::class.java) { r2.backup() }
        assertTrue(e.message!!.contains("0x0400"))
        assertFalse(r2.cart.ramEnabled)
    }

    // ---- cancel / cleanup --------------------------------------------------------------------

    @Test
    fun cancel_runsCleanup_andReturnsNoData() {
        val r = Rig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        assertThrows(BackupCancelledException::class.java) { r.backup(cancel = { r.fw.ramReadCount >= 50 }) }
        assertFalse("RAM left enabled after cancel", r.cart.ramEnabled)
        assertEquals("bank register not reset", 0, r.cart.ramBankReg)
        val ops = r.link.opcodes()
        assertEquals(listOf(0xB2, 0xB2, 0xA6, 0xA8), ops.takeLast(4))
        assertTrue(r.fw.ramReadCount < 128)
    }

    @Test
    fun cleanup_onSuccess_selectsBank0_disablesRam_releasesBus() {
        val r = Rig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        r.backup()
        assertEquals(listOf(0x4000 to 0, 0x0000 to 0), r.cart.registerWrites.takeLast(2))
        assertEquals(listOf(0xB2, 0xB2, 0xA6, 0xA8), r.link.opcodes().takeLast(4))
        assertFalse(r.cart.ramEnabled)
    }

    @Test
    fun cleanup_mbc1_leavesBankingModeOff() {
        val r = Rig(0x03, 3, FakeMapper.MBC1, 0x8000)
        r.backup()
        assertEquals(listOf(0x0000 to 0, 0x6000 to 0), r.cart.registerWrites.takeLast(2))
    }

    // ---- no SRAM writes ----------------------------------------------------------------------

    @Test
    fun noSramWriteEverSent_forAnyMapper() {
        val rigs = listOf(
            Rig(0x1B, 3, FakeMapper.MBC5, 0x8000),
            Rig(0x03, 3, FakeMapper.MBC1, 0x8000),
            Rig(0x06, 0, FakeMapper.MBC2, 0x200),
            Rig(0x10, 3, FakeMapper.MBC3, 0x8000, hasRtc = true).also { intArrayOf(5, 6, 7, 8, 0).copyInto(it.cart.liveRtc) },
            Rig(0x10, 5, FakeMapper.MBC3, 0x10000, hasRtc = true),
            Rig(0x09, 2, FakeMapper.NONE, 0x2000),
        )
        for (r in rigs) {
            r.backup()
            assertEquals("B3 sent", 0, r.fw.b3Count)
            assertFalse("B3 in opcode stream", 0xB3 in r.link.opcodes())
            assertTrue("write into 0xA000-0xBFFF: ${r.cart.sramWriteAttempts}", r.cart.sramWriteAttempts.isEmpty())
            assertTrue(r.cart.registerWrites.all { it.first < 0x8000 })
            assertArrayEquals("SRAM changed", r.sramBefore, r.cart.sram)
        }
    }

    @Test
    fun noSramWrite_evenWhenBackupFailsOrIsCancelled() {
        val r = Rig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        r.fw.ramReadFilter = { n, _, d -> d.copyOf().also { it[0] = (it[0] + n).toByte() } }
        assertThrows(VerificationFailedException::class.java) { r.backup() }
        assertEquals(0, r.fw.b3Count)
        assertTrue(r.cart.sramWriteAttempts.isEmpty())
        assertArrayEquals(r.sramBefore, r.cart.sram)
    }

    @Test
    fun cartAccess_refusesAddressesInTheSramRange() {
        val link = FakeSerialLink()
        val transport = Transport(link, link)
        link.enqueue(SPEC_FW_REPLY)
        val info = FirmwareInfo.read(transport, 1000)
        val access = Commands(transport, info).dmgCartAccess()
        link.writes.clear()
        for (addr in listOf(0xA000, 0xA123, 0xBFFF, 0x8000)) {
            assertThrows(IllegalArgumentException::class.java) { access.writeRegister(addr, 0x55) }
        }
        assertTrue("nothing may reach the wire", link.writes.isEmpty())
    }

    // ---- voltage rule, platform rule ---------------------------------------------------------

    @Test
    fun voltageRule_holdsDuringBackup() {
        val r = Rig(0x10, 3, FakeMapper.MBC3, 0x8000, hasRtc = true)
        r.backup()
        val ops = r.link.opcodes()
        assertFalse(0xA4 in ops)
        assertFalse(0xA2 in ops)
        assertFalse(r.fw.voltageChangedWhilePowered)
        assertEquals("5V", r.fw.voltage)
        assertTrue(r.fw.voltageAtPowerOn.all { it == "5V" })
    }

    @Test
    fun backupRefusedInGbaMode_andSendsNothing() {
        val r = Rig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        r.fw.rom = agbHeader()
        runBlocking { r.session.readHeader(CartPlatform.GBA) }
        r.link.writes.clear()
        assertThrows(IllegalStateException::class.java) { r.backup() }
        assertTrue(r.link.writes.isEmpty())
    }

    // ---- wire trace (spec Appendix B) --------------------------------------------------------

    @Test
    fun wireTrace_matchesAppendixB() {
        val r = Rig(0x1B, 3, FakeMapper.MBC5, 0x8000)
        r.link.writes.clear()
        r.backup()
        val ops = r.link.opcodes()
        // ping, power query, STATUS_REGISTER_MASK/VALUE, MBC reset, DMG mode, CS pulses, EnableRAM
        assertEquals(listOf(0xFE, 0xF4, 0xA6, 0xA6, 0xB4, 0xA3, 0xA6, 0xA6, 0xB2), ops.take(9))
        assertTrue(r.link.writes.any { it.contentEquals(hex("A6 02 00000005 00000080")) })
        assertTrue(r.link.writes.any { it.contentEquals(hex("A6 02 00000006 00000080")) })
        assertTrue(r.link.writes.any { it.contentEquals(hex("B2 00000000 0A")) })
        // First chunk: TRANSFER_SIZE, ADDRESS, ACCESS_MODE, READ_CS_PULSE=1, B1, READ_CS_PULSE=0
        val firstB1 = ops.indexOf(0xB1)
        assertEquals(List(4) { 0xA6 }, ops.subList(firstB1 - 4, firstB1))
        assertTrue(r.link.writes.any { it.contentEquals(hex("A6 02 00000000 00000200")) })
        assertTrue(r.link.writes.any { it.contentEquals(hex("A6 04 00000000 0000A000")) })
        assertTrue(r.link.writes.any { it.contentEquals(hex("A6 01 00000001 00000003")) })
        assertTrue(r.link.writes.any { it.contentEquals(hex("A6 01 00000008 00000001")) })
    }

    // ---- progress / end to end with the library ----------------------------------------------

    @Test
    fun progress_countsBothReads_andReachesTotal() {
        val r = Rig(0x1B, 2, FakeMapper.MBC5, 0x2000)
        val seen = mutableListOf<Pair<Long, Long>>()
        r.backup(progress = { d, t -> seen += d to t })
        assertTrue(seen.all { it.second == 0x4000L })
        assertEquals(0L, seen.first().first)
        assertEquals(0x4000L, seen.last().first)
        assertEquals(seen.map { it.first }.sorted(), seen.map { it.first })
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun backupToLibraryToExport_isEmulatorCompatibleSav() {
        val r = Rig(0x10, 3, FakeMapper.MBC3, 0x8000, hasRtc = true, title = "POKEMON GOLD")
        intArrayOf(21, 42, 13, 123, 0x41).copyInto(r.cart.liveRtc)
        val result = r.backup(now = 1_700_000_000L)
        val lib = SaveLibrary(tmp.newFolder("saves"))
        val game = GameInfo.fromDmg(r.header)
        val entry = lib.store(
            game,
            NewBackup(result.data, result.rtc?.toFooter(), result.rtcProbePresent, result.rtcDeclared, "L15", "test", true, SaveCheck.suspectReason(result.data)),
        )
        assertTrue(entry.rtcIncluded)
        val withRtc = lib.exportBytes(entry, true)
        assertEquals(0x8000 + 48, withRtc.size)
        assertArrayEquals(r.sramBefore, withRtc.copyOf(0x8000))
        assertArrayEquals(result.rtc!!.toFooter(), withRtc.copyOfRange(0x8000, withRtc.size))
        assertArrayEquals(r.sramBefore, lib.exportBytes(entry, false))
        assertNotNull(entry.rtc)
        assertEquals(SaveLibrary.crc32Of(r.sramBefore), entry.crc32)
        assertEquals(result.crc32, entry.crc32)
    }
}

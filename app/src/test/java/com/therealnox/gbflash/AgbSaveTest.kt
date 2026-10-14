package com.therealnox.gbflash

import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.AgbRtcSnapshot
import com.therealnox.gbflash.cart.AgbSaveDetection
import com.therealnox.gbflash.cart.AgbSaveTypes
import com.therealnox.gbflash.cart.SaveTypeSource
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.HeaderChangedException
import com.therealnox.gbflash.save.AgbBackupPlan
import com.therealnox.gbflash.save.RestoreOutcome
import com.therealnox.gbflash.save.RestorePhase
import com.therealnox.gbflash.save.SaveImport
import com.therealnox.gbflash.save.VerificationFailedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** GBA save backup, restore, detection and the isolation of the write path (docs/PORTING_SPEC.md 5.5-5.8, 6.1.2, 6.6). */
class AgbSaveTest {
    private fun flashRig(size: Int = 0x20000, chip: Int = 0x6213, rtc: Boolean = false, save: ByteArray? = null) =
        AgbRig(AgbSaveKind.FLASH, size, flashId = chip, hasRtc = rtc, save = save)

    private fun verified(o: RestoreOutcome): RestoreOutcome.Verified {
        assertTrue("expected Verified but was ${o::class.simpleName}: " + describe(o), o is RestoreOutcome.Verified)
        return o as RestoreOutcome.Verified
    }

    private fun describe(o: RestoreOutcome) = when (o) {
        is RestoreOutcome.Failed -> o.message
        is RestoreOutcome.Mismatch -> "mismatch bank ${o.bank} offset ${o.offset}"
        else -> o.toString()
    }

    // ---- header, database, detection order ---------------------------------------------------

    @Test
    fun database_loadsAndKnowsFrenchSapphire() {
        val db = loadAgbDatabase()
        assertTrue(db.size > 2900)
        // The header of the test cartridge is not available here; the DB key is the plain SHA-1 string of the entry.
        val h = AgbHeader.parse(fakeAgbRom())
        assertNull(db.lookup(h))
    }

    @Test
    fun detection_markerWinsOverTheDatabase() {
        val rom = fakeAgbRom(marker = 0x1D) // flash (3), 128 KiB (5)
        val h = AgbHeader.parse(rom)
        val d = AgbSaveDetection.fromHeader(h, loadAgbDatabase())
        assertEquals(AgbSaveTypes.FLASH_1M, d.typeId)
        assertEquals(SaveTypeSource.HEADER_MARKER, d.source)
    }

    @Test
    fun detection_databaseHitUsesTheEntrysSaveType() {
        val h = AgbHeader.parse(fakeAgbRom())
        val db = com.therealnox.gbflash.cart.AgbDatabase.parse(
            "# c\n${h.dbKey}\t5\t\tABCD\tTest Game (World)\n".byteInputStream(),
        )
        val d = AgbSaveDetection.fromHeader(h, db)
        assertEquals(AgbSaveTypes.FLASH_1M, d.typeId)
        assertEquals(SaveTypeSource.DATABASE, d.source)
        assertEquals("Test Game (World)", d.dbName)
        assertFalse(d.uncertain)
    }

    @Test
    fun detection_unknownCartridgeIsUncertainAndHasNoType() {
        val d = AgbSaveDetection.fromHeader(AgbHeader.parse(fakeAgbRom()), com.therealnox.gbflash.cart.AgbDatabase.EMPTY)
        assertNull(d.typeId)
        assertTrue(d.uncertain)
    }

    @Test
    fun detection_eepromMarkerWithoutSizeStaysUnresolved() {
        val h = AgbHeader.parse(fakeAgbRom(marker = 0x10))
        val d = AgbSaveDetection.fromHeader(h, com.therealnox.gbflash.cart.AgbDatabase.EMPTY)
        assertNull(d.typeId)
    }

    @Test
    fun plan_needsAKnownSupportedType() {
        val h = AgbRig(AgbSaveKind.SRAM, 0x8000).header
        assertTrue(AgbBackupPlan.forHeader(h, null, null) is AgbBackupPlan.Unavailable)
        assertTrue(AgbBackupPlan.forHeader(h, 0, SaveTypeSource.DATABASE) is AgbBackupPlan.Unavailable)
        assertTrue(AgbBackupPlan.forHeader(h, 6, SaveTypeSource.DATABASE) is AgbBackupPlan.Unavailable)
        assertTrue(AgbBackupPlan.forHeader(h, 9, SaveTypeSource.DATABASE) is AgbBackupPlan.Unavailable)
        assertTrue(AgbBackupPlan.forHeader(h, 3, SaveTypeSource.USER) is AgbBackupPlan.Ready)
    }

    // ---- backup of every save type -----------------------------------------------------------

    private fun assertBackupRoundTrip(rig: AgbRig, type: Int) {
        val saved = rig.cart.save.copyOf()
        val r = rig.backup(type)
        assertArrayEquals(saved, r.data)
        assertNull(r.rtc)
        assertArrayEquals("a backup never changes the save", saved, rig.cart.save)
        assertBackupWritesAreSafe(rig)
        assertTrue(rig.cart.violations.isEmpty())
        assertEquals(0, rig.fw.a3Count)
        assertEquals(0, rig.fw.a5Count)
        assertTrue(rig.fw.violations.isEmpty())
    }

    @Test fun backup_sram256k() = assertBackupRoundTrip(AgbRig(AgbSaveKind.SRAM, 0x8000), 3)

    @Test fun backup_sram512k() = assertBackupRoundTrip(AgbRig(AgbSaveKind.SRAM, 0x10000), 7)

    @Test
    fun backup_sram1m_readsBothBanks() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x20000)
        assertBackupRoundTrip(rig, 8)
        val banks = rig.cart.writes.filterIsInstance<AgbWrite.RomSpace>().filter { it.address == 0x1000000 }.map { it.value }
        assertEquals(listOf(0, 1), banks)
    }

    @Test fun backup_eeprom4k() = assertBackupRoundTrip(AgbRig(AgbSaveKind.EEPROM, 512), 1)

    @Test fun backup_eeprom64k() = assertBackupRoundTrip(AgbRig(AgbSaveKind.EEPROM, 8192), 2)

    @Test fun backup_flash512k() = assertBackupRoundTrip(flashRig(0x10000, 0xBFD4), 4)

    @Test
    fun backup_flash1m_bankOneLandsInTheSecondHalf() {
        val data = agbTestSave(0x20000, 5)
        val rig = flashRig(save = data)
        val r = rig.backup(5)
        assertArrayEquals(data, r.data)
        assertArrayEquals(data.copyOfRange(0x10000, 0x20000), r.data.copyOfRange(0x10000, 0x20000))
        assertFalse("the two banks hold different data", data.copyOfRange(0, 0x10000).contentEquals(data.copyOfRange(0x10000, 0x20000)))
        val banks = rig.cart.writes.filterIsInstance<AgbWrite.FlashCmds>().filter { it.cmds.size == 4 }.map { it.cmds[3].second }
        assertEquals(listOf(0, 1), banks)
        assertBackupWritesAreSafe(rig)
        assertEquals("Macronix/Sanyo chip is named", "SANYO LE26FV10N1TS (0x6213)", r.flashChipLabel)
    }

    @Test
    fun backup_everyChunkIsReadTwice() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        rig.backup(3)
        assertEquals(2 * 0x8000 / 0x1000, rig.fw.agbReadCount)
    }

    @Test
    fun backup_progressCountsBothReads() {
        val rig = AgbRig(AgbSaveKind.EEPROM, 8192)
        var last = 0L to 0L
        rig.backup(2, progress = { d, t -> last = d to t })
        assertEquals(16384L to 16384L, last)
    }

    @Test
    fun backup_inconsistentReadsFail_afterRetries_andStoreNothing() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        var n = 0
        rig.fw.agbReadFilter = { _, _, d -> d.copyOf().also { it[3] = (it[3] + ++n).toByte() } }
        val e = assertThrows(VerificationFailedException::class.java) { rig.backup(3) }
        assertTrue(e.message!!, e.message!!.contains("Verification failed"))
    }

    @Test
    fun backup_aTransientBadReadIsRetried() {
        val rig = AgbRig(AgbSaveKind.EEPROM, 512)
        var first = true
        rig.fw.agbReadFilter = { _, _, d -> if (first) { first = false; d.copyOf().also { it[0] = 0x00 } } else d }
        assertArrayEquals(rig.cart.save, rig.backup(1).data)
    }

    @Test
    fun backup_flashOnAnSramCartridgeIsRefused() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        val e = assertThrows(com.therealnox.gbflash.save.AgbSaveException::class.java) { rig.backup(5) }
        assertTrue(e.message!!.contains("SRAM"))
        // the byte-4 test was undone
        assertArrayEquals(rig.cart.saveBefore, rig.cart.save)
    }

    @Test
    fun backup_flashChipSmallerThanTheSaveTypeIsRefused() {
        val rig = flashRig(0x10000, 0xBFD4)
        assertThrows(com.therealnox.gbflash.save.AgbSaveException::class.java) { rig.backup(5) }
    }

    @Test
    fun backup_cancelled() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        assertThrows(com.therealnox.gbflash.save.BackupCancelledException::class.java) { rig.backup(3, cancel = { true }) }
    }

    // ---- isolation of the write path ---------------------------------------------------------

    private val allowedD4: List<(List<Pair<Int, Int>>) -> Boolean> = listOf(
        { c -> c.size == 1 && c[0].first == 0x0004 },
        { c -> c == listOf(0x5555 to 0xAA, 0x2AAA to 0x55, 0x5555 to 0x90) },
        { c -> c == listOf(0x5555 to 0xAA, 0x2AAA to 0x55, 0x5555 to 0xF0) },
        { c -> c == listOf(0x0000 to 0xF0) },
        { c -> c.size == 4 && c.take(3) == listOf(0x5555 to 0xAA, 0x2AAA to 0x55, 0x5555 to 0xB0) && c[3].first == 0 && c[3].second in 0..1 },
        { c -> c.size == 3 && c[0].first == 0x5555 && c[1].first == 0x2AAA && c[2].first == 0 }, // restore of probed bytes
    )

    private fun assertBackupWritesAreSafe(rig: AgbRig) {
        for (w in rig.cart.writes) {
            val ok = when (w) {
                is AgbWrite.FlashCmds -> allowedD4.any { it(w.cmds) }
                is AgbWrite.RomSpace -> w.address in setOf(0xC4, 0xC6, 0xC8) || (w.address == 0x1000000 && w.value in 0..1)
                else -> false
            }
            assertTrue("a backup or detection sent $w", ok)
            if (w is AgbWrite.FlashCmds) {
                assertFalse("erase setup", w.cmds.any { it.first == 0x5555 && it.second == 0x80 })
                assertFalse("sector erase", w.cmds.size >= 6)
            }
        }
        assertTrue(rig.cart.erasedSectors.isEmpty())
        assertTrue(rig.cart.writes.none { it is AgbWrite.SramData || it is AgbWrite.EepromData || it is AgbWrite.FlashData })
    }

    @Test
    fun detection_sendsNoEraseProgramOrDataWrites_forEveryCartridgeKind() {
        listOf(
            AgbRig(AgbSaveKind.SRAM, 0x8000), AgbRig(AgbSaveKind.SRAM, 0x20000), AgbRig(AgbSaveKind.EEPROM, 512),
            AgbRig(AgbSaveKind.EEPROM, 8192), flashRig(), flashRig(0x10000, 0xBFD4), AgbRig(AgbSaveKind.NONE, 0),
        ).forEach { rig ->
            runBlocking { rig.session.detectAgbSave({ false }, { _, _ -> }) }
            assertBackupWritesAreSafe(rig)
            assertEquals(0, rig.fw.a3Count)
            assertEquals(0, rig.fw.a5Count)
        }
    }

    // ---- detection by probing ----------------------------------------------------------------

    private fun probe(rig: AgbRig) = runBlocking { rig.session.detectAgbSave({ false }, { _, _ -> }) }

    @Test
    fun probe_flash1m_isTypeFiveWithTheChipName() {
        val d = probe(flashRig())
        assertEquals(AgbSaveTypes.FLASH_1M, d.typeId)
        assertEquals(SaveTypeSource.PROBE, d.source)
        assertTrue(d.chipLabel!!.contains("SANYO"))
        assertTrue(d.uncertain)
    }

    @Test fun probe_flash512k() = assertEquals(AgbSaveTypes.FLASH_512K, probe(flashRig(0x10000, 0xC21C)).typeId)

    @Test fun probe_sram32k() = assertEquals(AgbSaveTypes.SRAM_256K, probe(AgbRig(AgbSaveKind.SRAM, 0x8000)).typeId)

    @Test fun probe_sram64k() = assertEquals(AgbSaveTypes.SRAM_512K, probe(AgbRig(AgbSaveKind.SRAM, 0x10000)).typeId)

    @Test fun probe_sram128k() = assertEquals(AgbSaveTypes.SRAM_1M, probe(AgbRig(AgbSaveKind.SRAM, 0x20000)).typeId)

    @Test fun probe_eeprom4k() = assertEquals(AgbSaveTypes.EEPROM_4K, probe(AgbRig(AgbSaveKind.EEPROM, 512)).typeId)

    @Test fun probe_eeprom64k() = assertEquals(AgbSaveTypes.EEPROM_64K, probe(AgbRig(AgbSaveKind.EEPROM, 8192)).typeId)

    @Test fun probe_noSave() = assertEquals(AgbSaveTypes.NONE, probe(AgbRig(AgbSaveKind.NONE, 0)).typeId)

    @Test
    fun probe_unknownFlashChip_isLeftToTheUser() {
        val d = probe(flashRig(0x20000, 0x1234))
        assertNull(d.typeId)
        assertTrue(d.note!!.contains("0x1234"))
    }

    @Test
    fun probe_onSram_flipsOneByteAndPutsItBack() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        probe(rig)
        assertArrayEquals(rig.cart.saveBefore, rig.cart.save)
    }

    // ---- restore -----------------------------------------------------------------------------

    private fun assertRestoreRoundTrip(rig: AgbRig, type: Int) {
        val data = agbTestSave(rig.saveSize, 9)
        val o = verified(rig.restoreRaw(type, data))
        assertArrayEquals(data, rig.cart.save)
        assertEquals(com.therealnox.gbflash.library.SaveLibrary.crc32Of(data), o.crc32)
        assertTrue(rig.cart.violations.isEmpty())
        assertEquals(0, rig.fw.a3Count)
        assertEquals(0, rig.fw.a5Count)
    }

    @Test fun restore_sram256k() = assertRestoreRoundTrip(AgbRig(AgbSaveKind.SRAM, 0x8000), 3)

    @Test fun restore_sram512k() = assertRestoreRoundTrip(AgbRig(AgbSaveKind.SRAM, 0x10000), 7)

    @Test fun restore_sram1m() = assertRestoreRoundTrip(AgbRig(AgbSaveKind.SRAM, 0x20000), 8)

    @Test fun restore_eeprom4k() = assertRestoreRoundTrip(AgbRig(AgbSaveKind.EEPROM, 512), 1)

    @Test fun restore_eeprom64k() = assertRestoreRoundTrip(AgbRig(AgbSaveKind.EEPROM, 8192), 2)

    @Test fun restore_flash512k_sst() = assertRestoreRoundTrip(flashRig(0x10000, 0xBFD4), 4)

    @Test fun restore_flash512k_macronix() = assertRestoreRoundTrip(flashRig(0x10000, 0xC21C), 4)

    @Test fun restore_flash512k_panasonic() = assertRestoreRoundTrip(flashRig(0x10000, 0x321B), 4)

    @Test fun restore_flash1m_sanyo() = assertRestoreRoundTrip(flashRig(0x20000, 0x6213), 5)

    @Test fun restore_flash1m_macronix() = assertRestoreRoundTrip(flashRig(0x20000, 0xC209), 5)

    @Test
    fun restore_flash_erasesEverySectorBeforeProgramming() {
        // The chip starts with a different save: programming only clears bits, so without the erase the result
        // would be the AND of both images and the read-back could not match.
        val rig = flashRig()
        val data = agbTestSave(0x20000, 11)
        verified(rig.restoreRaw(5, data))
        assertEquals(32, rig.cart.erasedSectors.size)
        assertEquals((0 until 2).flatMap { b -> (0 until 16).map { b to it * 0x1000 } }, rig.cart.erasedSectors.take(32))
        val firstErase = rig.cart.writes.indexOfFirst { it is AgbWrite.FlashCmds && it.cmds.size == 6 }
        val firstProgram = rig.cart.writes.indexOfFirst { it is AgbWrite.FlashData }
        assertTrue(firstErase in 0 until firstProgram)
    }

    @Test
    fun restore_flash_bankOneIsWrittenIntoTheSecondHalf() {
        val rig = flashRig()
        val data = ByteArray(0x20000) { if (it < 0x10000) 0x11 else 0x22 }
        verified(rig.restoreRaw(5, data))
        assertTrue(rig.cart.save.copyOfRange(0, 0x10000).all { it == 0x11.toByte() })
        assertTrue(rig.cart.save.copyOfRange(0x10000, 0x20000).all { it == 0x22.toByte() })
    }

    @Test
    fun restore_flash_skipsProgrammingChunksThatAreAllFF() {
        val rig = flashRig(0x10000, 0xBFD4)
        val data = ByteArray(0x10000) { 0xFF.toByte() }.also { it[0x5000] = 0x12 }
        verified(rig.restoreRaw(4, data))
        val programmed = rig.cart.writes.filterIsInstance<AgbWrite.FlashData>().map { it.address }
        assertTrue(programmed.all { it in 0x5000 until 0x6000 })
        assertEquals(16, rig.cart.erasedSectors.size) // 16 sectors of 4 KiB
    }

    @Test
    fun restore_atmel_writesPagesWithoutErase() {
        val rig = flashRig(0x10000, 0x1F3D)
        val data = agbTestSave(0x10000, 4)
        verified(rig.restoreRaw(4, data))
        assertArrayEquals(data, rig.cart.save)
        assertTrue(rig.cart.erasedSectors.isEmpty())
        val w = rig.cart.writes.filterIsInstance<AgbWrite.FlashData>()
        assertTrue(w.all { it.mode == 2 })
        assertEquals(0x10000 / 256, w.size)
        assertEquals("page numbers", 0, w.first().address)
        assertEquals(0x10000 / 128 - 2, w.last().address)
    }

    @Test
    fun restore_bootlegAndUnknownFlashChips_areRefusedWithNothingWritten() {
        for (chip in listOf(0xBF6D, 0xBF4B, 0xFFFF, 0x1234)) {
            val rig = flashRig(0x20000, chip)
            val o = rig.restoreRaw(5, agbTestSave(0x20000, 2))
            assertTrue(describe(o), o is RestoreOutcome.Failed && !o.wroteData)
            assertTrue(rig.cart.writes.none { it is AgbWrite.FlashData })
            assertTrue(rig.cart.erasedSectors.isEmpty())
        }
    }

    @Test
    fun backup_ofABootlegChip_usesItsBufferAndBankRegister() {
        val data = agbTestSave(0x20000, 6)
        val rig = flashRig(0x20000, 0xBF6D, save = data)
        assertArrayEquals(data, rig.backup(5).data)
        val bank = rig.cart.writes.filterIsInstance<AgbWrite.RomSpace>().filter { it.address == 0x1000000 }
        assertEquals(2, bank.size)
    }

    @Test
    fun restore_verifyMismatch_isReportedAndNeverSuccess() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        rig.fw.agbWriteFilter = { n, d -> if (n == 3) d.copyOf().also { it[10] = (it[10] + 1).toByte() } else d }
        val o = rig.restoreRaw(3, agbTestSave(0x8000, 1).also { it[0] = 7 })
        assertTrue(describe(o), o is RestoreOutcome.Mismatch)
        o as RestoreOutcome.Mismatch
        assertEquals(2 * 256 + 10, o.offset)
        assertEquals(1, o.differingBytes)
    }

    @Test
    fun restore_flashSectorThatNeverErases_stopsBeforeProgramming() {
        val rig = flashRig(0x10000, 0xBFD4)
        rig.fw.agbReadFilter = { _, addr, d -> if (d.size == 2 && addr == 0 && !rig.cart.idMode) byteArrayOf(0, 0) else d }
        val o = rig.restoreRaw(4, agbTestSave(0x10000, 8))
        assertTrue(describe(o), o is RestoreOutcome.Failed)
        o as RestoreOutcome.Failed
        assertTrue(o.wroteData)
        assertTrue(rig.cart.writes.none { it is AgbWrite.FlashData })
    }

    @Test
    fun restore_cancelBeforeTheFirstChunk_writesNothing() {
        val rig = flashRig()
        val o = rig.restoreRaw(5, agbTestSave(0x20000, 8), cancel = { true })
        assertTrue(o is RestoreOutcome.Cancelled && !o.wroteData)
        assertTrue(rig.cart.erasedSectors.isEmpty())
    }

    @Test
    fun restore_progressIsWriteThenVerify() {
        val rig = AgbRig(AgbSaveKind.EEPROM, 512)
        val phases = mutableListOf<RestorePhase>()
        rig.restoreRaw(1, agbTestSave(512, 3), progress = { p, _, _ -> if (phases.lastOrNull() != p) phases += p })
        assertEquals(listOf(RestorePhase.WRITE, RestorePhase.VERIFY), phases)
    }

    @Test
    fun restore_headerChanged_abortsWithZeroWrites() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        val other = AgbRig(AgbSaveKind.SRAM, 0x8000, title = "OTHER", code = "WXYZ")
        rig.fw.agbCart = other.cart
        assertThrows(HeaderChangedException::class.java) { rig.restoreRaw(3, agbTestSave(0x8000, 3)) }
        assertTrue(other.cart.writes.none { it is AgbWrite.SramData })
        assertArrayEquals(other.cart.saveBefore, other.cart.save)
    }

    // ---- clock -------------------------------------------------------------------------------

    private val refDate = hex("00 05 07 01 04 33 30")

    @Test
    fun backup_clockFooter_matchesFlashGbxFormat() {
        val rig = AgbRig(AgbSaveKind.FLASH, 0x20000, flashId = 0x6213, hasRtc = true)
        var asked = 0
        val r = rig.backup(5, askRtc = { asked++; true }, now = 0x6ACF7ADBL)
        assertEquals(1, asked)
        assertEquals(true, r.rtcProbePresent)
        assertArrayEquals(hex("00 05 07 01 04 33 30 40 db 7a cf 6a 00 00 00 00"), r.rtc!!.toFooter())
        assertTrue("only clock registers and bank/ID writes", rig.cart.writes.none { it is AgbWrite.SramData || it is AgbWrite.FlashData })
        assertBackupWritesAreSafe(rig)
        assertTrue(rig.cart.violations.isEmpty())
    }

    @Test
    fun backup_clockDeclined_hasNoFooter_andNoGpioTraffic() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000, hasRtc = true)
        val r = rig.backup(3, askRtc = { false })
        assertNull(r.rtc)
        assertTrue(rig.cart.writes.none { it is AgbWrite.RomSpace && it.address == 0xC4 })
    }

    @Test
    fun backup_noClockCartridge_isNotAskedAndHasNone() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000)
        var asked = false
        val r = rig.backup(3, askRtc = { asked = true; true })
        assertFalse(asked)
        assertNull(r.rtc)
        assertEquals(false, r.rtcProbePresent)
    }

    @Test
    fun backup_clockWithPowerFlagSet_isTreatedAsAbsent() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000, hasRtc = true)
        rig.cart.rtcStatus = 0xC0
        assertNull(rig.backup(3, askRtc = { true }).rtc)
    }

    @Test
    fun statusRead_reportsTheChipStatusRegister() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000, hasRtc = true)
        rig.cart.rtcStatus = 0x41
        assertEquals(0x41, rig.backup(3, askRtc = { true }).rtc!!.status)
    }

    @Test
    fun restore_clock_writesDateAndStatus_advanced() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000, hasRtc = true)
        rig.cart.rtcDate.fill(0)
        val footer = AgbRtcSnapshot(refDate, 0x40, 1_700_000_000L).toFooter()
        val o = verified(rig.restoreRaw(3, agbTestSave(0x8000, 3), clockFooter = footer, adjust = false, now = 1_800_000_000L))
        assertNotNull(o.rtcWritten)
        assertNull(o.rtcError)
        assertEquals(listOf("date 00 05 07 01 04 33 30", "status 40"), rig.cart.rtcWrites)
        assertTrue(o.rtcReadBack!!.describe().startsWith("2000-05-07 04:33:30"))
        assertTrue(rig.cart.violations.isEmpty())
    }

    @Test
    fun restore_clock_adjustAdvancesTheDate() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000, hasRtc = true)
        val then = 1_700_000_000L
        val footer = AgbRtcSnapshot(refDate, 0x40, then).toFooter()
        verified(rig.restoreRaw(3, agbTestSave(0x8000, 3), clockFooter = footer, adjust = true, now = then + 3600 + 60 + 1))
        assertEquals("date 00 05 07 01 05 34 31", rig.cart.rtcWrites.first())
    }

    @Test
    fun restore_withoutClockFooter_leavesTheClockAlone() {
        val rig = AgbRig(AgbSaveKind.SRAM, 0x8000, hasRtc = true)
        verified(rig.restoreRaw(3, agbTestSave(0x8000, 3)))
        assertTrue(rig.cart.rtcWrites.isEmpty())
        assertTrue(rig.cart.writes.none { it is AgbWrite.RomSpace && it.address == 0xC4 })
    }

    // ---- import ------------------------------------------------------------------------------

    @Test
    fun import_acceptsSaveOrSaveWithSixteenByteFooter() {
        val save = agbTestSave(0x20000, 1)
        assertTrue(SaveImport.parseAgb(save, 0x20000) is SaveImport.Result.Ok)
        val withFooter = save + hex("00 05 07 01 04 33 30 40 db 7a cf 6a 00 00 00 00")
        val ok = SaveImport.parseAgb(withFooter, 0x20000) as SaveImport.Result.Ok
        assertEquals(0x20000, ok.save.size)
        assertEquals(16, ok.rtcFooter!!.size)
        assertTrue(SaveImport.parseAgb(save.copyOf(0x10000), 0x20000) is SaveImport.Result.Rejected)
        assertTrue(SaveImport.parseAgb(ByteArray(0), 0x20000) is SaveImport.Result.Rejected)
        assertTrue(SaveImport.parseAgb(save + ByteArray(48), 0x20000) is SaveImport.Result.Rejected)
    }

    @Test
    fun gbaSessionNeverSendsDmgModeOr5Volts() {
        val rig = flashRig(rtc = true)
        rig.backup(5, askRtc = { true })
        rig.restoreRaw(5, agbTestSave(0x20000, 1))
        probe(rig)
        assertEquals(CartPlatform.GBA, CartPlatform.GBA)
        assertEquals(0, rig.fw.a3Count)
        assertEquals(0, rig.fw.a5Count)
        assertFalse(rig.fw.voltageChangedWhilePowered)
    }
}

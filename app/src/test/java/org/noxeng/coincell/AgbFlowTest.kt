package org.noxeng.coincell

import org.noxeng.coincell.cart.AgbRtcSnapshot
import org.noxeng.coincell.library.BackupKind
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.NewBackup
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.library.displayPlatform
import org.noxeng.coincell.restore.RestoreMatch
import org.noxeng.coincell.restore.RestoreRequest
import org.noxeng.coincell.restore.RestoreStatus
import org.noxeng.coincell.restore.SafetyCopy
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
import java.io.File
import java.util.zip.CRC32

/** The restore flow for GBA cartridges: safety copy, confirm, write, verify, record, undo; library details. */
class AgbFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun flashRig(rtc: Boolean = false, code: String = "ABCD", title: String = "TESTGBA", version: Int = 0) =
        AgbRig(AgbSaveKind.FLASH, 0x20000, flashId = 0x6213, hasRtc = rtc, title = title, code = code, version = version)

    private fun env(r: AgbRig, type: Int = 5) = AgbEnv(tmp.newFolder(), r, type)

    private fun AgbEnv.safety(req: RestoreRequest, cancel: () -> Boolean = { false }) =
        runBlocking { coordinator.takeSafetyCopy(req, cancel) { _, _ -> } }

    private fun AgbEnv.write(req: RestoreRequest, s: SafetyCopy.Ready) =
        runBlocking { coordinator.writeAndVerify(req, s, { false }) { _, _, _ -> } }

    private fun writes(r: AgbRig) = r.cart.writes.count { it is AgbWrite.FlashData || it is AgbWrite.SramData || it is AgbWrite.EepromData } +
        r.cart.erasedSectors.size

    @Test
    fun fullRestore_safetyCopyFirst_thenErase_write_verify_andRecord() {
        val r = flashRig()
        val e = env(r)
        val original = r.cart.save.copyOf()
        val backup = agbTestSave(0x20000, 77)
        val entry = e.store(backup)
        val req = e.request(entry)
        assertEquals(RestoreMatch.SameGame, req.review.match)
        val s = e.safety(req) as SafetyCopy.Ready
        assertEquals("nothing written during the safety copy", 0, writes(r))
        assertFalse(s.reusedExisting)
        assertArrayEquals(original, e.library.readSave(s.entry))
        assertEquals(BackupKind.PRE_RESTORE, s.entry.kind)
        assertEquals(5, s.entry.saveType)
        val result = e.write(req, s)
        assertEquals(result.message, RestoreStatus.VERIFIED, result.status)
        assertArrayEquals(backup, r.cart.save)
        assertEquals("verified", e.library.restoreRecords(r.game.key).single().outcome)
    }

    @Test
    fun safetyCopyFailure_meansZeroWrites_andNothingStored() {
        val r = flashRig()
        val e = env(r)
        val entry = e.store(agbTestSave(0x20000, 2))
        val req = e.request(entry)
        r.fw.agbReadFilter = { _, _, d -> d.copyOf().also { it[0] = (it[0] + 1).toByte() } }
        // the filter makes the two reads of a chunk differ every time only if it changes between calls
        var n = 0
        r.fw.agbReadFilter = { _, _, d -> d.copyOf().also { it[1] = (it[1] + ++n).toByte() } }
        val s = e.safety(req)
        assertTrue(s.toString(), s is SafetyCopy.Failed)
        assertEquals(0, writes(r))
        assertTrue(e.library.list(r.game.key).none { it.kind == BackupKind.PRE_RESTORE })
    }

    @Test
    fun safetyCopyCancelled_meansZeroWrites() {
        val r = flashRig()
        val e = env(r)
        val req = e.request(e.store(agbTestSave(0x20000, 2)))
        assertEquals(SafetyCopy.Cancelled, e.safety(req, cancel = { true }))
        assertEquals(0, writes(r))
    }

    @Test
    fun identicalLibraryCopy_isReusedAsTheSafetyCopy() {
        val r = flashRig()
        val e = env(r)
        val current = e.store(r.cart.save.copyOf())
        val req = e.request(e.store(agbTestSave(0x20000, 3)))
        val s = e.safety(req) as SafetyCopy.Ready
        assertTrue(s.reusedExisting)
        assertEquals(current.id, s.entry.id)
    }

    @Test
    fun writeNeedsTheSafetyCopyOfThisVeryRestore() {
        val r = flashRig()
        val e = env(r)
        val a = e.request(e.store(agbTestSave(0x20000, 3)))
        val b = e.request(e.store(agbTestSave(0x20000, 4)))
        val s = e.safety(a) as SafetyCopy.Ready
        assertThrows(IllegalArgumentException::class.java) { e.write(b, s) }
        assertEquals(0, writes(r))
    }

    @Test
    fun headerChangedBetweenReviewAndWrite_abortsWithNoWrite() {
        val r = flashRig()
        val e = env(r)
        val req = e.request(e.store(agbTestSave(0x20000, 3)))
        val s = e.safety(req) as SafetyCopy.Ready
        val other = AgbRig(AgbSaveKind.FLASH, 0x20000, flashId = 0x6213, title = "OTHER", code = "WXYZ")
        r.fw.agbCart = other.cart
        val result = e.write(req, s)
        assertEquals(RestoreStatus.NOT_WRITTEN, result.status)
        assertEquals(0, writes(other))
    }

    @Test
    fun verifyMismatch_isNeverReportedAsSuccess() {
        val r = AgbRig(AgbSaveKind.SRAM, 0x8000)
        val e = env(r, 3)
        val req = e.request(e.store(agbTestSave(0x8000, 3)))
        val s = e.safety(req) as SafetyCopy.Ready
        r.fw.agbWriteFilter = { n, d -> if (n == 2) d.copyOf().also { it[0] = (it[0] + 1).toByte() } else d }
        val result = e.write(req, s)
        assertEquals(RestoreStatus.VERIFY_MISMATCH, result.status)
        assertTrue(result.message.contains("save offset 0x00100"))
        assertEquals(e.library.restoreRecords(r.game.key).single().outcome, "verify-failed")
    }

    // ---- matching rules ----------------------------------------------------------------------

    @Test
    fun blocked_whenTheSaveTypeDiffersEvenIfTheSizeMatches() {
        val r = AgbRig(AgbSaveKind.SRAM, 0x10000)
        val e = env(r, 7)
        val entry = e.store(agbTestSave(0x10000, 1), saveType = 4) // a 512K Flash backup, 64 KiB too
        val m = e.review(entry).match
        assertTrue(m.toString(), m is RestoreMatch.Blocked)
    }

    @Test
    fun blocked_whenTheSizeDiffers() {
        val r = AgbRig(AgbSaveKind.SRAM, 0x8000)
        val e = env(r, 3)
        val m = e.review(e.store(agbTestSave(0x10000, 1), saveType = 3)).match
        assertTrue(m is RestoreMatch.Blocked)
    }

    @Test
    fun blocked_acrossPlatforms() {
        val r = AgbRig(AgbSaveKind.SRAM, 0x8000)
        val e = env(r, 3)
        val dmg = GameInfo("DMG", "TESTGBA", "DMG_TESTGBA_1234", "x", null, 0x1234, 0, null)
        val entry = e.store(agbTestSave(0x8000, 1), game = dmg)
        assertTrue(e.review(entry).match is RestoreMatch.Blocked)
    }

    @Test
    fun otherCartridgeOfTheSameGame_isAllowedWithANotice() {
        val r = AgbRig(AgbSaveKind.SRAM, 0x8000)
        val e = env(r, 3)
        val rev1 = AgbRig(AgbSaveKind.SRAM, 0x8000, version = 1).game
        assertTrue(rev1.key != r.game.key)
        val m = e.review(e.store(agbTestSave(0x8000, 1), game = rev1)).match
        assertTrue(m.toString(), m is RestoreMatch.DifferentCartridge)
    }

    @Test
    fun sameTitleButAnotherGameCode_needsTheCodeTyped() {
        val r = AgbRig(AgbSaveKind.SRAM, 0x8000, title = "POKEMON SAPP", code = "AXPF")
        val e = env(r, 3)
        val usa = AgbRig(AgbSaveKind.SRAM, 0x8000, title = "POKEMON SAPP", code = "AXPE").game
        val review = e.review(e.store(agbTestSave(0x8000, 1), game = usa))
        val m = review.match as RestoreMatch.DifferentTitle
        assertEquals("AXPF", m.requiredTitle)
        assertFalse(org.noxeng.coincell.restore.RestorePolicy.mayStart(review, "POKEMON SAPP"))
        assertTrue(org.noxeng.coincell.restore.RestorePolicy.mayStart(review, "axpf"))
        val req = RestoreRequest(review, "")
        assertThrows(IllegalArgumentException::class.java) { e.safety(req) }
    }

    @Test
    fun differentTitle_needsTheTitleTyped() {
        val r = AgbRig(AgbSaveKind.SRAM, 0x8000, title = "GAME ONE", code = "AAAA")
        val e = env(r, 3)
        val other = AgbRig(AgbSaveKind.SRAM, 0x8000, title = "GAME TWO", code = "BBBB").game
        val m = e.review(e.store(agbTestSave(0x8000, 1), game = other)).match as RestoreMatch.DifferentTitle
        assertEquals("GAME ONE", m.requiredTitle)
    }

    // ---- the clock through the coordinator ---------------------------------------------------

    @Test
    fun clockIsOfferedOnlyWhenBothSidesHaveOne_andRestoredOnRequest() {
        val r = flashRig(rtc = true)
        val e = env(r)
        hex("01 02 03 04 05 06 07").copyInto(r.cart.rtcDate)
        val footer = AgbRtcSnapshot(hex("00 05 07 01 04 33 30"), 0x40, 1_700_000_000L).toFooter()
        val entry = e.store(agbTestSave(0x20000, 5), rtcFooter = footer)
        assertEquals(16, e.library.readRtcFooter(entry)!!.size)
        assertNotNull(entry.agbRtc)
        assertTrue(e.review(entry).clockOptionOffered)
        assertFalse(e.review(entry, clockPresent = false).clockOptionOffered)
        assertFalse(e.review(entry, clockPresent = null).clockOptionOffered)

        val req = e.request(entry, clock = true, adjust = false)
        val s = e.safety(req) as SafetyCopy.Ready
        assertNotNull("the safety copy keeps the cartridge clock", s.entry.agbRtc)
        val result = e.write(req, s)
        assertEquals(result.message, RestoreStatus.VERIFIED, result.status)
        assertTrue(result.clockLine!!.contains("2000-05-07 04:33:30"))
        assertEquals(listOf("date 00 05 07 01 04 33 30", "status 40"), r.cart.rtcWrites)
    }

    @Test
    fun clockSwitchOff_leavesTheClockAlone() {
        val r = flashRig(rtc = true)
        val e = env(r)
        val footer = AgbRtcSnapshot(hex("00 05 07 01 04 33 30"), 0x40, 1L).toFooter()
        val req = e.request(e.store(agbTestSave(0x20000, 5), rtcFooter = footer), clock = false)
        val s = e.safety(req) as SafetyCopy.Ready
        e.write(req, s)
        assertTrue(r.cart.rtcWrites.isEmpty())
    }

    @Test
    fun undo_putsTheSafetyCopyBack_withoutAnotherCopy() {
        val r = flashRig()
        val e = env(r)
        val original = r.cart.save.copyOf()
        val req = e.request(e.store(agbTestSave(0x20000, 8)))
        val s = e.safety(req) as SafetyCopy.Ready
        e.write(req, s)
        val undo = e.request(s.entry, known = e.library.list(r.game.key).first { it.kind == BackupKind.BACKUP })
        val s2 = e.safety(undo) as SafetyCopy.Ready
        assertTrue(s2.reusedExisting)
        e.write(undo, s2)
        assertArrayEquals(original, r.cart.save)
    }

    // ---- library -----------------------------------------------------------------------------

    @Test
    fun gameKey_isPlatformTitleGameCodeAndHeaderChecksum() {
        val g = AgbRig(AgbSaveKind.SRAM, 0x8000, title = "POKEMON SAPP", code = "AXPF").game
        assertEquals("AGB", g.platform)
        assertTrue(g.key, Regex("AGB_POKEMON-SAPP_AXPF_[0-9A-F]{2}").matches(g.key))
        assertEquals("AXPF", g.gameCode)
        assertEquals(org.noxeng.coincell.library.DisplayPlatform.GBA, g.displayPlatform)
    }

    @Test
    fun library_rtcFooterSizeFollowsThePlatform() {
        val lib = SaveLibrary(tmp.newFolder())
        val gba = AgbRig(AgbSaveKind.SRAM, 0x8000).game
        val dmg = GameInfo("DMG", "X", "DMG_X_1234", "x", null, 0x1234, 0, null)
        val save = ByteArray(0x8000)
        assertThrows(IllegalArgumentException::class.java) { lib.store(gba, nb(save, ByteArray(48))) }
        assertThrows(IllegalArgumentException::class.java) { lib.store(dmg, nb(save, ByteArray(16))) }
        val e = lib.store(gba, nb(save, AgbRtcSnapshot(hex("00 05 07 01 04 33 30"), 0x40, 5).toFooter()))
        assertEquals(16, File(tmp.root.walkTopDown().first { it.name == "rtc.bin" }.path).length().toInt())
        assertEquals(save.size + 16, lib.exportBytes(e, includeRtc = true).size)
        assertEquals(save.size, lib.exportBytes(e, includeRtc = false).size)
    }

    private fun nb(save: ByteArray, footer: ByteArray?) = NewBackup(save, footer, null, false, "L15", "test", true, null)

    @Test
    fun library_storesTheSaveTypeAndItsSource() {
        val r = AgbRig(AgbSaveKind.FLASH, 0x20000)
        val lib = SaveLibrary(tmp.newFolder())
        val e = lib.store(
            r.game,
            NewBackup(ByteArray(0x20000), null, null, false, "L15", "t", true, null, saveType = 5, saveTypeSource = org.noxeng.coincell.cart.SaveTypeSource.USER, flashChip = "SANYO"),
        )
        assertEquals(5, e.saveType)
        assertEquals(org.noxeng.coincell.cart.SaveTypeSource.USER, e.saveTypeSource)
        assertEquals("SANYO", e.flashChip)
        assertTrue(File(tmp.root, "").walkTopDown().first { it.name == "backup.json" }.readText().contains("\"saveTypeSource\": \"user\""))
    }

    // ---- real FlashGBX reference (optional, skipped when the personal fixture is absent) ----

    @Test
    fun flashGbxReferenceFile_splitsIntoSaveAndFooter() {
        val f = File("src/test/fixtures-local/sapphire-fr.sav")
        org.junit.Assume.assumeTrue("personal fixture not present", f.isFile)
        val bytes = f.readBytes()
        assertEquals(131088, bytes.size)
        val ok = org.noxeng.coincell.save.SaveImport.parseAgb(bytes, 0x20000) as org.noxeng.coincell.save.SaveImport.Result.Ok
        val crc = CRC32().also { it.update(ok.save) }.value
        assertEquals(0xDBF7C8BEL, crc)
        assertArrayEquals(hex("00 05 07 01 04 33 30 40 db 7a cf 6a 00 00 00 00"), ok.rtcFooter)
        // A Flash chip holding that save reads back to the same bytes through the whole backup path.
        val rig = AgbRig(AgbSaveKind.FLASH, 0x20000, flashId = 0x6213, hasRtc = true, save = ok.save)
        val r = rig.backup(5, askRtc = { true }, now = 0x6ACF7ADBL)
        assertArrayEquals(ok.save, r.data)
        assertArrayEquals(ok.rtcFooter, r.rtc!!.toFooter())
        assertNull(rig.cart.erasedSectors.firstOrNull())
    }
}

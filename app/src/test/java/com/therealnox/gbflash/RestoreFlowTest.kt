package com.therealnox.gbflash

import com.therealnox.gbflash.cart.RtcSnapshot
import com.therealnox.gbflash.library.BackupKind
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.restore.RestoreStatus
import com.therealnox.gbflash.restore.SafetyCopy
import com.therealnox.gbflash.save.RestorePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Safety copy -> confirm -> write -> verify -> record, plus undo (restore/RestoreCoordinator). */
class RestoreFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rig(
        title: String = "POKEMON BLUE",
        global: Int = 0x56A4,
        type: Int = 0x1B,
        ram: Int = 3,
        mapper: FakeMapper = FakeMapper.MBC5,
        size: Int = 0x8000,
        rtc: Boolean = false,
    ) = RestoreRig(type, ram, mapper, size, hasRtc = rtc, title = title, globalChecksum = global)

    private fun env(r: RestoreRig, dir: String = "saves") = RestoreEnv(tmp.newFolder(dir), r)

    private fun RestoreEnv.safety(req: com.therealnox.gbflash.restore.RestoreRequest, cancel: () -> Boolean = { false }) =
        runBlocking { coordinator.takeSafetyCopy(req, cancel) { _, _ -> } }

    private fun RestoreEnv.write(
        req: com.therealnox.gbflash.restore.RestoreRequest,
        s: SafetyCopy.Ready,
        cancel: () -> Boolean = { false },
        progress: (RestorePhase, Long, Long) -> Unit = { _, _, _ -> },
    ) = runBlocking { coordinator.writeAndVerify(req, s, cancel, progress) }

    private fun noWireTraffic(r: RestoreRig, block: () -> Unit) {
        r.link.writes.clear()
        block()
        assertTrue("nothing may be sent", r.link.writes.isEmpty())
    }

    // ---- the happy path ----------------------------------------------------------------------

    @Test
    fun fullRestore_safetyCopyFirst_thenWrite_thenVerifiedRecord() {
        val r = rig()
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50), note = "Before Elite Four")
        val req = e.request(backup)

        val s = e.safety(req) as SafetyCopy.Ready
        assertEquals("nothing written during the safety copy", 0, r.fw.b3Count)
        assertFalse(s.reusedExisting)
        assertEquals(BackupKind.PRE_RESTORE, s.entry.kind)
        assertTrue(s.entry.verified)
        assertEquals(SaveLibrary.crc32Of(r.sramBefore), s.entry.crc32)
        assertArrayEquals(r.sramBefore, e.library.readSave(s.entry))
        assertEquals("stored under the cartridge's game identity", r.game.key, s.entry.gameKey)
        assertTrue(s.entry.note.contains(backup.id))
        assertEquals(2, e.library.list(r.game.key).size)

        val res = e.write(req, s)
        assertEquals(res.message, RestoreStatus.VERIFIED, res.status)
        assertTrue(res.verified)
        assertTrue(res.wroteData)
        assertArrayEquals(testSave(0x8000, 50), r.cart.sram)
        assertEquals(backup.crc32, res.readBackCrc32)
        assertEquals(backup.crc32, res.expectedCrc32)
        assertEquals(s.entry.id, res.safetyCopy.id)
        assertEquals(backup.id, res.source.id)
        assertFalse(r.cart.ramEnabled)

        val rec = e.library.restoreRecords(r.game.key).single()
        assertEquals("verified", rec.outcome)
        assertEquals(backup.id, rec.sourceBackupId)
        assertEquals(s.entry.id, rec.safetyCopyId)
        assertEquals(backup.crc32, rec.crc32)
    }

    @Test
    fun safetyCopy_isFullyReadBeforeTheFirstB3() {
        val r = rig()
        val e = env(r)
        val req = e.request(e.store(testSave(0x8000, 50)))
        val s = e.safety(req) as SafetyCopy.Ready
        assertNull("safety copy reads only", r.fw.ramReadCountAtFirstB3)
        e.write(req, s)
        assertEquals("all 64 chunks were read twice before the first write", 128, r.fw.ramReadCountAtFirstB3)
    }

    @Test
    fun progressPhases_areWriteThenVerify() {
        val r = rig(size = 0x2000, ram = 2)
        val e = env(r)
        val req = e.request(e.store(testSave(0x2000, 9)))
        val s = e.safety(req) as SafetyCopy.Ready
        val phases = mutableListOf<RestorePhase>()
        e.write(req, s, progress = { p, _, _ -> if (phases.lastOrNull() != p) phases += p })
        assertEquals(listOf(RestorePhase.WRITE, RestorePhase.VERIFY), phases)
    }

    // ---- safety copy failure means no write --------------------------------------------------

    @Test
    fun safetyCopyFailure_meansZeroB3_andNothingStored() {
        val r = rig()
        val e = env(r)
        val req = e.request(e.store(testSave(0x8000, 50)))
        r.fw.ramReadFilter = { n, _, d -> d.copyOf().also { it[0] = (it[0] + n).toByte() } } // reads never agree
        val s = e.safety(req)
        assertTrue(s is SafetyCopy.Failed)
        assertTrue((s as SafetyCopy.Failed).message.contains("nothing was written"))
        assertEquals(0, r.fw.b3Count)
        assertArrayEquals(r.sramBefore, r.cart.sram)
        assertTrue(r.cart.sramWriteAttempts.isEmpty())
        assertTrue(e.library.list(r.game.key).none { it.kind == BackupKind.PRE_RESTORE })
        assertTrue(e.library.restoreRecords(r.game.key).isEmpty())
        assertFalse("cleanup ran", r.cart.ramEnabled)
    }

    @Test
    fun safetyCopyCancelled_meansZeroB3_andNothingStored() {
        val r = rig()
        val e = env(r)
        val req = e.request(e.store(testSave(0x8000, 50)))
        val s = e.safety(req, cancel = { r.fw.ramReadCount >= 20 })
        assertEquals(SafetyCopy.Cancelled, s)
        assertEquals(0, r.fw.b3Count)
        assertTrue(r.cart.sramWriteAttempts.isEmpty())
        assertTrue(e.library.list(r.game.key).none { it.kind == BackupKind.PRE_RESTORE })
        assertFalse(r.cart.ramEnabled)
    }

    @Test
    fun safetyCopyThatCannotBeStored_meansZeroB3() {
        val cart1 = rig(global = 0x1111)
        val r = rig(global = 0x2222)
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50), game = cart1.game)
        // The cartridge's game directory cannot be created: a plain file sits in its place.
        File(File(tmp.root, "saves"), r.game.key).writeText("not a directory")
        val s = e.safety(e.request(backup))
        assertTrue(s is SafetyCopy.Failed)
        assertEquals(0, r.fw.b3Count)
        assertTrue(r.cart.sramWriteAttempts.isEmpty())
        assertArrayEquals(r.sramBefore, r.cart.sram)
    }

    @Test
    fun corruptLibraryEntry_isRefusedBeforeTouchingTheCartridge() {
        val r = rig()
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50))
        val file = File(File(File(tmp.root, "saves"), backup.gameKey), backup.id + "/" + SaveLibrary.SAVE_FILE)
        file.writeBytes(ByteArray(0x8000))
        val req = e.request(backup)
        noWireTraffic(r) {
            val s = e.safety(req)
            assertTrue(s is SafetyCopy.Failed)
        }
        assertEquals(0, r.fw.b3Count)
    }

    @Test
    fun safetyCopyInGbaMode_failsWithoutAnyWrite() {
        val r = rig()
        val e = env(r)
        val req = e.request(e.store(testSave(0x8000, 50)))
        r.fw.rom = agbHeader()
        runBlocking { r.session.readHeader(CartPlatform.GBA) }
        r.link.writes.clear()
        val s = e.safety(req)
        assertTrue(s is SafetyCopy.Failed)
        assertTrue(r.link.writes.isEmpty())
        assertEquals(0, r.fw.b3Count)
    }

    // ---- write phase outcomes ----------------------------------------------------------------

    @Test
    fun readBackMismatch_isReportedAsFailureWithPlaceAndSafetyPointer() {
        val r = rig()
        val e = env(r)
        val data = testSave(0x8000, 50)
        val req = e.request(e.store(data))
        val s = e.safety(req) as SafetyCopy.Ready
        r.fw.ramWriteFilter = { _, addr, d -> if (addr == 0xA100 && r.cart.ramBankReg == 1) d.copyOf().also { it[0] = (it[0] + 1).toByte() } else d }
        val res = e.write(req, s)
        assertEquals(RestoreStatus.VERIFY_MISMATCH, res.status)
        assertFalse(res.verified)
        assertTrue(res.wroteData)
        assertEquals(1, res.bank)
        assertEquals(0x100, res.offset)
        assertTrue(res.message, res.message.contains("cartridge contents uncertain"))
        assertTrue(res.message.contains("bank 1"))
        assertTrue(res.message.contains("0x0100"))
        assertTrue("points at the safety copy", res.message.contains(s.entry.id))
        assertEquals("verify-failed", e.library.restoreRecords(r.game.key).single().outcome)
        assertNotEquals("the record holds what the cartridge returned", res.expectedCrc32, e.library.restoreRecords(r.game.key).single().crc32)
    }

    @Test
    fun cancelDuringWrite_isPartial_runsCleanup_andPointsToSafetyCopy() {
        val r = rig()
        val e = env(r)
        val req = e.request(e.store(testSave(0x8000, 50)))
        val s = e.safety(req) as SafetyCopy.Ready
        val res = e.write(req, s, cancel = { r.fw.b3Count >= 10 })
        assertEquals(RestoreStatus.CANCELLED_PARTIAL, res.status)
        assertTrue(res.wroteData)
        assertTrue(res.message.contains("partly written"))
        assertTrue(res.message.contains(s.entry.id))
        assertFalse(r.cart.ramEnabled)
        assertEquals(0, r.cart.ramBankReg)
        assertEquals("cancelled-partial", e.library.restoreRecords(r.game.key).single().outcome)
        // the safety copy can put everything back
        val undo = e.request(s.entry)
        val s2 = e.safety(undo) as SafetyCopy.Ready
        assertFalse("the cartridge is not what the safety copy holds", s2.reusedExisting)
        val back = e.write(undo, s2)
        assertTrue(back.message, back.verified)
        assertArrayEquals(r.sramBefore, r.cart.sram)
    }

    @Test
    fun headerChangedBetweenReviewAndWrite_abortsWithNoWrite() {
        val r = rig()
        val e = env(r)
        val req = e.request(e.store(testSave(0x8000, 50)))
        val s = e.safety(req) as SafetyCopy.Ready
        val other = rig(title = "SOMETHING ELSE")
        r.swapCart(other.cart)
        val res = e.write(req, s)
        assertEquals(RestoreStatus.NOT_WRITTEN, res.status)
        assertFalse(res.wroteData)
        assertTrue(res.message, res.message.contains("changed"))
        assertEquals(0, r.fw.b3Count)
        assertArrayEquals(other.sramBefore, other.cart.sram)
        assertEquals("aborted-nothing-written", e.library.restoreRecords(r.game.key).single().outcome)
    }

    @Test
    fun writeNeedsTheSafetyCopyOfThisVeryRestore() {
        val r = rig()
        val e = env(r)
        val a = e.request(e.store(testSave(0x8000, 50)))
        val b = e.request(e.store(testSave(0x8000, 51)))
        val sa = e.safety(a) as SafetyCopy.Ready
        assertThrows(IllegalArgumentException::class.java) { e.write(b, sa) }
        assertEquals(0, r.fw.b3Count)
    }

    // ---- matching rules enforced by the coordinator ------------------------------------------

    @Test
    fun differentTitle_cannotStartWithoutTheTypedTitle() {
        val r = rig(title = "POKEMON BLUE")
        val e = env(r)
        val red = rig(title = "POKEMON RED", global = 0x9E00)
        val backup = e.store(testSave(0x8000, 50), game = red.game)
        noWireTraffic(r) {
            assertThrows(IllegalArgumentException::class.java) { e.safety(e.request(backup)) }
            assertThrows(IllegalArgumentException::class.java) { e.safety(e.request(backup, typed = "POKEMON RED")) }
        }
        val req = e.request(backup, typed = "pokemon blue")
        val s = e.safety(req) as SafetyCopy.Ready
        val res = e.write(req, s)
        assertTrue(res.message, res.verified)
        assertEquals("the pre-restore copy belongs to the cartridge, not the backup's game", r.game.key, s.entry.gameKey)
    }

    @Test
    fun blockedRestore_cannotStart_whateverTheUiDid() {
        val r = rig()
        val e = env(r)
        val small = e.store(ByteArray(0x2000) { 1 }) // 8 KiB backup for a 32 KiB cartridge
        val req = e.request(small, typed = "POKEMON BLUE")
        noWireTraffic(r) { assertThrows(IllegalArgumentException::class.java) { e.safety(req) } }
        assertEquals(0, r.fw.b3Count)
    }

    @Test
    fun secondCopyOfTheSameGame_restoresFromCart1ToCart2() {
        val cart1 = rig(global = 0x1111)
        val cart2 = rig(global = 0x2222)
        assertNotEquals(cart1.game.key, cart2.game.key)
        val e = env(cart2)
        val fromCart1 = e.store(testSave(0x8000, 60), game = cart1.game)
        assertEquals(listOf(fromCart1.id), e.library.listOtherCartridges(cart2.game).map { it.id })
        val req = e.request(fromCart1)
        assertTrue(req.review.match.toString(), req.review.match is com.therealnox.gbflash.restore.RestoreMatch.DifferentCartridge)
        val s = e.safety(req) as SafetyCopy.Ready
        assertEquals(cart2.game.key, s.entry.gameKey)
        val res = e.write(req, s)
        assertTrue(res.message, res.verified)
        assertArrayEquals(testSave(0x8000, 60), cart2.cart.sram)
        assertEquals(1, e.library.restoreRecords(cart2.game.key).size)
    }

    @Test
    fun importedEntry_isRestorableLikeAnyBackup() {
        val r = rig()
        val e = env(r)
        val imported = e.store(testSave(0x8000, 70), kind = BackupKind.IMPORTED, verified = false)
        val req = e.request(imported)
        assertEquals(BackupKind.IMPORTED, req.review.source.kind)
        assertEquals("edited.sav", req.review.source.originalFileName)
        val s = e.safety(req) as SafetyCopy.Ready
        assertTrue(e.write(req, s).verified)
        assertArrayEquals(testSave(0x8000, 70), r.cart.sram)
    }

    // ---- clock option ------------------------------------------------------------------------

    private fun clockRig(): RestoreRig = rig(type = 0x10, mapper = FakeMapper.MBC3, rtc = true).also {
        intArrayOf(9, 9, 9, 9, 0).copyInto(it.cart.liveRtc)
    }

    @Test
    fun clockNotRestored_byDefault_evenWhenTheBackupHasOne() {
        val r = clockRig()
        val e = env(r)
        val footer = RtcSnapshot(10, 0, 0, 0, 0, 1_700_000_000L).toFooter()
        val backup = e.store(testSave(0x8000, 50), rtcFooter = footer)
        val req = e.request(backup)
        assertTrue("option is offered", req.review.clockOptionOffered)
        assertFalse("but off", req.restoreClock)
        val s = e.safety(req) as SafetyCopy.Ready
        r.cart.rtcWrites.clear()
        val clockTogglesBefore = r.fw.clockToggles
        assertTrue(e.write(req, s).verified)
        assertTrue(r.cart.rtcWrites.isEmpty())
        assertEquals(clockTogglesBefore, r.fw.clockToggles)
        assertEquals(listOf(9, 9, 9, 9, 0), r.cart.liveRtc.toList())
    }

    @Test
    fun clockRestored_whenAsked_advancedByTheTimeSinceTheBackup() {
        val r = clockRig()
        val e = env(r)
        val footer = RtcSnapshot(10, 0, 0, 0, 0, 1_700_000_000L).toFooter()
        val backup = e.store(testSave(0x8000, 50), rtcFooter = footer)
        e.clockMillis = 1_700_000_100_000L // restored 100 s after the backup
        val req = e.request(backup, clock = true)
        val s = e.safety(req) as SafetyCopy.Ready
        r.cart.rtcWrites.clear()
        val res = e.write(req, s)
        assertTrue(res.message, res.verified)
        assertEquals(listOf(12 to 0x40, 8 to 50, 9 to 1, 10 to 0, 11 to 0, 12 to 0), r.cart.rtcWrites) // 10 s + 100 s = 1:50
        assertNotNull(res.clockLine)
        assertTrue(res.clockLine!!.contains("0d 00:01:50"))
    }

    @Test
    fun clockYes_withoutAdjust_writesTheStoredValues() {
        val r = clockRig()
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50), rtcFooter = RtcSnapshot(10, 0, 0, 0, 0, 1_700_000_000L).toFooter())
        e.clockMillis = 1_700_000_100_000L
        val req = e.request(backup, clock = true, adjust = false)
        val s = e.safety(req) as SafetyCopy.Ready
        r.cart.rtcWrites.clear()
        assertTrue(e.write(req, s).verified)
        assertEquals(listOf(12 to 0x40, 8 to 10, 9 to 0, 10 to 0, 11 to 0, 12 to 0), r.cart.rtcWrites)
    }

    @Test
    fun clockNo_leavesTheCartridgeClockAlone_evenWithAdjustTicked() {
        val r = clockRig()
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50), rtcFooter = RtcSnapshot(10, 0, 0, 0, 0, 1_700_000_000L).toFooter())
        val req = e.request(backup, clock = false, adjust = true)
        val s = e.safety(req) as SafetyCopy.Ready
        r.cart.rtcWrites.clear()
        assertTrue(e.write(req, s).verified)
        assertTrue(r.cart.rtcWrites.isEmpty())
        assertEquals(listOf(9, 9, 9, 9, 0), r.cart.liveRtc.toList())
    }

    @Test
    fun clockSwitchIgnored_whenNotOffered() {
        val r = clockRig()
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50)) // no rtc.bin
        val req = e.request(backup, clock = true)
        assertFalse(req.review.clockOptionOffered)
        val s = e.safety(req) as SafetyCopy.Ready
        r.cart.rtcWrites.clear()
        assertTrue(e.write(req, s).verified)
        assertTrue(r.cart.rtcWrites.isEmpty())
    }

    // ---- undo --------------------------------------------------------------------------------

    @Test
    fun undo_restoresTheSafetyCopy_throughTheSameFlow_withoutAnotherCopy() {
        val r = rig()
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50))
        val req = e.request(backup)
        val s = e.safety(req) as SafetyCopy.Ready
        val first = e.write(req, s)
        assertTrue(first.verified)
        assertArrayEquals(testSave(0x8000, 50), r.cart.sram)

        val entriesBefore = e.library.list(r.game.key).size
        val readsBefore = r.fw.ramReadCount
        val undoReq = e.request(first.safetyCopy, known = first.source)
        assertEquals("same game", com.therealnox.gbflash.restore.RestoreMatch.SameGame, undoReq.review.match)
        val s2 = e.safety(undoReq) as SafetyCopy.Ready
        // the cartridge was read (twice) and provably equals the backup we just wrote
        assertTrue(r.fw.ramReadCount > readsBefore)
        assertTrue(s2.reusedExisting)
        assertEquals(backup.id, s2.entry.id)
        assertEquals("no extra copy stored", entriesBefore, e.library.list(r.game.key).size)

        val b3Before = r.fw.b3Count
        val second = e.write(undoReq, s2)
        assertEquals(second.message, RestoreStatus.VERIFIED, second.status)
        assertTrue("undo still writes", r.fw.b3Count > b3Before)
        assertArrayEquals("original save is back", r.sramBefore, r.cart.sram)
        assertEquals(SaveLibrary.crc32Of(r.sramBefore), second.readBackCrc32)
        assertEquals(listOf("verified", "verified"), e.library.restoreRecords(r.game.key).map { it.outcome })
    }

    @Test
    fun undo_takesANewSafetyCopy_ifTheCartridgeChangedSinceTheRestore() {
        val r = rig()
        val e = env(r)
        val backup = e.store(testSave(0x8000, 50))
        val req = e.request(backup)
        val first = e.write(req, e.safety(req) as SafetyCopy.Ready)
        assertTrue(first.verified)
        r.cart.sram[123] = (r.cart.sram[123] + 1).toByte() // the game was played / another cartridge was inserted
        val undoReq = e.request(first.safetyCopy, known = first.source)
        val entriesBefore = e.library.list(r.game.key).size
        val s2 = e.safety(undoReq) as SafetyCopy.Ready
        assertFalse(s2.reusedExisting)
        assertEquals(BackupKind.PRE_RESTORE, s2.entry.kind)
        assertEquals(entriesBefore + 1, e.library.list(r.game.key).size)
        assertTrue(e.write(undoReq, s2).verified)
        assertArrayEquals(r.sramBefore, r.cart.sram)
    }

    @Test
    fun gameIdentity_readBackFromGameJson() {
        val r = rig()
        val e = env(r)
        e.store(testSave(0x8000, 1))
        val g = e.library.readGame(r.game.key)!!
        assertEquals(r.game, g)
        assertEquals("DMG", g.platform)
        assertNull(e.library.readGame("DMG_NOPE_0000"))
        assertEquals(GameInfo.sanitizeTitle("POKEMON BLUE"), GameInfo.sanitizeTitle(g.title))
    }
}

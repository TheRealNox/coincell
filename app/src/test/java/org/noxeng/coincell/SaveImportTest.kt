package org.noxeng.coincell

import org.noxeng.coincell.cart.RtcSnapshot
import org.noxeng.coincell.library.BackupKind
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.NewBackup
import org.noxeng.coincell.library.RestoreRecord
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.save.SaveCheck
import org.noxeng.coincell.save.SaveImport
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Importing a `.sav` (sizes, RTC footer split) and the library fields milestone 3 added. */
class SaveImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val save = testSave(0x8000, 3)
    private val footer = RtcSnapshot(21, 42, 13, 123, 0x41, 1_700_000_000L).toFooter()

    private fun ok(r: SaveImport.Result) = r as SaveImport.Result.Ok

    private fun rejected(r: SaveImport.Result) = (r as SaveImport.Result.Rejected).reason

    // ---- size handling -----------------------------------------------------------------------

    @Test
    fun exactSaveSize_isAccepted() {
        val r = ok(SaveImport.parse(save, 0x8000, clockCart = false))
        assertArrayEquals(save, r.save)
        assertNull(r.rtcFooter)
    }

    @Test
    fun saveSizePlus48_isSplitIntoSaveAndRtc() {
        val r = ok(SaveImport.parse(save + footer, 0x8000, clockCart = true))
        assertArrayEquals(save, r.save)
        assertArrayEquals(footer, r.rtcFooter)
    }

    @Test
    fun saveSizePlus48_withoutAClockOnTheCartridge_isRejectedWithAReason() {
        val reason = rejected(SaveImport.parse(save + footer, 0x8000, clockCart = false))
        assertTrue(reason, reason.contains("no clock"))
    }

    @Test
    fun bgbFooter_isRecognisedAndRejected() {
        val reason = rejected(SaveImport.parse(save + ByteArray(44), 0x8000, clockCart = true))
        assertTrue(reason, reason.contains("BGB"))
    }

    @Test
    fun otherSizes_areRejectedWithBothSizesInTheMessage() {
        for (n in listOf(1, 0x2000, 0x7FFF, 0x8001, 0x8000 + 47, 0x8000 + 49, 0x10000, 0x8000 + 0x30 * 2)) {
            val reason = rejected(SaveImport.parse(ByteArray(n), 0x8000, clockCart = true))
            assertTrue("$n: $reason", reason.contains("$n bytes") && reason.contains("32768"))
        }
    }

    @Test
    fun emptyFile_isRejected() {
        assertTrue(rejected(SaveImport.parse(ByteArray(0), 0x8000, clockCart = false)).contains("empty"))
    }

    @Test
    fun mbc2Size_512() {
        assertEquals(512, ok(SaveImport.parse(ByteArray(512), 512, clockCart = false)).save.size)
        assertTrue(SaveImport.parse(ByteArray(0x2000), 512, clockCart = false) is SaveImport.Result.Rejected)
    }

    @Test
    fun maxFileSize_isSaveSizePlusFooter() {
        assertEquals(0x8000 + 48, SaveImport.maxFileSize(0x8000))
    }

    // ---- stored as an imported entry ---------------------------------------------------------

    private val game = GameInfo("DMG", "POKEMON BLUE", "DMG_POKEMON-BLUE_56A4", "0x1B MBC5", 0x100000L, 0x56A4, 0x9C, null)

    private fun lib() = SaveLibrary(tmp.newFolder("saves")) { 1_700_000_000_000L }

    @Test
    fun importedEntry_keepsKindNameAndUnverifiedFlag() {
        val lib = lib()
        val e = lib.store(
            game,
            NewBackup(save, null, null, false, "", "test", false, SaveCheck.suspectReason(save), "Imported from edited.sav", BackupKind.IMPORTED, "edited.sav"),
        )
        assertEquals(BackupKind.IMPORTED, e.kind)
        assertEquals("edited.sav", e.originalFileName)
        assertFalse(e.verified)
        assertEquals(SaveLibrary.crc32Of(save), e.crc32)
        val again = lib.list(game.key).single()
        assertEquals(BackupKind.IMPORTED, again.kind)
        assertEquals("edited.sav", again.originalFileName)
        assertFalse(again.verified)
        assertArrayEquals(save, lib.readSave(again))
    }

    @Test
    fun importedWithClock_storesSaveSavAndRtcBinSeparately() {
        val lib = lib()
        val parsed = ok(SaveImport.parse(save + footer, 0x8000, clockCart = true))
        val e = lib.store(game, NewBackup(parsed.save, parsed.rtcFooter, null, true, "", "test", false, null, kind = BackupKind.IMPORTED, originalFileName = "gold.sav"))
        val dir = File(File(tmp.root, "saves"), "${game.key}/${e.id}")
        assertEquals(0x8000L, File(dir, "save.sav").length())
        assertEquals(48L, File(dir, "rtc.bin").length())
        assertArrayEquals(footer, File(dir, "rtc.bin").readBytes())
        assertTrue(e.rtcIncluded)
        assertEquals(123 + 256, e.rtc!!.days)
        assertArrayEquals(save, lib.exportBytes(e, false))
        assertArrayEquals(save + footer, lib.exportBytes(e, true))
    }

    @Test
    fun entriesWithoutKind_areNormalBackups() {
        val lib = lib()
        val e = lib.store(game, NewBackup(save, null, null, false, "L15", "test", true, null))
        assertEquals(BackupKind.BACKUP, e.kind)
        // a milestone 2 backup.json has no "kind" and no "originalFileName"
        val json = File(File(tmp.root, "saves"), "${game.key}/${e.id}/backup.json")
        val o = JSONObject(json.readText())
        o.remove("kind")
        json.writeText(o.toString())
        val old = lib.list(game.key).single()
        assertEquals(BackupKind.BACKUP, old.kind)
        assertNull(old.originalFileName)
        assertTrue(old.verified)
    }

    @Test
    fun preRestoreKind_roundTrips() {
        val lib = lib()
        val e = lib.store(game, NewBackup(save, null, null, false, "L15", "test", true, null, "auto", BackupKind.PRE_RESTORE))
        assertEquals(BackupKind.PRE_RESTORE, lib.list(game.key).single().kind)
        assertEquals("auto", e.note)
    }

    @Test
    fun unknownKind_isTreatedAsBackup() {
        assertEquals(BackupKind.BACKUP, BackupKind.fromJson("something-else"))
        assertEquals(BackupKind.BACKUP, BackupKind.fromJson(null))
        assertEquals(BackupKind.IMPORTED, BackupKind.fromJson("imported"))
    }

    // ---- restore records ---------------------------------------------------------------------

    @Test
    fun restoreRecords_appendInOrder_andSurviveReload() {
        val lib = lib()
        lib.store(game, NewBackup(save, null, null, false, "L15", "test", true, null))
        assertTrue(lib.restoreRecords(game.key).isEmpty())
        lib.addRestoreRecord(game.key, RestoreRecord(1000, "A", "S1", "verified", 0xDEADBEEFL, "ok"))
        lib.addRestoreRecord(game.key, RestoreRecord(2000, "S1", null, "verify-failed", null, "bad"))
        val again = SaveLibrary(File(tmp.root, "saves")).restoreRecords(game.key)
        assertEquals(listOf("verified", "verify-failed"), again.map { it.outcome })
        assertEquals(0xDEADBEEFL, again[0].crc32)
        assertEquals("S1", again[0].safetyCopyId)
        assertNull(again[1].safetyCopyId)
        assertNull(again[1].crc32)
        assertEquals(1000L, again[0].timestampMillis)
        // the records file does not look like a backup entry
        assertEquals(1, lib.list(game.key).size)
    }

    @Test
    fun otherCartridgesOfTheSameGame_areListed_unrelatedGamesAreNot() {
        val lib = lib()
        val second = game.copy(key = "DMG_POKEMON-BLUE_1234", globalChecksum = 0x1234)
        val red = game.copy(title = "POKEMON RED", key = "DMG_POKEMON-RED_9E00", globalChecksum = 0x9E00)
        val a = lib.store(game, NewBackup(save, null, null, false, "L15", "test", true, null))
        val b = lib.store(second, NewBackup(save, null, null, false, "L15", "test", true, null))
        lib.store(red, NewBackup(save, null, null, false, "L15", "test", true, null))
        assertEquals(listOf(b.gameKey), lib.listOtherCartridges(game).map { it.gameKey })
        assertEquals(listOf(a.gameKey), lib.listOtherCartridges(second).map { it.gameKey })
    }
}

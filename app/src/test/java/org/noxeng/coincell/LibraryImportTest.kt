package org.noxeng.coincell

import org.noxeng.coincell.cart.RtcSnapshot
import org.noxeng.coincell.library.BackupKind
import org.noxeng.coincell.library.DisplayPlatform
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.ImportGuesses
import org.noxeng.coincell.library.NewBackup
import org.noxeng.coincell.library.RestoreRecord
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.library.displayPlatform
import org.noxeng.coincell.save.SaveImport
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
import java.io.IOException

/** Importing a `.sav` without its cartridge, and linking that game to the cartridge later. */
class LibraryImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000L
    private val root by lazy { tmp.newFolder("saves") }
    private val lib by lazy { SaveLibrary(root) { clock } }

    private val crystalCart = EditorTestSaves.crystalGame
    private val save = ByteArray(0x8000) { (it * 7 + 3).toByte() }

    private fun imported(data: ByteArray = save, rtc: ByteArray? = null, kind: BackupKind = BackupKind.IMPORTED, parentId: String? = null) =
        NewBackup(data, rtc, null, rtc != null, "", "0.1.0", false, null, note = "Imported", kind = kind, originalFileName = "a.sav", parentId = parentId)

    // ---- parsing without a cartridge -------------------------------------------------------------

    @Test
    fun parseLoose_acceptsEverySupportedSize_withOrWithoutTheClockFooter() {
        for (size in SaveImport.DMG_SIZES) {
            assertTrue(SaveImport.parseLoose(ByteArray(size), gba = false) is SaveImport.Result.Ok)
            val withClock = SaveImport.parseLoose(ByteArray(size + RtcSnapshot.FOOTER_SIZE), gba = false) as SaveImport.Result.Ok
            assertEquals(size, withClock.save.size)
            assertEquals(RtcSnapshot.FOOTER_SIZE, withClock.rtcFooter!!.size)
        }
        val gba = SaveImport.parseLoose(ByteArray(0x10000 + 16), gba = true) as SaveImport.Result.Ok
        assertEquals(0x10000, gba.save.size)
        assertEquals(16, gba.rtcFooter!!.size)
    }

    @Test
    fun parseLoose_rejectsOddSizes_andExplainsBgbFooters() {
        assertTrue(SaveImport.parseLoose(ByteArray(0), gba = false) is SaveImport.Result.Rejected)
        val odd = SaveImport.parseLoose(ByteArray(1000), gba = false) as SaveImport.Result.Rejected
        assertTrue(odd.reason, odd.reason.contains("not a GB / GBC save size"))
        val bgb = SaveImport.parseLoose(ByteArray(0x8000 + 44), gba = false) as SaveImport.Result.Rejected
        assertTrue(bgb.reason, bgb.reason.contains("BGB"))
        // a GB clock footer is not a GBA one
        assertTrue(SaveImport.parseLoose(ByteArray(0x8000 + 48), gba = true) is SaveImport.Result.Rejected)
    }

    // ---- games made from a file -------------------------------------------------------------------

    @Test
    fun newFileGame_isStoredWithItsPlatform_andNeverCollidesWithACartridgeKey() {
        val e = lib.storeInNewFileGame(DisplayPlatform.GBC, "Pokemon Crystal (French)", imported())
        assertTrue(e.gameKey, e.gameKey.startsWith("DMG_POKEMON-CRYSTAL-FRENCH_FILE-"))
        val g = lib.readGame(e.gameKey)!!
        assertTrue(g.isFromFile)
        assertEquals(DisplayPlatform.GBC, g.displayPlatform)
        assertEquals("Pokemon Crystal (French)", g.label)
        assertNull(g.secondaryTitle)
        assertEquals("Imported from a file, not linked to a cartridge", g.identityText)
        // the same name twice makes two games
        clock += 1
        val again = lib.storeInNewFileGame(DisplayPlatform.GBC, "Pokemon Crystal (French)", imported())
        assertFalse(again.gameKey == e.gameKey)
        // a cartridge's game is not "from a file"
        lib.store(crystalCart, imported())
        assertFalse(lib.readGame(crystalCart.key)!!.isFromFile)
    }

    @Test
    fun newFileGame_gba_isAnAgbGame() {
        val e = lib.storeInNewFileGame(DisplayPlatform.GBA, "Sapphire", imported(ByteArray(0x20000)))
        val g = lib.readGame(e.gameKey)!!
        assertEquals("AGB", g.platform)
        assertEquals(DisplayPlatform.GBA, g.displayPlatform)
    }

    // ---- linking to the cartridge -----------------------------------------------------------------

    @Test
    fun link_movesEveryBackup_keepsTheData_andRemovesTheFileGame() {
        val footer = RtcSnapshot(1, 2, 3, 4, 0, 1_700_000_000L).toFooter()
        val first = lib.storeInNewFileGame(DisplayPlatform.GBC, "My Crystal", imported(rtc = footer))
        clock += 1000
        lib.store(lib.readGame(first.gameKey)!!, imported(save.copyOf().also { it[0] = 9 }))
        lib.setDisplayName(first.gameKey, "Nox's Crystal")

        val linked = lib.linkToCartridge(first.gameKey, crystalCart, 0x8000)

        assertEquals(crystalCart.key, linked.key)
        assertFalse(linked.isFromFile)
        assertEquals("Nox's Crystal", linked.displayName) // the name given to the file game is kept
        assertFalse(File(root, first.gameKey).exists())
        val moved = lib.list(crystalCart.key)
        assertEquals(2, moved.size)
        assertArrayEquals(save, lib.readSave(moved.last()))
        assertArrayEquals(footer, lib.readRtcFooter(moved.last()))
        assertTrue(moved.all { it.kind == BackupKind.IMPORTED && it.gameKey == crystalCart.key })
    }

    @Test
    fun link_renamesCollidingIds_andFollowsParentLinks_andRestoreRecords() {
        // The cartridge game already has a backup with the id the file game's first backup will get.
        lib.store(crystalCart, imported())
        val existingId = lib.list(crystalCart.key).single().id
        val parent = lib.storeInNewFileGame(DisplayPlatform.GBC, "Crystal", imported()) // same clock: same id
        assertEquals(existingId, parent.id)
        clock += 1000
        val child = lib.store(lib.readGame(parent.gameKey)!!, imported(kind = BackupKind.EDITED, parentId = parent.id))
        lib.addRestoreRecord(parent.gameKey, RestoreRecord(clock, parent.id, child.id, "verified", 1L, "ok"))

        lib.linkToCartridge(parent.gameKey, crystalCart, 0x8000)

        val all = lib.list(crystalCart.key)
        assertEquals(3, all.size)
        val renamed = all.single { it.id == "$existingId-2" }
        val movedChild = all.single { it.kind == BackupKind.EDITED }
        assertEquals(renamed.id, movedChild.parentId)
        val record = lib.restoreRecords(crystalCart.key).single()
        assertEquals(renamed.id, record.sourceBackupId)
        assertEquals(movedChild.id, record.safetyCopyId)
    }

    @Test
    fun link_isRefused_forAnotherSizeOrPlatform_andMovesNothing() {
        val e = lib.storeInNewFileGame(DisplayPlatform.GBC, "Crystal", imported())
        val size = assertThrows(IOException::class.java) { lib.linkToCartridge(e.gameKey, crystalCart, 0x2000) }
        assertTrue(size.message!!, size.message!!.contains("8192 bytes"))
        assertThrows(IOException::class.java) { lib.linkToCartridge(e.gameKey, EditorTestSaves.sapphire, 0x8000) }
        assertNotNull(lib.readGame(e.gameKey))
        assertEquals(1, lib.list(e.gameKey).size)
        assertTrue(lib.list(crystalCart.key).isEmpty())
        // a cartridge game cannot be "linked" again
        lib.store(crystalCart, imported())
        assertThrows(IOException::class.java) { lib.linkToCartridge(crystalCart.key, crystalCart, 0x8000) }
    }

    // ---- what the dialog pre-fills ------------------------------------------------------------------

    @Test
    fun guess_namesPokemonSaves_evenWithAClockFooter_andFallsBackToTheFileName() {
        val crystal = EditorTestSaves.crystal()
        val g = ImportGuesses.of("whatever.sav", crystal + ByteArray(48))
        assertEquals(DisplayPlatform.GBC, g.platform)
        assertEquals("Pokemon Crystal (English)", g.name)
        assertEquals(DisplayPlatform.GB, ImportGuesses.of("x.sav", EditorTestSaves.blue()).platform)
        val other = ImportGuesses.of("Link's_Awakening DX.sav", ByteArray(0x2000))
        assertNull(other.platform)
        assertEquals("Link's Awakening DX", other.name)
    }

    @Test
    fun fromFile_keysAreSafePathComponents() {
        val g = GameInfo.fromFile(DisplayPlatform.GB, "  ../../etc  ", "abc")
        assertTrue(g.key, Regex("[A-Za-z0-9_-]+").matches(g.key))
    }
}

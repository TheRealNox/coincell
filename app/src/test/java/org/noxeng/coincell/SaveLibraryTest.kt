package org.noxeng.coincell

import org.noxeng.coincell.cart.DmgHeader
import org.noxeng.coincell.cart.RtcSnapshot
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.NewBackup
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.save.SaveCheck
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SaveLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000L
    private val root by lazy { tmp.newFolder("saves") }
    private val lib by lazy { SaveLibrary(root) { clock } }

    private val game = GameInfo(
        "DMG", "POKEMON BLUE", "DMG_POKEMON-BLUE_56A4", "0x1B MBC5+RAM+BATTERY", 0x100000L, 0x56A4, 0x9C, null,
    )
    private val save = ByteArray(0x8000) { (it * 3 + 1).toByte() }
    private val footer = RtcSnapshot(21, 42, 13, 123, 0x41, 1_700_000_000L).toFooter()

    private fun backup(
        data: ByteArray = save,
        rtc: ByteArray? = null,
        suspect: String? = null,
    ) = NewBackup(data, rtc, if (rtc != null) true else null, rtc != null, "L15 (GBFlash)", "0.1.0", true, suspect)

    // ---- identity ----------------------------------------------------------------------------

    @Test
    fun key_isPlatformTitleChecksum() {
        assertEquals("DMG_POKEMON-BLUE_56A4", GameInfo.makeKey("DMG", "POKEMON BLUE", 0x56A4))
        assertEquals("DMG_ZELDA-LINK-S-AWAKENING_000F", GameInfo.makeKey("DMG", "ZELDA: LINK'S AWAKENING", 0xF))
    }

    @Test
    fun key_fromHeader_usesStoredGlobalChecksum() {
        val raw = dmgHeader(title = "POKEMON BLUE".toByteArray())
        raw[0x14E] = 0x56
        raw[0x14F] = 0xA4.toByte()
        val g = GameInfo.fromDmg(DmgHeader.parse(raw))
        assertEquals("DMG_POKEMON-BLUE_56A4", g.key)
        assertEquals("DMG", g.platform)
        assertEquals(0x56A4, g.globalChecksum)
    }

    @Test
    fun key_isAlwaysSafeAsPathComponent() {
        for (t in listOf("../../etc/passwd", "a/b\\c", "", "...", "C:\\x", "\u0000\u0001", "ÄÖÜ", "x".repeat(300))) {
            val k = GameInfo.makeKey("DMG", t, 1)
            assertTrue(k, Regex("[A-Za-z0-9_-]+").matches(k))
            assertTrue(k.length < 60)
        }
        assertEquals("DMG_UNTITLED_0001", GameInfo.makeKey("DMG", "", 1))
    }

    @Test
    fun differentChecksumOrPlatform_areDifferentGames() {
        assertNotEquals(GameInfo.makeKey("DMG", "TETRIS", 1), GameInfo.makeKey("DMG", "TETRIS", 2))
        assertNotEquals(GameInfo.makeKey("DMG", "TETRIS", 1), GameInfo.makeKey("AGB", "TETRIS", 1))
    }

    // ---- layout ------------------------------------------------------------------------------

    @Test
    fun store_writesDocumentedLayout() {
        val e = lib.store(game, backup(rtc = footer))
        val dir = File(File(root, game.key), e.id)
        assertEquals(setOf("save.sav", "rtc.bin", "backup.json"), dir.list()!!.toSet())
        assertArrayEquals(save, File(dir, "save.sav").readBytes())
        assertArrayEquals(footer, File(dir, "rtc.bin").readBytes())

        val b = JSONObject(File(dir, "backup.json").readText())
        assertEquals(0x8000, b.getInt("size"))
        assertEquals("%08X".format(SaveLibrary.crc32Of(save)), b.getString("crc32"))
        assertEquals(clock, b.getLong("timestamp"))
        assertTrue(b.getBoolean("rtcIncluded"))
        assertEquals(379, b.getJSONObject("rtc").getInt("days"))
        assertEquals(13, b.getJSONObject("rtc").getInt("hours"))
        assertTrue(b.getJSONObject("rtc").getBoolean("halted"))
        assertEquals("L15 (GBFlash)", b.getString("firmware"))
        assertEquals("0.1.0", b.getString("appVersion"))
        assertTrue(b.getBoolean("verified"))
        assertFalse(b.getBoolean("suspect"))
        assertEquals("", b.getString("note"))

        val g = JSONObject(File(File(root, game.key), "game.json").readText())
        assertEquals("DMG", g.getString("platform"))
        assertEquals("POKEMON BLUE", g.getString("title"))
        assertEquals("56A4", g.getString("globalChecksum"))
        assertEquals("9C", g.getString("headerChecksum"))
        assertEquals(0x100000L, g.getLong("romSize"))
        assertEquals(clock, g.getLong("firstSeen"))
    }

    @Test
    fun store_withoutRtc_hasNoRtcFile() {
        val e = lib.store(game, backup())
        assertFalse(File(File(File(root, game.key), e.id), "rtc.bin").exists())
        assertFalse(e.rtcIncluded)
        assertNull(lib.readRtcFooter(e))
        assertFalse(JSONObject(File(File(File(root, game.key), e.id), "backup.json").readText()).getBoolean("rtcIncluded"))
    }

    @Test
    fun suspectBackup_isStoredButFlagged() {
        val ff = ByteArray(0x8000) { 0xFF.toByte() }
        val e = lib.store(game, backup(ff, suspect = SaveCheck.suspectReason(ff)))
        assertTrue(e.suspect)
        assertTrue(e.suspectReason!!.contains("0xFF"))
        val json = JSONObject(File(File(File(root, game.key), e.id), "backup.json").readText())
        assertTrue(json.getBoolean("suspect"))
        assertEquals(1, lib.list(game.key).size)
        assertTrue(lib.list(game.key)[0].suspect)
    }

    @Test
    fun gameJson_keepsFirstSeen_updatesLastSeen() {
        lib.store(game, backup())
        clock += 5_000
        lib.store(game, backup())
        val g = JSONObject(File(File(root, game.key), "game.json").readText())
        assertEquals(1_700_000_000_000L, g.getLong("firstSeen"))
        assertEquals(1_700_000_005_000L, g.getLong("lastSeen"))
        assertEquals(1, lib.games().size)
    }

    // ---- listing, export, delete -------------------------------------------------------------

    @Test
    fun list_isNewestFirst_andIdsNeverCollide() {
        val a = lib.store(game, backup())
        val b = lib.store(game, backup()) // same clock value
        clock += 60_000
        val c = lib.store(game, backup(rtc = footer))
        assertEquals(3, setOf(a.id, b.id, c.id).size)
        val listed = lib.list(game.key)
        assertEquals(c.id, listed.first().id)
        assertEquals(setOf(a.id, b.id, c.id), listed.map { it.id }.toSet())
        assertTrue(lib.list("DMG_OTHER_0000").isEmpty())
    }

    @Test
    fun export_concatenatesSaveAndRtc_orSaveAlone() {
        val e = lib.store(game, backup(rtc = footer))
        assertArrayEquals(save + footer, lib.exportBytes(e, true))
        assertArrayEquals(save, lib.exportBytes(e, false))
        val plain = lib.store(game, backup())
        assertArrayEquals(save, lib.exportBytes(plain, true))
    }

    @Test
    fun delete_removesOnlyThatBackup() {
        val a = lib.store(game, backup())
        val b = lib.store(game, backup())
        lib.delete(a)
        assertEquals(listOf(b.id), lib.list(game.key).map { it.id })
        assertTrue(File(File(root, game.key), "game.json").exists())
    }

    @Test
    fun corruptSave_isDetectedOnRead() {
        val e = lib.store(game, backup())
        val f = File(File(File(root, game.key), e.id), "save.sav")
        f.writeBytes(save.copyOf().also { it[10] = (it[10] + 1).toByte() })
        assertThrows(IOException::class.java) { lib.readSave(e) }
        f.delete()
        assertTrue("entry without save.sav is not listed", lib.list(game.key).isEmpty())
    }

    // ---- atomicity ---------------------------------------------------------------------------

    @Test
    fun halfWrittenTempDirectory_isInvisibleAndCleanedUp() {
        lib.store(game, backup())
        val stale = File(File(root, game.key), ".tmp-20990101-000000")
        assertTrue(stale.mkdirs())
        File(stale, "save.sav").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(1, lib.list(game.key).size)
        lib.store(game, backup())
        assertFalse(stale.exists())
        assertEquals(2, lib.list(game.key).size)
    }

    @Test
    fun failedStore_leavesNoBackupDirectory() {
        lib.store(game, backup())
        assertThrows(IllegalArgumentException::class.java) { lib.store(game, backup(rtc = ByteArray(10))) }
        val dirs = File(root, game.key).list()!!.filter { it != "game.json" }
        assertEquals(1, dirs.size)
        assertTrue(dirs.none { it.startsWith(".") })
    }

    @Test
    fun entryWithBrokenJson_isSkipped() {
        val a = lib.store(game, backup())
        val b = lib.store(game, backup())
        File(File(File(root, game.key), a.id), "backup.json").writeText("{not json")
        assertEquals(listOf(b.id), lib.list(game.key).map { it.id })
    }

    // ---- helpers -----------------------------------------------------------------------------

    @Test
    fun suspectDetection_andFileNames() {
        assertNull(SaveCheck.suspectReason(byteArrayOf(0, 1)))
        assertTrue(SaveCheck.suspectReason(ByteArray(8) { 0xFF.toByte() })!!.contains("0xFF"))
        assertTrue(SaveCheck.suspectReason(ByteArray(8))!!.contains("0x00"))
        assertEquals("POKEMON BLUE.sav", SaveCheck.exportFileName("POKEMON BLUE"))
        assertEquals("A_B_C.sav", SaveCheck.exportFileName("A/B:C"))
        assertEquals("save.sav", SaveCheck.exportFileName(""))
    }
}

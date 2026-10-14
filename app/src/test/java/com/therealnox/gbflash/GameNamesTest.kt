package com.therealnox.gbflash

import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.GameSummary
import com.therealnox.gbflash.library.LibraryQuery
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.library.applyQuery
import com.therealnox.gbflash.restore.RestoreMatch
import com.therealnox.gbflash.restore.RestorePolicy
import com.therealnox.gbflash.ui.library.exportName
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Friendly game names: storage, label fallback, search, export name, and that restore matching ignores them. */
class GameNamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_700_000_000_000L
    private val root by lazy { tmp.newFolder("saves") }
    private val lib by lazy { SaveLibrary(root) { clock } }

    private val crystal = GameInfo("DMG", "PM_CRYSTAL", "DMG_PM-CRYSTAL_F2E2", "0x10 MBC3", 0x200000L, 0xF2E2, 0x26, "BYTF")
    private val sapphire = GameInfo(
        "AGB", "POKEMON SAPP", "AGB_POKEMON-SAPP_AXPF_54", "GBA", null, 0, 0x54, "AXPF",
        databaseName = "Pokemon - Version Saphir (France)",
    )
    private val save = ByteArray(0x8000) { (it * 3 + 1).toByte() }

    private fun backup() = NewBackup(save, null, null, false, "L15", "0.1.0", true, null)

    private fun gameJson(g: GameInfo) = File(File(root, g.key), SaveLibrary.GAME_JSON)

    // ---- label fallback ----------------------------------------------------------------------

    @Test
    fun label_order_customName_thenDatabaseName_thenTitle() {
        assertEquals("Pokémon Crystal", crystal.copy(displayName = "Pokémon Crystal").label)
        assertEquals("Mon Saphir", sapphire.copy(displayName = "Mon Saphir").label)
        assertEquals("Pokemon - Version Saphir (France)", sapphire.label)
        assertEquals("POKEMON SAPP", sapphire.copy(databaseName = null).label)
        assertEquals("PM_CRYSTAL", crystal.label)
    }

    @Test
    fun label_blankCustomNameCountsAsNotSet_andGbIgnoresDatabaseName() {
        assertEquals("PM_CRYSTAL", crystal.copy(displayName = "   ").label)
        assertEquals("PM_CRYSTAL", crystal.copy(databaseName = "Something else").label)
        assertEquals("(untitled)", crystal.copy(title = "").label)
    }

    @Test
    fun secondaryTitle_isTheHeaderTitleOnlyWhenItDiffers() {
        assertNull(crystal.secondaryTitle)
        assertEquals("PM_CRYSTAL", crystal.copy(displayName = "Pokémon Crystal").secondaryTitle)
        assertEquals("POKEMON SAPP", sapphire.secondaryTitle)
        assertNull(sapphire.copy(databaseName = null).secondaryTitle)
    }

    @Test
    fun identityText_gbaShowsGameCodeAndHeaderChecksum_gbKeepsGlobalChecksum() {
        assertEquals("Game code AXPF  ·  Header checksum 0x54", sapphire.identityText)
        assertEquals("Checksum 0xF2E2", crystal.identityText)
    }

    @Test
    fun fromAgb_carriesTheDatabaseName() {
        val g = GameInfo.fromAgb(AgbHeader.parse(agbHeader("POKEMON SAPP", "AXPF")), databaseName = "  Pokemon - Version Saphir (France) ")
        assertEquals("Pokemon - Version Saphir (France)", g.label)
        assertEquals("AGB_POKEMON-SAPP_AXPF_${"%02X".format(g.headerChecksum)}", g.key)
        assertEquals(GameInfo.fromAgb(AgbHeader.parse(agbHeader("POKEMON SAPP", "AXPF"))).key, g.key)
    }

    // ---- storage -----------------------------------------------------------------------------

    @Test
    fun rename_persistsAcrossInstances_andLeavesKeyAndFolderAlone() {
        lib.store(crystal, backup())
        val updated = lib.setDisplayName(crystal.key, "  Pokémon Crystal ")
        assertEquals("Pokémon Crystal", updated.displayName)
        val reopened = SaveLibrary(root) { clock }.readGame(crystal.key)!!
        assertEquals("Pokémon Crystal", reopened.displayName)
        assertEquals(crystal.key, reopened.key)
        assertEquals("PM_CRYSTAL", reopened.title)
        assertTrue(File(root, crystal.key).isDirectory)
        assertEquals(listOf(crystal.key), root.list()!!.toList())
        assertEquals(1, lib.list(crystal.key).size)
    }

    @Test
    fun rename_capsLengthAndTrims() {
        lib.store(crystal, backup())
        val updated = lib.setDisplayName(crystal.key, "  " + "x".repeat(100) + "  ")
        assertEquals(GameInfo.NAME_MAX_LENGTH, updated.displayName!!.length)
        assertEquals(GameInfo.NAME_MAX_LENGTH, lib.readGame(crystal.key)!!.displayName!!.length)
    }

    @Test
    fun rename_blankOrNullClearsToDefault() {
        lib.store(sapphire, backup())
        lib.setDisplayName(sapphire.key, "Mon Saphir")
        assertEquals("Mon Saphir", lib.readGame(sapphire.key)!!.label)
        assertNull(lib.setDisplayName(sapphire.key, "   ").displayName)
        assertFalse(JSONObject(gameJson(sapphire).readText()).has("displayName"))
        assertEquals("Pokemon - Version Saphir (France)", lib.readGame(sapphire.key)!!.label)
        lib.setDisplayName(sapphire.key, "Again")
        assertNull(lib.setDisplayName(sapphire.key, null).displayName)
    }

    @Test
    fun rename_isAtomic_noTempLeftAndOtherFieldsKept() {
        lib.store(crystal, backup())
        val before = JSONObject(gameJson(crystal).readText())
        lib.setDisplayName(crystal.key, "Pokémon Crystal")
        val after = JSONObject(gameJson(crystal).readText())
        before.keys().forEach { k -> if (k != "displayName") assertEquals(k, before.get(k), after.get(k)) }
        assertEquals(before.length() + 1, after.length())
        assertEquals(emptyList<String>(), File(root, crystal.key).list()!!.filter { it.startsWith(".tmp-") })
    }

    @Test
    fun rename_failedWriteLeavesTheOldGameJsonIntact() {
        lib.store(crystal, backup())
        lib.setDisplayName(crystal.key, "First")
        val original = gameJson(crystal).readText()
        // A directory where the temp file goes makes the write fail before the rename.
        assertTrue(File(File(root, crystal.key), ".tmp-${SaveLibrary.GAME_JSON}").mkdirs())
        assertThrows(Exception::class.java) { lib.setDisplayName(crystal.key, "Second") }
        assertEquals(original, gameJson(crystal).readText())
        assertEquals("First", lib.readGame(crystal.key)!!.displayName)
    }

    @Test
    fun rename_unsafeKeyIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { lib.setDisplayName("../evil", "x") }
    }

    @Test
    fun oldGameJsonWithoutTheFields_stillReads() {
        lib.store(crystal, backup())
        val j = JSONObject(gameJson(crystal).readText())
        j.remove("displayName")
        j.remove("databaseName")
        gameJson(crystal).writeText(j.toString(2))
        val g = lib.readGame(crystal.key)!!
        assertNull(g.displayName)
        assertNull(g.databaseName)
        assertEquals("PM_CRYSTAL", g.label)
        assertEquals(1, lib.games().size)
    }

    @Test
    fun emptyDisplayNameInJson_meansNotSet() {
        lib.store(crystal, backup())
        gameJson(crystal).writeText(JSONObject(gameJson(crystal).readText()).put("displayName", "").toString(2))
        assertNull(lib.readGame(crystal.key)!!.displayName)
    }

    @Test
    fun newBackup_keepsTheCustomName_andStoresTheDatabaseName() {
        lib.store(sapphire, backup())
        assertEquals("Pokemon - Version Saphir (France)", lib.readGame(sapphire.key)!!.databaseName)
        lib.setDisplayName(sapphire.key, "Mon Saphir")
        clock += 60_000
        // The next backup comes from the cartridge header: no custom name, and no database name known this time.
        lib.store(sapphire.copy(databaseName = null), backup())
        val g = lib.readGame(sapphire.key)!!
        assertEquals("Mon Saphir", g.displayName)
        assertEquals("Pokemon - Version Saphir (France)", g.databaseName)
        assertEquals(2, lib.list(sapphire.key).size)
    }

    // ---- search ------------------------------------------------------------------------------

    private fun summary(g: GameInfo) = GameSummary(g, listOf(entry(g)))

    private fun entry(g: GameInfo) = BackupEntry(
        gameKey = g.key, id = "20250101-120000", timestampMillis = 1_700_000_000_000L, size = 0x8000, crc32 = 1,
        rtc = null, rtcProbePresent = null, rtcDeclared = false, firmware = "", appVersion = "",
        verified = true, suspectReason = null, note = "",
    )

    private fun search(text: String) =
        listOf(summary(crystal.copy(displayName = "Pokémon Crystal")), summary(sapphire)).applyQuery(LibraryQuery(search = text)).map { it.key }

    @Test
    fun search_matchesNameDatabaseNameTitleAndCode() {
        assertEquals(listOf(crystal.key), search("pokémon crys"))
        assertEquals(listOf(sapphire.key), search("saphir"))
        assertEquals(listOf(crystal.key), search("PM_CRY"))
        assertEquals(listOf(sapphire.key), search("sapp"))
        assertEquals(listOf(sapphire.key), search("axpf"))
        assertEquals(listOf(crystal.key), search("bytf"))
        assertEquals(emptyList<String>(), search("zelda"))
        assertEquals(2, search("").size)
    }

    // ---- export name -------------------------------------------------------------------------

    @Test
    fun exportName_usesTheFriendlyName() {
        val stamp = exportName(crystal.copy(displayName = "Pokemon Crystal").label, clock)
        assertTrue(stamp, stamp.startsWith("Pokemon Crystal_") && stamp.endsWith(".sav"))
        assertTrue(exportName(crystal.label, clock).startsWith("PM_CRYSTAL_"))
        val bad = exportName(crystal.copy(displayName = "Zelda: Link's/Awakening?").label, clock)
        assertTrue(bad, bad.startsWith("Zelda_ Link's_Awakening__"))
        assertTrue(exportName(sapphire.label, clock).startsWith("Pokemon - Version Saphir (France)_"))
    }

    // ---- restore matching ignores names ------------------------------------------------------

    private fun dmgHeaderOf(title: String, global: Int): DmgHeader {
        val raw = dmgHeader(title = title.toByteArray(), cartType = 0x1B, ramCode = 3)
        raw[0x14E] = (global shr 8).toByte()
        raw[0x14F] = global.toByte()
        return DmgHeader.parse(raw)
    }

    @Test
    fun restorePolicy_outcomeIsTheSameAfterRename() {
        val cases = listOf(
            dmgHeaderOf("POKEMON BLUE", 0x56A4) to dmgHeaderOf("POKEMON BLUE", 0x56A4),
            dmgHeaderOf("POKEMON BLUE", 0x1111) to dmgHeaderOf("POKEMON BLUE", 0x2222),
            dmgHeaderOf("POKEMON BLUE", 0x56A4) to dmgHeaderOf("POKEMON RED", 0x1234),
        )
        for ((source, target) in cases) {
            val plain = GameInfo.fromDmg(source)
            val renamed = plain.copy(displayName = "POKEMON RED", databaseName = "POKEMON RED")
            val a = RestorePolicy.review(entry(plain), plain, target)
            val b = RestorePolicy.review(entry(plain), renamed, target)
            assertEquals(a.match, b.match)
            assertEquals(a.canProceed, b.canProceed)
            assertEquals(a.cartGame.key, b.cartGame.key)
            assertEquals(RestorePolicy.mayStart(a, ""), RestorePolicy.mayStart(b, ""))
        }
    }

    @Test
    fun restorePolicy_aRenamedGameStillNeedsTheHeaderTitleTyped() {
        val source = dmgHeaderOf("POKEMON BLUE", 0x56A4)
        val target = dmgHeaderOf("POKEMON RED", 0x1234)
        // Named like the cartridge's header title: this must not make the two look like the same game.
        val renamed = GameInfo.fromDmg(source).copy(displayName = "POKEMON RED")
        val r = RestorePolicy.review(entry(renamed), renamed, target)
        val m = r.match as RestoreMatch.DifferentTitle
        assertEquals("POKEMON RED", m.requiredTitle)
        assertFalse(RestorePolicy.mayStart(r, ""))
        assertTrue(RestorePolicy.mayStart(r, "pokemon red"))
        assertEquals("POKEMON BLUE", m.sourceTitle)
    }
}

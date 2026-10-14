package com.therealnox.gbflash

import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.SaveInfo
import com.therealnox.gbflash.cart.analyzeSaveWindow
import com.therealnox.gbflash.cart.findRepeatingSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderParsingTest {
    // ---- DMG ---------------------------------------------------------------------------------

    @Test
    fun dmg_mbc5Battery32K() {
        val h = DmgHeader.parse(dmgHeader(title = "POKEMON RED".toByteArray(), cartType = 0x1B, romCode = 5, ramCode = 3))
        assertEquals("POKEMON RED", h.title)
        assertEquals("MBC5+RAM+BATTERY", h.mapperName)
        assertEquals(0x1B, h.mapperRaw)
        assertEquals(1024L * 1024, h.romSizeBytes)
        assertTrue(h.logoValid)
        assertTrue(h.headerChecksumValid)
        assertFalse(h.likelyNoCart)
        assertTrue(h.supportedMapper)
        assertEquals(SaveInfo.Status.FROM_HEADER, h.save.status)
        assertEquals(3, h.save.typeId)
        assertEquals(0x8000, h.save.sizeBytes)
    }

    @Test
    fun dmg_ramSizeTable() {
        val expected = mapOf(0 to 0, 1 to 0x800, 2 to 0x2000, 3 to 0x8000, 4 to 0x20000, 5 to 0x10000)
        for ((code, size) in expected) {
            val h = DmgHeader.parse(dmgHeader(ramCode = code))
            assertEquals("code $code", size, h.save.sizeBytes)
        }
        assertEquals(SaveInfo.Status.NONE, DmgHeader.parse(dmgHeader(ramCode = 0)).save.status)
        val unknown = DmgHeader.parse(dmgHeader(ramCode = 7))
        assertEquals(SaveInfo.Status.UNKNOWN, unknown.save.status)
        assertNull(unknown.save.sizeBytes)
    }

    @Test
    fun dmg_mbc2ForcesBuiltInRam() {
        val h = DmgHeader.parse(dmgHeader(cartType = 0x06, ramCode = 0))
        assertEquals(0x100, h.save.typeId)
        assertEquals(0x200, h.save.sizeBytes)
        val noBattery = DmgHeader.parse(dmgHeader(cartType = 0x05, ramCode = 0))
        assertEquals(0, noBattery.save.sizeBytes)
    }

    @Test
    fun dmg_mbc30IsSynthetic() {
        val h = DmgHeader.parse(dmgHeader(cartType = 0x10, ramCode = 5))
        assertEquals(0x110, h.mapperRaw)
        assertEquals(0x10000, h.save.sizeBytes)
        assertTrue(h.hasRtcDeclared)
        assertEquals(0x10, DmgHeader.parse(dmgHeader(cartType = 0x10, ramCode = 3)).mapperRaw)
    }

    @Test
    fun dmg_specialMapperSaveOverrides() {
        assertEquals(0x103, DmgHeader.parse(dmgHeader(cartType = 0xFD, ramCode = 0)).save.typeId)
        assertEquals(0x104, DmgHeader.parse(dmgHeader(cartType = 0x20, ramCode = 0)).save.typeId)
        val kirby = DmgHeader.parse(dmgHeader(title = "KORO2 KIRBY".toByteArray(), cartType = 0x22, ramCode = 0))
        assertEquals(0x101, kirby.save.typeId)
        assertEquals(0x100, kirby.save.sizeBytes)
        val cm = DmgHeader.parse(dmgHeader(title = "CMASTER".toByteArray(), cartType = 0x22, ramCode = 0))
        assertEquals(0x102, cm.save.typeId)
    }

    @Test
    fun dmg_cgbTitleWithGameCode() {
        val title = "POKEMON GOLD".toByteArray().copyOf(11) + "AAUE".toByteArray()
        val h = DmgHeader.parse(dmgHeader(title = title, cgbFlag = 0xC0))
        assertEquals("AAUE", h.gameCode)
        assertEquals("POKEMON GOL", h.title)
        assertEquals(0xC0, h.cgbFlag)
    }

    @Test
    fun dmg_titleCleanup() {
        val h = DmgHeader.parse(dmgHeader(title = "A  B__C".toByteArray() + bytes(0, 0, 0)))
        assertEquals("A B_C", h.title)
    }

    @Test
    fun dmg_badLogoAndChecksum() {
        val bad = dmgHeader().also { it[0x110] = 0x55 }
        val h = DmgHeader.parse(bad)
        assertFalse(h.logoValid)
        val badSum = dmgHeader().also { it[0x14D] = (it[0x14D] + 1).toByte() }
        assertFalse(DmgHeader.parse(badSum).headerChecksumValid)
    }

    @Test
    fun dmg_noCartridgePatterns() {
        assertTrue(DmgHeader.parse(ByteArray(0x180) { 0xFF.toByte() }).likelyNoCart)
        assertTrue(DmgHeader.parse(ByteArray(0x180)).likelyNoCart)
    }

    @Test
    fun dmg_globalChecksumIsSumWithChecksumBytesZeroed() {
        val raw = dmgHeader()
        val h = DmgHeader.parse(raw)
        var sum = 0
        raw.forEachIndexed { i, b -> if (i != 0x14E && i != 0x14F) sum += b.toInt() and 0xFF }
        assertEquals(sum and 0xFFFF, h.globalChecksumComputed)
    }

    // ---- AGB ---------------------------------------------------------------------------------

    @Test
    fun agb_basicFields() {
        val h = AgbHeader.parse(agbHeader(title = "POKEMON EMER", code = "BPEE", maker = "01"))
        assertEquals("POKEMON EMER", h.title)
        assertEquals("BPEE", h.gameCode)
        assertEquals("01", h.makerCode)
        assertTrue(h.logoValid)
        assertTrue(h.fixed96Valid)
        assertTrue(h.headerChecksumValid)
        assertFalse(h.likelyNoCart)
        assertEquals(40, h.dbKey.length)
        assertEquals(SaveInfo.Status.UNRESOLVED, h.save.status)
    }

    @Test
    fun agb_mCodeUsesShortHashRange() {
        val raw = agbHeader(code = "MABC")
        val a = AgbHeader.parse(raw)
        raw[0x150] = 0x55 // outside 0..0x100, must not change the key
        assertEquals(a.dbKey, AgbHeader.parse(raw).dbKey)
        val other = agbHeader(code = "ABCD")
        val k = AgbHeader.parse(other).dbKey
        other[0x150] = 0x55
        assertTrue(k != AgbHeader.parse(other).dbKey)
    }

    @Test
    fun agb_badLogoAndChecksum() {
        val raw = agbHeader().also { it[0x20] = 0 }
        assertFalse(AgbHeader.parse(raw).logoValid)
        val sum = agbHeader().also { it[0xBD] = (it[0xBD] + 1).toByte() }
        assertFalse(AgbHeader.parse(sum).headerChecksumValid)
    }

    @Test
    fun agb_noCartOpenBus() {
        assertTrue(AgbHeader.parse(ByteArray(0x180) { 0xFF.toByte() }).likelyNoCart)
    }

    @Test
    fun agb_patchMarkerDecoding() {
        fun m(flags: Int) = AgbHeader.saveTypeFromMarker(agbHeader(marker = flags))
        assertEquals(3 to 32768, m(0b0_0_01_011)) // SRAM 32K
        assertEquals(7 to 65536, m(0b0_0_01_100)) // SRAM 64K
        assertEquals(8 to 131072, m(0b0_0_01_101)) // SRAM 128K
        assertEquals(9 to 512, m(0b0_01_01_001)) // batteryless SRAM
        assertEquals(1 to 512, m(0b0_0_10_001)) // EEPROM 4K
        assertEquals(2 to 8192, m(0b0_0_10_010)) // EEPROM 64K
        assertEquals(1 to 0, m(0b0_0_10_000)) // EEPROM unresolved
        assertEquals(4 to 65536, m(0b0_0_11_100)) // Flash 64K
        assertEquals(5 to 131072, m(0b0_0_11_101)) // Flash 128K
        assertEquals(0 to 0, m(0)) // none
        assertNull(m(0b0_0_00_001)) // medium none but size set
        assertNull(m(0b0_0_01_110)) // invalid size code
        assertNull(AgbHeader.saveTypeFromMarker(agbHeader())) // no marker
        val h = AgbHeader.parse(agbHeader(marker = 0b0_0_11_101))
        assertEquals(SaveInfo.Status.FROM_HEADER, h.save.status)
        assertEquals(131072, h.save.sizeBytes)
        assertEquals("1M FLASH", h.save.label)
    }

    // ---- save window analysis ----------------------------------------------------------------

    @Test
    fun findRepeatingSize_matchesPython() {
        assertEquals(0x10, findRepeatingSize(ByteArray(0x10000)))
        val half = ByteArray(0x10000) { (it % 0x8000 * 31 / 7).toByte() }
        assertEquals(0x8000, findRepeatingSize(half))
        val none = ByteArray(0x10000) { (it * 31 / 7 + (it shr 8)).toByte() }
        assertEquals(0x10000, findRepeatingSize(none))
    }

    @Test
    fun saveWindow_hints() {
        assertNull(analyzeSaveWindow(ByteArray(0x10000) { 0xFF.toByte() }).suggestedType)
        val sram32 = ByteArray(0x10000) { ((it % 0x8000) * 13 + (it % 0x8000 shr 8)).toByte() }
        assertEquals(3, analyzeSaveWindow(sram32).suggestedType)
        val big = ByteArray(0x10000) { ((it * 13) + (it shr 8)).toByte() }
        assertNull(analyzeSaveWindow(big).suggestedType)
        assertTrue(analyzeSaveWindow(big).summary.contains("Flash"))
    }
}

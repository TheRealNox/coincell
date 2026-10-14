package com.therealnox.gbflash

import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.RtcSnapshot
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.restore.RestoreMatch
import com.therealnox.gbflash.restore.RestorePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The matching rules between a library backup and the cartridge header just read. */
class RestorePolicyTest {
    private fun header(
        title: String = "POKEMON BLUE",
        cartType: Int = 0x1B,
        ramCode: Int = 3,
        global: Int = 0x56A4,
    ): DmgHeader {
        val raw = dmgHeader(title = title.toByteArray(), cartType = cartType, ramCode = ramCode)
        raw[0x14E] = (global shr 8).toByte()
        raw[0x14F] = global.toByte()
        return DmgHeader.parse(raw)
    }

    private fun entry(game: GameInfo, size: Int = 0x8000, rtc: RtcSnapshot? = null) = BackupEntry(
        gameKey = game.key, id = "20250101-120000", timestampMillis = 1_700_000_000_000L, size = size, crc32 = 0x1234,
        rtc = rtc, rtcProbePresent = null, rtcDeclared = rtc != null, firmware = "", appVersion = "",
        verified = true, suspectReason = null, note = "",
    )

    private fun review(source: DmgHeader, target: DmgHeader, size: Int = 0x8000, game: GameInfo? = GameInfo.fromDmg(source)) =
        RestorePolicy.review(entry(GameInfo.fromDmg(source), size), game, target)

    @Test
    fun sameGameKey_isAllowed() {
        val r = review(header(), header())
        assertEquals(RestoreMatch.SameGame, r.match)
        assertTrue(r.canProceed)
        assertTrue(RestorePolicy.mayStart(r, ""))
    }

    @Test
    fun sameTitleSameSizeOtherChecksum_isAllowedWithNotice_noTyping() {
        val r = review(header(global = 0x1111), header(global = 0x2222))
        assertEquals(RestoreMatch.DifferentCartridge(0x1111, 0x2222), r.match)
        assertTrue(r.canProceed)
        assertTrue("no typing needed", RestorePolicy.mayStart(r, ""))
    }

    @Test
    fun titleComparison_usesTheCleanedTitle() {
        val r = review(header(title = "Pokemon  Blue", global = 0x1111), header(title = "POKEMON-BLUE", global = 0x2222))
        assertTrue(r.match is RestoreMatch.DifferentCartridge)
    }

    @Test
    fun differentTitle_needsTheCartridgeTitleTyped() {
        val r = review(header(title = "POKEMON RED", global = 0x9E00), header(title = "POKEMON BLUE"))
        val m = r.match as RestoreMatch.DifferentTitle
        assertEquals("POKEMON BLUE", m.requiredTitle)
        assertEquals("POKEMON RED", m.sourceTitle)
        assertTrue(r.canProceed)
        assertFalse(RestorePolicy.mayStart(r, ""))
        assertFalse("the backup's title is the wrong one", RestorePolicy.mayStart(r, "POKEMON RED"))
        assertFalse(RestorePolicy.mayStart(r, "POKEMON BLU"))
        assertFalse(RestorePolicy.mayStart(r, "POKEMON  BLUE"))
        assertTrue(RestorePolicy.mayStart(r, "POKEMON BLUE"))
    }

    @Test
    fun typedTitle_isCaseInsensitive_andIgnoresSurroundingBlanks() {
        val r = review(header(title = "POKEMON RED", global = 0x9E00), header(title = "POKEMON BLUE"))
        assertTrue(RestorePolicy.mayStart(r, "pokemon blue"))
        assertTrue(RestorePolicy.mayStart(r, "  Pokemon Blue "))
    }

    @Test
    fun emptyCartridgeTitle_asksForUntitled() {
        val target = header(title = "")
        val r = review(header(title = "POKEMON RED", global = 0x9E00), target)
        assertEquals("UNTITLED", (r.match as RestoreMatch.DifferentTitle).requiredTitle)
        assertTrue(RestorePolicy.mayStart(r, "untitled"))
    }

    @Test
    fun differentPlatform_isBlocked_withReason() {
        val src = GameInfo("AGB", "POKEMON BLUE", "AGB_POKEMON-BLUE_ABCD", "", null, 0, 0, "ABCD")
        val r = RestorePolicy.review(entry(src), src, header())
        val m = r.match as RestoreMatch.Blocked
        assertTrue(m.reason, m.reason.contains("GBA backup cannot be written to a GB / GBC cartridge"))
        assertFalse(r.canProceed)
        assertFalse(RestorePolicy.mayStart(r, "POKEMON BLUE"))
    }

    @Test
    fun saveSizeDiffersFromCartridgeHeader_isBlocked_evenForTheSameGame() {
        val r = review(header(), header(), size = 0x2000)
        val m = r.match as RestoreMatch.Blocked
        assertTrue(m.reason, m.reason.contains("8 KiB") && m.reason.contains("32 KiB"))
        assertFalse(RestorePolicy.mayStart(r, "POKEMON BLUE"))
    }

    @Test
    fun saveSizeMismatch_isCheckedBeforeTheTitle() {
        val r = review(header(title = "POKEMON RED", global = 0x9E00), header(), size = 0x2000)
        assertTrue(r.match is RestoreMatch.Blocked)
    }

    @Test
    fun unsupportedMapper_isBlocked() {
        val mbc7 = header(cartType = 0x22, ramCode = 3)
        val r = RestorePolicy.review(entry(GameInfo.fromDmg(mbc7)), GameInfo.fromDmg(mbc7), mbc7)
        val m = r.match as RestoreMatch.Blocked
        assertTrue(m.reason, m.reason.contains("not supported yet"))
        assertFalse(r.canProceed)
    }

    @Test
    fun cartridgeWithoutSaveRam_isBlocked() {
        val none = header(cartType = 0x00, ramCode = 0)
        val r = RestorePolicy.review(entry(GameInfo.fromDmg(none)), GameInfo.fromDmg(none), none)
        assertTrue(r.match is RestoreMatch.Blocked)
    }

    @Test
    fun missingGameJson_isBlocked() {
        val r = review(header(), header(), game = null)
        assertTrue(r.match is RestoreMatch.Blocked)
    }

    @Test
    fun allSupportedMappers_canBeReviewed() {
        for ((type, ram, size) in listOf(Triple(0x00, 2, 0x2000), Triple(0x03, 3, 0x8000), Triple(0x06, 0, 0x200), Triple(0x13, 3, 0x8000), Triple(0x10, 5, 0x10000), Triple(0x1B, 4, 0x20000))) {
            val h = header(cartType = type, ramCode = ram)
            val r = RestorePolicy.review(entry(GameInfo.fromDmg(h), size), GameInfo.fromDmg(h), h)
            assertEquals("type %02X".format(type), RestoreMatch.SameGame, r.match)
        }
    }

    // ---- clock option ------------------------------------------------------------------------

    private val goodRtc = RtcSnapshot(1, 2, 3, 4, 0, 1_700_000_000L)

    private fun clockReview(rtc: RtcSnapshot?, cartType: Int) = header(cartType = cartType).let {
        RestorePolicy.review(entry(GameInfo.fromDmg(it), rtc = rtc), GameInfo.fromDmg(it), it)
    }

    @Test
    fun clockOption_offeredOnlyWithBackupClockAndCartridgeClock() {
        assertTrue(clockReview(goodRtc, 0x10).clockOptionOffered)
        assertFalse("backup has no rtc.bin", clockReview(null, 0x10).clockOptionOffered)
        assertFalse("cartridge has no timer", clockReview(goodRtc, 0x13).clockOptionOffered)
        assertFalse("MBC5", clockReview(goodRtc, 0x1B).clockOptionOffered)
        assertFalse("implausible clock values", clockReview(RtcSnapshot(99, 99, 99, 0, 0, 1), 0x10).clockOptionOffered)
        assertTrue(clockReview(null, 0x10).clockNote.contains("will not be changed"))
    }

    @Test
    fun clockOption_notOfferedForBlockedRestore() {
        val h = header(cartType = 0x10)
        val r = RestorePolicy.review(entry(GameInfo.fromDmg(h), size = 0x2000, rtc = goodRtc), GameInfo.fromDmg(h), h)
        assertFalse(r.clockOptionOffered)
    }
}

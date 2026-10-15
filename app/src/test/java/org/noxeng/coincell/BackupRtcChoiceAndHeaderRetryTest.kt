package org.noxeng.coincell

import org.noxeng.coincell.cart.DmgHeader
import org.noxeng.coincell.protocol.CartPlatform
import org.noxeng.coincell.protocol.DeviceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The RTC question of a backup (FlashGBX wording flow) and the header read retry after a power cycle. */
class BackupRtcChoiceAndHeaderRetryTest {
    // ---- backup: RTC yes / no ----------------------------------------------------------------

    private fun clockRig(cartType: Int) = RestoreRig(cartType, 3, FakeMapper.MBC3, 0x8000, hasRtc = true).also {
        intArrayOf(21, 42, 13, 123, 0).copyInto(it.cart.liveRtc)
    }

    private fun backup(r: RestoreRig, includeRtc: Boolean = true, ask: (() -> Boolean)? = null) =
        runBlocking { r.session.backupDmgSave(r.plan, { false }, { _, _ -> }, includeRtc = includeRtc, askRtc = ask) }

    @Test
    fun declaredTimer_yes_storesTheRtc() {
        val r = clockRig(0x10)
        val b = backup(r, includeRtc = true)
        assertNotNull(b.rtc)
        assertArrayEquals(r.sramBefore, b.data)
    }

    @Test
    fun declaredTimer_no_readsNoClockAtAll() {
        val r = clockRig(0x10)
        val b = backup(r, includeRtc = false)
        assertNull(b.rtc)
        assertNull("no probe either", b.rtcProbePresent)
        assertTrue(b.rtcDeclared)
        assertArrayEquals("the save itself is unaffected", r.sramBefore, b.data)
        assertEquals("no clock toggles", 0, r.fw.clockToggles)
        assertTrue("no latch, no RTC select", r.cart.registerWrites.none { it.first == 0x6000 || (it.first == 0x4000 && it.second >= 8) })
        assertEquals(0, r.fw.b3Count)
    }

    @Test
    fun undeclaredTimer_questionComesAfterTheProbe_butBeforeTheRamRead() {
        val r = clockRig(0x13)
        var readsWhenAsked = -1
        val b = backup(r, ask = { readsWhenAsked = r.fw.ramReadCount; true })
        assertEquals("asked before any RAM chunk was read, after the probe page reads", 5, readsWhenAsked)
        assertNotNull(b.rtc)
        assertEquals(true, b.rtcProbePresent)
        assertArrayEquals(r.sramBefore, b.data)
    }

    @Test
    fun undeclaredTimer_no_dropsTheClock() {
        val yes = clockRig(0x13)
        backup(yes, ask = { true })
        val r = clockRig(0x13)
        val b = backup(r, ask = { false })
        assertNull(b.rtc)
        assertArrayEquals(r.sramBefore, b.data)
        assertTrue("the clock was not read after the probe", r.fw.clockToggles < yes.fw.clockToggles)
        assertFalse(r.cart.ramEnabled)
    }

    @Test
    fun undeclaredTimer_noClockPresent_neverAsks() {
        val r = RestoreRig(0x13, 3, FakeMapper.MBC3, 0x8000, hasRtc = false)
        var asked = false
        val b = backup(r, ask = { asked = true; true })
        assertFalse(asked)
        assertNull(b.rtc)
    }

    // ---- header retry ------------------------------------------------------------------------

    private class Setup(cartType: Int = 0x1B) {
        val logs = mutableListOf<String>()
        val link = FakeSerialLink()
        val fw = FakeFirmware(link)
        val cart = FakeDmgCart(FakeMapper.MBC5, dmgHeader(title = "CRYSTAL".toByteArray(), cartType = cartType).copyOf(0x10000), testSave(0x8000, 1))
        val session: DeviceSession

        init {
            fw.cart = cart
            session = runBlocking { DeviceSession.open(link, log = logs::add, clock = link, io = Dispatchers.Unconfined) }
        }

        fun read(): DmgHeader = runBlocking { session.readHeader(CartPlatform.GB_GBC) } as DmgHeader
    }

    private fun garbage(n: Int) = ByteArray(n) { (it * 37 + 5).toByte() }

    @Test
    fun garbageFirstRead_powerCyclesAndRetriesOnce() {
        val s = Setup()
        s.fw.romReadFilter = { d -> if (s.fw.powerOnCount == 1) garbage(d.size) else d }
        val h = s.read()
        assertTrue(h.logoValid && h.headerChecksumValid)
        assertEquals("CRYSTAL", h.title)
        assertEquals("one initial power-on and one retry", 2, s.fw.powerOnCount)
        assertTrue(s.logs.contains("Header invalid, power-cycling cartridge and retrying"))
        val ops = s.link.opcodes()
        val f3 = ops.lastIndexOf(0xF3)
        assertTrue("an F3 (power off) happened during the read, before the second F2", f3 > ops.indexOf(0xF2) && f3 < ops.lastIndexOf(0xF2))
        assertTrue(s.fw.powered)
        assertEquals(1, s.logs.count { it.startsWith("Header invalid") })
    }

    @Test
    fun invalidHeaderChecksum_alsoTriggersTheRetry() {
        val s = Setup()
        s.cart.rom[0x14D] = (s.cart.rom[0x14D] + 1).toByte() // wrong header checksum until the cartridge is power-cycled
        var fixed = false
        s.fw.romReadFilter = { d ->
            if (s.fw.powerOnCount >= 2 && !fixed) {
                fixed = true
                s.cart.rom[0x14D] = (s.cart.rom[0x14D] - 1).toByte()
            }
            d
        }
        val h = s.read()
        assertEquals(2, s.fw.powerOnCount)
        assertTrue(s.logs.contains("Header invalid, power-cycling cartridge and retrying"))
        assertEquals("CRYSTAL", h.title)
    }

    @Test
    fun validFirstRead_neverPowerCycles() {
        val s = Setup()
        s.read()
        assertEquals(1, s.fw.powerOnCount)
        assertFalse(s.logs.any { it.startsWith("Header invalid") })
    }

    @Test
    fun persistentGarbage_isRetriedExactlyOnce_thenReportedInvalid() {
        val s = Setup()
        s.fw.romReadFilter = { d -> garbage(d.size) }
        val h = s.read()
        assertFalse(h.logoValid)
        assertEquals(2, s.fw.powerOnCount)
        assertEquals(1, s.logs.count { it.startsWith("Header invalid") })
    }

    @Test
    fun retry_keepsTheVoltageRules() {
        val s = Setup()
        s.fw.romReadFilter = { d -> if (s.fw.powerOnCount == 1) garbage(d.size) else d }
        s.read()
        assertFalse(0xA4 in s.link.opcodes())
        assertFalse(s.fw.voltageChangedWhilePowered)
        assertTrue(s.fw.voltageAtPowerOn.all { it == "5V" })
        assertTrue(s.fw.violations.isEmpty())
        assertEquals(0, s.fw.b3Count)
    }

    @Test
    fun powerOffCart_thenReadAgain_powersOnAgain() {
        val s = Setup()
        s.read()
        runBlocking { s.session.powerOffCart() }
        assertFalse(s.fw.powered)
        val h = s.read()
        assertTrue(h.logoValid)
        assertTrue(s.fw.powered)
        assertEquals(2, s.fw.powerOnCount)
    }
}

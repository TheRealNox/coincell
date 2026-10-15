package org.noxeng.coincell

import org.noxeng.coincell.cart.AgbHeader
import org.noxeng.coincell.cart.DmgHeader
import org.noxeng.coincell.protocol.CartPlatform
import org.noxeng.coincell.protocol.DeviceSession
import org.noxeng.coincell.protocol.IncompatibleDeviceException
import org.noxeng.coincell.protocol.ProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAndVoltageTest {
    private val link = FakeSerialLink()
    private val fw = FakeFirmware(link)

    private fun open() = runBlocking { DeviceSession.open(link, clock = link, io = Dispatchers.Unconfined) }

    private val voltageOpcodes = setOf(0xA4, 0xA5)

    // ---- voltage safety (hard rule) ----------------------------------------------------------

    @Test
    fun platformDeterminesVoltage() {
        assertEquals(0xA5, CartPlatform.GB_GBC.voltageOpcode)
        assertEquals(0xA3, CartPlatform.GB_GBC.modeOpcode)
        assertEquals(0xA4, CartPlatform.GBA.voltageOpcode)
        assertEquals(0xA2, CartPlatform.GBA.modeOpcode)
        assertNotEquals(CartPlatform.GBA.voltageOpcode, 0xA5)
    }

    @Test
    fun gbaSession_neverSends5VOrDmgMode_andPowersOnAt3v3() {
        fw.rom = agbHeader()
        val s = open()
        runBlocking {
            s.readHeader(CartPlatform.GBA)
            s.readHeader(CartPlatform.GBA)
            s.probeAgbSaveReadOnly()
            s.close()
        }
        // Every host write is one command whose first byte is the opcode.
        val ops = link.opcodes()
        assertFalse("A5 (5V) sent in GBA flow", 0xA5 in ops)
        assertFalse("A3 (DMG mode) sent in GBA flow", 0xA3 in ops)
        assertTrue(0xA4 in ops && 0xA2 in ops)
        assertFalse(fw.voltageChangedWhilePowered)
        assertTrue(fw.voltageAtPowerOn.isNotEmpty())
        assertTrue(fw.voltageAtPowerOn.all { it == "3.3V" })
    }

    @Test
    fun gbSession_uses5VAndNeverGbaOpcodes() {
        fw.rom = dmgHeader()
        val s = open()
        runBlocking { s.readHeader(CartPlatform.GB_GBC) }
        val ops = link.opcodes()
        assertFalse(0xA4 in ops)
        assertFalse(0xA2 in ops)
        assertTrue(fw.voltageAtPowerOn.all { it == "5V" })
    }

    @Test
    fun switchingPlatform_changesVoltageOnlyWhileCartUnpowered() {
        fw.rom = dmgHeader()
        val s = open()
        runBlocking { s.readHeader(CartPlatform.GB_GBC) }
        assertEquals("5V", fw.voltage)
        assertTrue(fw.powered)
        fw.rom = agbHeader()
        runBlocking { s.readHeader(CartPlatform.GBA) }
        assertFalse("voltage changed while cart was powered", fw.voltageChangedWhilePowered)
        assertEquals("3.3V", fw.voltage)
        assertEquals(listOf("5V", "3.3V"), fw.voltageAtPowerOn)
    }

    @Test
    fun voltageOpcodesOnlyEverComeFromPlatform() {
        fw.rom = agbHeader()
        val s = open()
        runBlocking { s.readHeader(CartPlatform.GBA) }
        val sent = link.opcodes().filter { it in voltageOpcodes }.toSet()
        assertEquals(setOf(CartPlatform.GBA.voltageOpcode), sent)
    }

    // ---- handshake and flows -----------------------------------------------------------------

    @Test
    fun handshake_parsesFirmwareAndLeavesCartUnpowered() {
        val s = open()
        assertEquals("L15", s.firmware.versionString)
        assertEquals("GBFlash", s.firmware.deviceName)
        assertFalse(fw.powered)
        // A1, AUTO_POWEROFF_ENABLED=0, AUTO_POWEROFF_TIME=0, F3, then PING
        assertEquals(listOf(0xA1, 0xA6, 0xA6, 0xF3, 0xFE), link.opcodes())
    }

    @Test
    fun handshake_rejectsNonGbflashFirmware() {
        val l = FakeSerialLink()
        FakeFirmware(l, fwReply = hex("08 58 00 0F 0D 6A 20 68 1E 07 47 42 46 6C 61 73 68 01 01"))
        val e = assertThrows(IncompatibleDeviceException::class.java) {
            runBlocking { DeviceSession.open(l, clock = l, io = Dispatchers.Unconfined) }
        }
        assertEquals('X', e.info.cfwId)
        assertTrue(l.closed)
    }

    @Test
    fun handshake_failsCleanlyWhenDeviceSilent() {
        val l = FakeSerialLink()
        assertThrows(ProtocolException::class.java) {
            runBlocking { DeviceSession.open(l, clock = l, io = Dispatchers.Unconfined) }
        }
        assertTrue(l.closed)
    }

    @Test
    fun dmgHeaderFlow_commandSequence() {
        fw.rom = dmgHeader(title = "ZELDA".toByteArray(), ramCode = 3)
        val s = open()
        link.writes.clear()
        val header = runBlocking { s.readHeader(CartPlatform.GB_GBC) } as DmgHeader
        assertEquals("ZELDA", header.title)
        val expected = listOf(
            0xF3, 0xA3, 0xA5, 0xA6, 0xA6, 0xA6, // power off, mode, 5V, DMG_READ_METHOD, CART_MODE, ADDRESS
            0xF4, 0xA3, 0xF2, 0xF4, 0xB4, // CartPowerOn: query, mode, power, query, MBC reset
            0xFE, 0xF4, 0xAC, 0xA5, 0xB4, // ping, already powered, pullups, 5V, MBC reset
            0xA6, 0xA6, 0xA6, 0xA6, 0xA6, // CS pulses, TRANSFER_SIZE, ADDRESS, ACCESS_MODE
            0xB1, 0xB1, 0xB1, 0xB1, 0xB1, 0xB1, // 6 x 64 bytes
            0xA8,
        )
        assertEquals(expected, link.opcodes())
        // TRANSFER_SIZE = 0x40 was set before reading
        assertTrue(fw.setVarLog.any { it.contentEquals(hex("A6 02 00000000 00000040")) })
    }

    @Test
    fun agbHeaderFlow_commandSequence() {
        fw.rom = agbHeader(marker = 0x1B)
        val s = open()
        link.writes.clear()
        val header = runBlocking { s.readHeader(CartPlatform.GBA) } as AgbHeader
        assertEquals("TESTGBA", header.title)
        val expected = listOf(
            0xF3, 0xA2, 0xA4, 0xA6, 0xA6, 0xA6, 0xA6, // power off, mode, 3.3V, AGB_READ_METHOD, CART_MODE, IRQ, ADDRESS
            0xF4, 0xA2, 0xF2, 0xF4, 0xC9, // CartPowerOn ends with the AGB boot-up sequence
            0xFE, 0xF4, 0xAC, 0xA4, // ping, powered, pullups, 3.3V
            0xA6, 0xA6, // TRANSFER_SIZE, ADDRESS
            0xC1, 0xC1, 0xC1, 0xC1, 0xC1, 0xC1,
        )
        assertEquals(expected, link.opcodes())
    }

    @Test
    fun secondHeaderRead_sameMode_doesNotPowerCycle() {
        fw.rom = dmgHeader()
        val s = open()
        runBlocking { s.readHeader(CartPlatform.GB_GBC) }
        link.writes.clear()
        runBlocking { s.readHeader(CartPlatform.GB_GBC) }
        assertFalse(0xF3 in link.opcodes())
        assertFalse(0xA3 in link.opcodes())
    }

    @Test
    fun close_powersCartOffAndClosesPort() {
        fw.rom = dmgHeader()
        val s = open()
        runBlocking {
            s.readHeader(CartPlatform.GB_GBC)
            assertTrue(fw.powered)
            s.close()
        }
        assertFalse(fw.powered)
        assertTrue(link.closed)
        assertEquals(0xF3, link.opcodes().last())
    }

    @Test
    fun deviceError_surfacesAsException() {
        val s = open()
        link.enqueue(0x02) // pre-load an error where the next ACK would be consumed
        fw.swallowReplies = 1
        assertThrows(ProtocolException::class.java) {
            runBlocking { s.readHeader(CartPlatform.GB_GBC) }
        }
    }

    @Test
    fun gbaSaveProbe_isReadOnly() {
        fw.rom = agbHeader()
        fw.saveWindow = ByteArray(0x10000) { (it % 0x8000 / 7).toByte() }
        val s = open()
        val hint = runBlocking { s.probeAgbSaveReadOnly() }
        assertEquals(3, hint.suggestedType)
        // No cart-write opcodes (B2/B3/C2/C4/C6/C7/D4) anywhere.
        val writeOps = setOf(0xB2, 0xB3, 0xC2, 0xC4, 0xC6, 0xC7, 0xD4)
        assertTrue(link.opcodes().none { it in writeOps })
    }
}

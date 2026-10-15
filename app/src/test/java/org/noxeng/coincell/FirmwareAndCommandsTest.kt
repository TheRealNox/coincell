package org.noxeng.coincell

import org.noxeng.coincell.protocol.Commands
import org.noxeng.coincell.protocol.FirmwareInfo
import org.noxeng.coincell.protocol.FwVar
import org.noxeng.coincell.protocol.Transport
import org.noxeng.coincell.protocol.UnexpectedResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FirmwareAndCommandsTest {
    private val link = FakeSerialLink()
    private val transport = Transport(link, link)

    private fun parse(reply: ByteArray): FirmwareInfo {
        link.enqueue(reply)
        return FirmwareInfo.read(transport, 1000)
    }

    @Test
    fun firmwareInfo_specSyntheticExample() {
        val fw = parse(SPEC_FW_REPLY)
        assertEquals('L', fw.cfwId)
        assertEquals(15, fw.fwVer)
        assertEquals("L15", fw.versionString)
        assertEquals(13, fw.pcbVer)
        assertEquals("v1.3", fw.pcbLabel)
        assertEquals(0x6A20681EL, fw.fwTimestamp)
        assertEquals("GBFlash", fw.deviceName)
        assertTrue(fw.cartPowerCtrl)
        assertFalse(fw.cartPresenceSwitch)
        assertFalse(fw.cartModeSwitch)
        assertTrue(fw.bootloaderReset)
        assertFalse(fw.unregistered)
        assertNull(fw.incompatibility())
    }

    @Test
    fun firmwareInfo_flagsAndUnnamedDevice() {
        // flags1 = 0b111, flags2 = 0x81; name is invalid UTF-8
        val reply = hex("08 4C 00 0F 0D 6A 20 68 1E 02 FF FE 07 81")
        val fw = parse(reply)
        assertEquals("Unnamed Device", fw.deviceName)
        assertTrue(fw.cartPowerCtrl && fw.cartPresenceSwitch && fw.cartModeSwitch)
        assertTrue(fw.bootloaderReset && fw.unregistered)
    }

    @Test
    fun firmwareInfo_badSizeByteRejected() {
        link.enqueue(hex("07 4C 00 0F 0D 6A 20 68 1E"))
        assertThrows(UnexpectedResponseException::class.java) { FirmwareInfo.read(transport, 1000) }
    }

    @Test
    fun firmwareInfo_compatibilityRules() {
        val ok = parse(SPEC_FW_REPLY)
        assertNotNull(ok.copy(cfwId = 'X').incompatibility())
        assertNotNull(ok.copy(pcbVer = 9).incompatibility())
        assertNotNull(ok.copy(pcbVer = 5).incompatibility())
        assertNotNull(ok.copy(fwTimestamp = 1730591999L).incompatibility())
        assertNotNull(ok.copy(fwVer = 11).incompatibility())
        assertNull(ok.copy(fwTimestamp = 1730592000L).incompatibility())
    }

    private fun commands(fw: FirmwareInfo = parse(SPEC_FW_REPLY)) = Commands(transport, fw, Random(1))

    @Test
    fun setVar_encodesTenBytesBigEndian() {
        assertArrayEquals(hex("A6 04 00000000 00000000"), Commands.encodeSetVar(FwVar.ADDRESS, 0))
        assertArrayEquals(hex("A6 02 00000000 00000040"), Commands.encodeSetVar(FwVar.TRANSFER_SIZE, 0x40))
        assertArrayEquals(hex("A6 01 0000000B 00000001"), Commands.encodeSetVar(FwVar.DMG_READ_METHOD, 1))
        assertArrayEquals(hex("A6 01 00000000 00000002"), Commands.encodeSetVar(FwVar.CART_MODE, 2))
        assertArrayEquals(hex("A6 04 00000001 00001388"), Commands.encodeSetVar(FwVar.AUTO_POWEROFF_TIME, 5000))
        assertArrayEquals(hex("A6 01 00000010 00000000"), Commands.encodeSetVar(FwVar.AGB_IRQ_ENABLED, 0))
        assertArrayEquals(hex("A6 04 00000000 DEADBEEF"), Commands.encodeSetVar(FwVar.ADDRESS, 0xDEADBEEFL))
    }

    @Test
    fun setVar_waitsForAck_andSendsOneWrite() {
        val cmds = commands()
        link.enqueue(0x01)
        cmds.setVar(FwVar.TRANSFER_SIZE, 0x40)
        assertEquals(1, link.writes.size)
        assertArrayEquals(hex("A6 02 00000000 00000040"), link.writes[0])
    }

    @Test
    fun getVar_requestAndParse() {
        val cmds = commands()
        link.enqueue(0x00, 0x00, 0x01, 0x02)
        assertEquals(0x102L, cmds.getVar(FwVar.AUTO_POWEROFF_TIME))
        assertArrayEquals(hex("AD 04 00000001"), link.writes.last())
    }

    @Test
    fun ping_acceptsComplementAndRejectsOthers() {
        val cmds = commands()
        link.onWrite = { data -> link.enqueue(data[1].toInt().inv() and 0xFF) }
        cmds.ping()
        assertEquals(0xFE, link.writes.last()[0].toInt() and 0xFF)
        link.onWrite = { data -> link.enqueue(data[1].toInt() and 0xFF) } // not inverted
        assertThrows(UnexpectedResponseException::class.java) { cmds.ping() }
    }
}

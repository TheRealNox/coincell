package com.therealnox.gbflash

import com.therealnox.gbflash.protocol.Commands
import com.therealnox.gbflash.protocol.DeviceErrorException
import com.therealnox.gbflash.protocol.FwVar
import com.therealnox.gbflash.protocol.ProtocolException
import com.therealnox.gbflash.protocol.ReadTimeoutException
import com.therealnox.gbflash.protocol.Transport
import com.therealnox.gbflash.protocol.UnexpectedResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTest {
    private val link = FakeSerialLink()
    private val transport = Transport(link, link)

    @Test
    fun readExact_collectsPartialReads() {
        link.maxPerRead = 1
        link.enqueue(1, 2, 3)
        assertArrayEquals(bytes(1, 2, 3), transport.readExact(3))
    }

    @Test
    fun readExact_timeoutThrowsAndDrains() {
        link.enqueue(0xAA)
        val e = assertThrows(ReadTimeoutException::class.java) { transport.readExact(4, timeout = 500) }
        assertTrue(e.message!!.contains("got 1"))
    }

    @Test
    fun waitAck_accepts01And03() {
        link.enqueue(0x01)
        assertEquals(1, transport.waitAck())
        link.enqueue(0x03)
        assertEquals(3, transport.waitAck())
    }

    @Test
    fun waitAck_02IsDeviceError() {
        link.enqueue(0x02)
        assertThrows(DeviceErrorException::class.java) { transport.waitAck() }
        assertEquals(1, transport.writeErrors)
    }

    @Test
    fun waitAck_otherByteIsUnexpected() {
        link.enqueue(0x55)
        assertThrows(UnexpectedResponseException::class.java) { transport.waitAck() }
    }

    @Test
    fun waitAck_silenceIsTimeout() {
        assertThrows(ReadTimeoutException::class.java) { transport.waitAck() }
    }

    @Test
    fun tryWrite_resyncsWithZeroPaddingThenResends() {
        val cmd = Commands.encodeSetVar(FwVar.TRANSFER_SIZE, 0x40)
        var seen = 0
        link.onWrite = { data ->
            seen++
            when {
                seen == 1 -> Unit // firmware stuck: no answer to the command
                data.size == 1 && data[0].toInt() == 0 -> link.enqueue(0x01) // pad answered
                else -> link.enqueue(0x01) // resent command acknowledged
            }
        }
        assertEquals(1, transport.tryWrite(cmd))
        assertEquals(3, link.writes.size)
        assertArrayEquals(cmd, link.writes[0])
        assertArrayEquals(bytes(0), link.writes[1])
        assertArrayEquals(cmd, link.writes[2])
    }

    @Test
    fun tryWrite_givesUpAfterFiveAttempts() {
        val cmd = Commands.encodeSetVar(FwVar.ADDRESS, 0)
        val e = assertThrows(ProtocolException::class.java) { transport.tryWrite(cmd) }
        assertTrue(e.message!!.contains("5 attempts"))
        assertEquals(5, link.writes.count { it.contentEquals(cmd) })
    }

    @Test
    fun drainInput_discardsStaleBytes() {
        link.enqueue(1, 2, 3)
        transport.drainInput()
        assertThrows(ReadTimeoutException::class.java) { transport.readExact(1, 50) }
    }
}

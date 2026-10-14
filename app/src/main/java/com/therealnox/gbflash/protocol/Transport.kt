package com.therealnox.gbflash.protocol

import com.therealnox.gbflash.usb.Clock
import com.therealnox.gbflash.usb.SerialLink
import com.therealnox.gbflash.usb.SystemClock
import java.io.IOException

open class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** No (or too few) response bytes arrived in time. */
class ReadTimeoutException(message: String) : ProtocolException(message)

/** The device answered an ACK request with 0x02. */
class DeviceErrorException(message: String) : ProtocolException(message)

/** The device answered with something that is not a valid reply. */
class UnexpectedResponseException(message: String) : ProtocolException(message)

internal fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }

/**
 * Wire conventions of spec section 1: raw writes, read-exactly-N with a deadline, ACK handling and
 * the `_try_write` resync. Blocking; callers serialise access (see [DeviceSession]).
 */
class Transport(
    private val link: SerialLink,
    private val clock: Clock = SystemClock,
    private val log: (String) -> Unit = {},
) {
    /** Default response timeout (spec: DEVICE_TIMEOUT = 1 s). */
    var timeoutMs: Long = 1000

    var writeErrors: Int = 0
        private set

    // Spec 1.4: after more than 3 failed ACKs FlashGBX sleeps 1.4 ms after every write.
    private var writeDelay = false

    /** One contiguous write (spec 1.2). */
    fun write(data: ByteArray) {
        log("TX " + data.abbreviated())
        link.write(data)
        if (writeDelay) clock.sleep(WRITE_DELAY_MS)
    }

    fun write(byte: Int) = write(byteArrayOf(byte.toByte()))

    /** Waits [ms] on the transport's clock (virtual in tests). */
    fun sleep(ms: Long) = clock.sleep(ms)

    /**
     * Reads exactly [count] bytes. On a short read, discards pending input (spec 1.3 step 4) and
     * throws [ReadTimeoutException].
     */
    fun readExact(count: Int, timeout: Long = timeoutMs): ByteArray {
        val out = ByteArray(count)
        var got = 0
        val deadline = clock.nowMs() + timeout
        while (got < count) {
            val remaining = deadline - clock.nowMs()
            if (remaining <= 0) break
            val buf = ByteArray(count - got)
            val n = link.read(buf, remaining.toInt())
            if (n > 0) {
                System.arraycopy(buf, 0, out, got, n)
                got += n
            }
        }
        if (got < count) {
            log("RX (short: $got of $count) " + out.copyOf(got).abbreviated())
            drainInput()
            throw ReadTimeoutException("Timeout: expected $count byte(s), got $got")
        }
        log("RX " + out.abbreviated())
        return out
    }

    fun readByte(timeout: Long = timeoutMs): Int = readExact(1, timeout)[0].toInt() and 0xFF

    /** Waits for the ACK byte (spec 1.4): 0x01 or 0x03 succeed, 0x02 is a device error. */
    fun waitAck(): Int {
        val value = try {
            readByte()
        } catch (e: ReadTimeoutException) {
            noteWriteError()
            throw ReadTimeoutException("No ACK from device (timeout)")
        }
        if (value == ACK_OK || value == ACK_OK_ALT) return value
        noteWriteError()
        if (value == ACK_ERROR) throw DeviceErrorException("Device reported an error (0x02)")
        throw UnexpectedResponseException("Unexpected ACK byte 0x%02X".format(value))
    }

    /** Write then wait for ACK, without resync. */
    fun writeAck(data: ByteArray): Int {
        write(data)
        return waitAck()
    }

    fun writeAck(byte: Int): Int = writeAck(byteArrayOf(byte.toByte()))

    /**
     * `_try_write` (spec 1.5): up to 5 attempts; after a failed attempt pad with 0x00 until the
     * firmware answers 0x01 or 0x02 (max 20 pads), then resend the whole command.
     */
    fun tryWrite(data: ByteArray): Int {
        var lastError: ProtocolException? = null
        repeat(TRY_WRITE_RETRIES) {
            try {
                return writeAck(data)
            } catch (e: ProtocolException) {
                lastError = e
            }
            resync()
        }
        throw ProtocolException("Command failed after $TRY_WRITE_RETRIES attempts: ${lastError?.message}", lastError)
    }

    private fun resync() {
        var pads = RESYNC_PADS
        while (pads > 0) {
            link.purge()
            write(0x00)
            val reply = try {
                readByte()
            } catch (e: ReadTimeoutException) {
                -1
            }
            if (reply == 1 || reply == 2) return
            pads--
        }
    }

    /** Discards everything the device has sent that we have not consumed. */
    fun drainInput() {
        link.purge()
        val buf = ByteArray(DRAIN_BUF)
        var rounds = 0
        while (rounds++ < DRAIN_MAX_ROUNDS) {
            if (link.read(buf, DRAIN_QUIET_MS) <= 0) break
        }
    }

    private fun noteWriteError() {
        writeErrors++
        if (writeErrors > 3) writeDelay = true
    }

    private fun ByteArray.abbreviated(): String =
        if (size <= LOG_MAX_BYTES) toHexString() else copyOf(LOG_MAX_BYTES).toHexString() + " ...(+${size - LOG_MAX_BYTES})"

    companion object {
        const val ACK_OK = 0x01
        const val ACK_OK_ALT = 0x03
        const val ACK_ERROR = 0x02
        private const val TRY_WRITE_RETRIES = 5
        private const val RESYNC_PADS = 20
        private const val WRITE_DELAY_MS = 2L
        private const val DRAIN_BUF = 4096
        private const val DRAIN_MAX_ROUNDS = 256
        private const val DRAIN_QUIET_MS = 20
        private const val LOG_MAX_BYTES = 64
    }
}

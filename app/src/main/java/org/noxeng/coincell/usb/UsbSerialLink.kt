package org.noxeng.coincell.usb

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException

/** [SerialLink] over a usb-serial-for-android port that is already open and configured. */
class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    // Read exactly one USB packet per transfer and hand out slices, so callers can ask for any
    // amount without losing bytes. A larger buffer breaks replies that are an exact multiple of
    // the packet size (e.g. 64 bytes on the CH340's 32-byte endpoint): with no short packet the
    // bulk transfer only ends at the timeout, and Android then discards the bytes received.
    private val chunk = ByteArray(port.readEndpoint.maxPacketSize)
    private var chunkPos = 0
    private var chunkLen = 0

    @Volatile
    private var closed = false

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        if (closed) throw IOException("Port closed")
        if (chunkPos >= chunkLen) {
            chunkLen = port.read(chunk, timeoutMs.coerceAtLeast(1))
            chunkPos = 0
            if (chunkLen <= 0) {
                chunkLen = 0
                return 0
            }
        }
        val n = minOf(dest.size, chunkLen - chunkPos)
        System.arraycopy(chunk, chunkPos, dest, 0, n)
        chunkPos += n
        return n
    }

    override fun write(data: ByteArray) {
        if (closed) throw IOException("Port closed")
        port.write(data, WRITE_TIMEOUT_MS)
    }

    override fun purge() {
        chunkPos = 0
        chunkLen = 0
        try {
            port.purgeHwBuffers(true, true)
        } catch (_: UnsupportedOperationException) {
            // Not supported by this driver; Transport.drainInput() reads the rest out.
        } catch (_: IOException) {
            // Best effort only.
        }
    }

    override fun close() {
        closed = true
        try {
            port.close()
        } catch (_: IOException) {
            // Already gone (e.g. detached).
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2000
    }
}

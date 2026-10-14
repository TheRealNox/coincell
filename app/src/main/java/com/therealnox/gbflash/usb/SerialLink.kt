package com.therealnox.gbflash.usb

import java.io.Closeable

/**
 * Thin blocking byte-pipe in front of the physical serial port, so the protocol layer can be
 * unit-tested with a fake. Implementations must be safe to [close] from another thread while a
 * read is blocked (the blocked call should then fail with an IOException).
 */
interface SerialLink : Closeable {
    /**
     * Blocks for up to [timeoutMs] waiting for data, then copies at most `dest.size` bytes into
     * [dest]. Returns the number of bytes copied; 0 means nothing arrived before the timeout.
     */
    fun read(dest: ByteArray, timeoutMs: Int): Int

    /** Writes all of [data] as one logical write (blocking). */
    fun write(data: ByteArray)

    /** Discards anything buffered by the driver/OS, best effort. */
    fun purge()
}

/** Time source + sleeper, injectable so tests do not wait in real time. */
interface Clock {
    fun nowMs(): Long

    fun sleep(ms: Long)
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000

    override fun sleep(ms: Long) {
        Thread.sleep(ms)
    }
}

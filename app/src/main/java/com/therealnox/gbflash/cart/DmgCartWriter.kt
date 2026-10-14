package com.therealnox.gbflash.cart

/**
 * The ONLY way to write cartridge SRAM (`B3`). It is deliberately separate from [DmgCartIo]: the
 * backup engine is built on [DmgCartIo] alone and therefore cannot write data. Only the restore
 * engine receives a writer, and only [com.therealnox.gbflash.protocol.DeviceSession.restoreDmgSave]
 * creates one.
 */
internal interface DmgCartWriter {
    /**
     * Writes [data] (1..[MAX_REQUEST] bytes) to cartridge RAM at 0xA000 + [offset]. The caller has
     * already enabled RAM and selected the bank through [DmgCartIo]. Throws
     * [IllegalArgumentException] for anything outside 0xA000-0xBFFF.
     */
    fun writeRam(offset: Int, data: ByteArray)

    /** Cleanup: write pulse off and address reset, safe to call at any time (even after a failed write). */
    fun release()

    companion object {
        /** Wire request size of `dmgRamWrite` (spec 3.1). */
        const val MAX_REQUEST = 256
        const val RAM_WINDOW = 0x2000
    }
}

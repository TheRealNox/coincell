package org.noxeng.coincell.cart

/**
 * The ONLY things a GBA save backup or save-type detection may do to a cartridge (spec 5.7, 6.6, 6.9).
 *
 * There is deliberately no method that carries save data to the cartridge, and no way to send an arbitrary
 * Flash command list: every write is one of a few fixed, named sequences, none of which erases or programs
 * anything.
 *
 *  - Flash identify (`ReadFlashSaveID`, LK:1616-1673): enter / exit ID mode, plus the temporary one-byte test
 *    that tells SRAM from Flash and the restore of the bytes the ID sequence may have touched on SRAM.
 *  - Bank select for 1M saves: the Flash `AA 55 B0 <bank>` sequence, or the bootleg bank register in ROM space.
 *  - The GPIO real-time clock registers (ROM space 0xC4 / 0xC6 / 0xC8) and the `CA` clock read.
 *
 * Erase, program and the SRAM / EEPROM / Flash data writes live in [AgbCartWriter], which only the restore
 * engine receives.
 */
internal interface AgbCartIo {
    /** Reads [length] bytes of the save window at [address] (`C3`, set up for every wire request). */
    fun saveRead(address: Int, length: Int): ByteArray

    /** Reads [length] bytes of EEPROM from 8-byte block [blockAddress] (`C5 <type>`). */
    fun eepromRead(eepromType: Int, blockAddress: Int, length: Int): ByteArray

    /** Reads cartridge ROM at byte address [byteAddress] (`C1`, word addressed on the wire). */
    fun romRead(byteAddress: Int, length: Int): ByteArray

    /** 1M Flash: `AA 55 B0` then bank register, followed by the 50 ms settle time (LK:3615-3626). */
    fun selectFlashBank(bank: Int)

    /** Bootleg 1M saves (type 8 SRAM and the SST bootleg chips): bank register at ROM address 0x1000000. */
    fun selectBootlegBank(bank: Int)

    /** `ReadFlashSaveID` step 1: write `original xor 0xFF` to save offset 4. Harmless to Flash, flips SRAM. */
    fun flashIdProbeFlip(original: Int)

    /** `ReadFlashSaveID` step 1: write [original] back to save offset 4. */
    fun flashIdProbeRestore(original: Int)

    /** Enter Flash ID mode: `5555=AA 2AAA=55 5555=90`. */
    fun flashIdEnter()

    /** Leave Flash ID mode: `5555=AA 2AAA=55 5555=F0`, then `0000=F0`, with the 10 ms pauses of LK:1654-1658. */
    fun flashIdExit()

    /** Put back the three bytes the ID sequence may have overwritten on a non-Flash chip (LK:1660-1666). */
    fun flashIdRestoreBytes(at5555: Int, at2AAA: Int, at0000: Int)

    /** GPIO register write (`C2`); only ROM addresses 0xC4, 0xC6 and 0xC8 are accepted. */
    fun gpioWrite(address: Int, value: Int)

    /** `CA`: the firmware reads the clock and answers 8 bytes `[status, YY, MM, DD, WW, HH, MI, SS]`. */
    fun readGpioRtc(): ByteArray

    /** Waits [ms] on the transport clock. */
    fun sleep(ms: Long)

    companion object {
        const val GPIO_DAT = 0xC4
        const val GPIO_CNT = 0xC6
        const val GPIO_RE = 0xC8
        val GPIO_ADDRESSES = setOf(GPIO_DAT, GPIO_CNT, GPIO_RE)

        /** Largest read the firmware answers in one request (`MAX_BUFFER_READ`, LK:3582). */
        const val MAX_READ_REQUEST = 0x1000
        const val BANK_SIZE = 0x10000
    }
}

/**
 * The only way to erase, program or write a GBA cartridge's save memory. It is deliberately separate from
 * [AgbCartIo]: the backup engine and the detection are built on [AgbCartIo] alone and therefore cannot write
 * data. Only the restore engine receives a writer, and only
 * [org.noxeng.coincell.protocol.DeviceSession.restoreAgbSave] creates one.
 */
internal interface AgbCartWriter {
    /** SRAM / FRAM write at save [address] (`C4`, 1..[MAX_REQUEST] bytes, one wire request). */
    fun writeSram(address: Int, data: ByteArray)

    /** EEPROM write of 8-byte blocks starting at [blockAddress] (`C6 <type>`, one wire request). */
    fun writeEeprom(eepromType: Int, blockAddress: Int, data: ByteArray)

    /**
     * Flash program of [data] at save [address] (`C7 01`), or for [atmelPages] the Atmel AT29LV512 page write
     * (`C7 02`, addressed in 128 byte pages). The sector must have been erased already.
     */
    fun writeFlash(address: Int, data: ByteArray, atmelPages: Boolean)

    /** Flash sector erase: `AA 55 80 AA 55 <sector>=30` (LK:3724-3732). The caller polls for completion. */
    fun eraseFlashSector(sectorAddress: Int)

    /** GPIO register write for the RTC (`C2`); only ROM addresses 0xC4, 0xC6 and 0xC8 are accepted. */
    fun writeGpio(address: Int, value: Int)

    companion object {
        /** Wire request size of the save writers (`WriteRAM` max_length, LK:1835). */
        const val MAX_REQUEST = 256
    }
}

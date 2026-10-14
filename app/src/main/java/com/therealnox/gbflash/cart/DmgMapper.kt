package com.therealnox.gbflash.cart

/**
 * The ONLY things a save backup may do to a cartridge besides reading (spec 6.1 - 6.5).
 *
 * There is deliberately no "write data" method: [writeRegister] only accepts mapper register
 * addresses below 0x8000 (RAM enable, bank select, RTC register select, latch), so a bug cannot turn
 * a backup into a write to cartridge SRAM at 0xA000-0xBFFF.
 */
internal interface DmgCartIo {
    /** Mapper register write (`B2`). Throws [IllegalArgumentException] for addresses >= 0x8000. */
    fun writeRegister(address: Int, value: Int)

    /** `CLK_TOGGLE` (`A9`), used around MBC3 RTC accesses. */
    fun clockToggle(count: Int)

    /** Reads [length] bytes at 0xA000 + [offset] (`B1` in RAM access mode). */
    fun ramRead(offset: Int, length: Int): ByteArray
}

/** Mapper register protocols for the cartridges whose RAM can be backed up (spec 6.2 - 6.5). */
internal enum class DmgMapper(val label: String) {
    /** No MBC / unknown-but-RAM carts: MBC5-style writes. */
    BASE("no mapper"),
    MBC1("MBC1"),
    MBC2("MBC2"),
    MBC3("MBC3"),
    MBC5("MBC5"),
    ;

    /** MBC2 RAM is 512 x 4 bit: only the low nibble of every byte is real (spec 6.3). */
    val nibbleOnly: Boolean get() = this == MBC2

    fun enableRam(io: DmgCartIo) {
        if (this == MBC1) io.writeRegister(0x6000, 0x01) // banking mode 1
        io.writeRegister(0x0000, 0x0A)
    }

    fun disableRam(io: DmgCartIo) {
        io.writeRegister(0x0000, 0x00)
        if (this == MBC1) io.writeRegister(0x6000, 0x00)
    }

    fun selectRamBank(io: DmgCartIo, bank: Int) {
        if (this != MBC2) io.writeRegister(0x4000, bank and 0xFF)
    }

    companion object {
        /** Maps the FlashGBX mapper id to a supported protocol, or null ("not supported yet"). */
        fun forMapperRaw(id: Int): DmgMapper? = when (id) {
            0x00, 0x08, 0x09 -> BASE
            0x01, 0x02, 0x03 -> MBC1
            0x05, 0x06 -> MBC2
            in 0x0F..0x13, 0x110 -> MBC3
            in 0x19..0x1E -> MBC5
            else -> null
        }
    }
}

package com.therealnox.gbflash

/** Mapper families the fake cartridge can emulate. */
enum class FakeMapper { NONE, MBC1, MBC2, MBC3, MBC5 }

/**
 * A DMG cartridge as the CPU bus sees it: ROM with a bank register, SRAM banks behind a RAM-enable
 * latch, MBC1 banking mode, MBC2 4-bit RAM with open-bus upper nibbles and an MBC3 RTC with latch.
 */
class FakeDmgCart(
    val mapper: FakeMapper,
    val rom: ByteArray,
    val sram: ByteArray,
    val hasRtc: Boolean = false,
) {
    var ramEnabled = false
        private set
    var ramBankReg = 0
        private set
    var mode1 = false
        private set
    var romBank = 1
        private set
    private var rtcSelect = -1
    private var latchPrev = -1

    /** Live clock registers S, M, H, DL, DH; tests set them. */
    val liveRtc = IntArray(5)
    private val latchedRtc = IntArray(5)

    /** Every mapper register write (address < 0x8000), in order. */
    val registerWrites = mutableListOf<Pair<Int, Int>>()

    /** Any write that landed in 0xA000-0xBFFF; must stay empty during a backup. */
    val sramWriteAttempts = mutableListOf<Pair<Int, Int>>()

    /** Data writes into the selected RTC register (register 8..12, value), in order. */
    val rtcWrites = mutableListOf<Pair<Int, Int>>()

    val rtcSelected: Int get() = rtcSelect

    fun write(addr: Int, v: Int) {
        if (addr >= 0xA000) {
            sramWriteAttempts += addr to v
            if (!(ramEnabled || ramEnabledAlways)) return
            when {
                mapper == FakeMapper.MBC2 -> sram[(addr - 0xA000) and 0x1FF] = (v and 0x0F).toByte() // 4 bit RAM
                mapper == FakeMapper.MBC3 && rtcSelect in 8..12 -> if (hasRtc) {
                    liveRtc[rtcSelect - 8] = v
                    rtcWrites += rtcSelect to v
                }
                else -> {
                    val i = ramIndex(addr)
                    if (i in sram.indices) sram[i] = v.toByte()
                }
            }
            return
        }
        registerWrites += addr to v
        when (mapper) {
            FakeMapper.NONE -> Unit
            FakeMapper.MBC1 -> when (addr) {
                in 0x0000..0x1FFF -> ramEnabled = v and 0xF == 0xA
                in 0x2000..0x3FFF -> romBank = (v and 0x1F).coerceAtLeast(1)
                in 0x4000..0x5FFF -> ramBankReg = v and 3
                else -> mode1 = v and 1 == 1
            }
            FakeMapper.MBC2 -> if (addr < 0x4000) {
                if (addr and 0x100 == 0) ramEnabled = v and 0xF == 0xA else romBank = (v and 0xF).coerceAtLeast(1)
            }
            FakeMapper.MBC3 -> when (addr) {
                in 0x0000..0x1FFF -> ramEnabled = v and 0xF == 0xA
                in 0x2000..0x3FFF -> romBank = (v and 0x7F).coerceAtLeast(1)
                in 0x4000..0x5FFF -> if (v in 8..12) {
                    rtcSelect = v
                } else {
                    rtcSelect = -1
                    ramBankReg = v and 7
                }
                else -> {
                    if (latchPrev == 0 && v == 1) liveRtc.copyInto(latchedRtc)
                    latchPrev = v
                }
            }
            FakeMapper.MBC5 -> when (addr) {
                in 0x0000..0x1FFF -> ramEnabled = v == 0x0A
                in 0x2000..0x3FFF -> romBank = (romBank and 0x100) or (v and 0xFF)
                in 0x4000..0x5FFF -> ramBankReg = v and 0xF
            }
        }
    }

    private val ramEnabledAlways = mapper == FakeMapper.NONE // no MBC: RAM is wired to the bus

    private fun ramIndex(addr: Int): Int {
        val off = addr - 0xA000
        return when (mapper) {
            FakeMapper.MBC2 -> off and 0x1FF
            FakeMapper.MBC1 -> (if (mode1) ramBankReg else 0) * 0x2000 + off
            FakeMapper.NONE -> off
            else -> ramBankReg * 0x2000 + off
        }
    }

    fun read(addr: Int): Int = when {
        addr < 0x4000 -> rom.getOrElse(addr) { 0 }.toInt() and 0xFF
        addr < 0x8000 -> rom.getOrElse(romBank * 0x4000 + addr - 0x4000) { 0 }.toInt() and 0xFF
        addr < 0xA000 -> 0xFF
        !(ramEnabled || ramEnabledAlways) -> 0xFF
        mapper == FakeMapper.MBC2 -> 0xF0 or (sram.getOrElse(ramIndex(addr)) { 0 }.toInt() and 0x0F)
        mapper == FakeMapper.MBC3 && rtcSelect in 8..12 ->
            if (hasRtc) latchedRtc[rtcSelect - 8] else 0xFF
        else -> sram.getOrElse(ramIndex(addr)) { 0xFF.toByte() }.toInt() and 0xFF
    }
}

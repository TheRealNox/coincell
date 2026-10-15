package org.noxeng.coincell

enum class AgbSaveKind { NONE, SRAM, EEPROM, FLASH }

/** One state-changing request the fake firmware received for the GBA cartridge, in order. */
sealed interface AgbWrite {
    /** `D4`: Flash command list (address, value) in save-memory addressing. */
    data class FlashCmds(val cmds: List<Pair<Int, Int>>) : AgbWrite

    /** `C2`: write into ROM space (byte address, value). */
    data class RomSpace(val address: Int, val value: Int) : AgbWrite

    data class SramData(val address: Int, val length: Int) : AgbWrite

    data class EepromData(val type: Int, val block: Int, val length: Int) : AgbWrite

    data class FlashData(val mode: Int, val address: Int, val length: Int) : AgbWrite
}

/**
 * A GBA cartridge as the firmware sees it: header ROM, SRAM / FRAM (with the bootleg bank register for 1M), serial
 * EEPROM 4K / 64K (the firmware hides the bit protocol; reading with the wrong type returns garbage like the real
 * chip does), a JEDEC Flash chip with ID mode, bank select, sector erase, AND-only programming and the Atmel page
 * write, and an S-3511A style GPIO clock.
 */
class FakeAgbCart(
    val rom: ByteArray,
    val kind: AgbSaveKind,
    val saveSize: Int,
    val flashId: Int = 0x6213,
    val hasRtc: Boolean = false,
    initial: ByteArray? = null,
) {
    val save: ByteArray = initial?.copyOf() ?: ByteArray(saveSize) { if (kind == AgbSaveKind.FLASH) 0xFF.toByte() else 0 }
    val saveBefore: ByteArray = save.copyOf()

    var bank = 0
        private set
    var idMode = false
        private set
    val writes = mutableListOf<AgbWrite>()
    val erasedSectors = mutableListOf<Pair<Int, Int>>() // bank, sector start
    val violations = mutableListOf<String>()

    // ---- clock ----
    /** Date bytes YY MM DD WW HH MI SS (BCD), status register. Tests set these. */
    val rtcDate = byteArrayOf(0x00, 0x05, 0x07, 0x01, 0x04, 0x33, 0x30)
    var rtcStatus = 0x40
    val rtcWrites = mutableListOf<String>()
    private var gpioRe = 0
    private var gpioCnt = 0
    private var gpioDat = 0
    private var prevSck = 0
    private var cs = 0
    private var phase = 0 // 0 idle, 1 command, 2 read, 3 write
    private var bits = 0
    private var cmdByte = 0
    private var outBytes = ByteArray(0)
    private var outBit = 0
    private var inBytes = ByteArray(0)
    private var inCount = 0
    private var inByte = 0

    private var cycle = 0
    private var eraseStage = 0

    // ---- save memory window (C3 reads, D4 command writes) ----

    private fun index(addr: Int): Int = (bank * 0x10000 + addr) % maxOf(saveSize, 1)

    fun saveRead(addr: Int): Int = when (kind) {
        AgbSaveKind.SRAM -> save[index(addr)].toInt() and 0xFF
        AgbSaveKind.FLASH -> when {
            idMode && addr == 0 -> flashId shr 8
            idMode && addr == 1 -> flashId and 0xFF
            else -> save[(bank * 0x10000 + addr) % saveSize].toInt() and 0xFF
        }
        else -> 0 // open bus: an EEPROM-only or empty cartridge reads zeros in the save window
    }

    /** Flash command cycle or, on SRAM, a plain data write (this is why the byte-4 test tells them apart). */
    fun flashCommand(addr: Int, v: Int) {
        when (kind) {
            AgbSaveKind.SRAM -> save[index(addr)] = v.toByte()
            AgbSaveKind.FLASH -> flashCycle(addr, v)
            else -> Unit
        }
    }

    private fun flashCycle(addr: Int, v: Int) {
        when (cycle) {
            0 -> if (addr == 0x5555 && v == 0xAA) cycle = 1 else if (v == 0xF0) idMode = false
            1 -> cycle = if (addr == 0x2AAA && v == 0x55) 2 else 0
            2 -> {
                cycle = 0
                if (addr == 0x5555) {
                    when (v) {
                        0x90 -> idMode = true
                        0xF0 -> idMode = false
                        0x80 -> { cycle = 3; eraseStage = 0 }
                        0xB0 -> cycle = 6
                        0xA0 -> violations += "host sent the program command A0"
                    }
                }
            }
            3 -> { if (addr == 0x5555 && v == 0xAA) cycle = 4 else cycle = 0 }
            4 -> { if (addr == 0x2AAA && v == 0x55) cycle = 5 else cycle = 0 }
            5 -> {
                cycle = 0
                if (v == 0x30) {
                    val sector = addr and 0xF000
                    val base = bank * 0x10000 + sector
                    for (i in 0 until 0x1000) save[(base + i) % saveSize] = 0xFF.toByte()
                    erasedSectors += bank to sector
                } else if (v == 0x10 && addr == 0x5555) {
                    save.fill(0xFF.toByte())
                }
            }
            6 -> { cycle = 0; if (addr == 0) bank = v and 1 }
        }
    }

    fun sramWrite(addr: Int, data: ByteArray) {
        if (kind != AgbSaveKind.SRAM) { violations += "C4 write on a non-SRAM cartridge"; return }
        data.forEachIndexed { i, b -> save[index(addr + i)] = b }
    }

    /** `C7 01`: programming only clears bits. `C7 02`: Atmel pages replace the whole page. */
    fun flashData(mode: Int, addr: Int, data: ByteArray) {
        if (kind != AgbSaveKind.FLASH) { violations += "C7 write on a non-Flash cartridge"; return }
        if (mode == 2) {
            if (flashId != 0x1F3D) violations += "Atmel page write on chip 0x%04X".format(flashId)
            val start = addr * 128 // addr is a 128 byte page number
            data.forEachIndexed { i, b -> save[(bank * 0x10000 + start + i) % saveSize] = b }
        } else {
            if (flashId == 0x1F3D) violations += "byte programming on the Atmel chip"
            data.forEachIndexed { i, b ->
                val p = (bank * 0x10000 + addr + i) % saveSize
                save[p] = (save[p].toInt() and b.toInt()).toByte()
            }
        }
    }

    // ---- EEPROM (8 byte blocks) ----

    private val eepromType: Int get() = if (saveSize == 512) 1 else 2

    fun eepromRead(type: Int, block: Int, length: Int): ByteArray {
        if (kind != AgbSaveKind.EEPROM) return ByteArray(length) { 0xFF.toByte() }
        return if (type == eepromType) {
            ByteArray(length) { save[(block * 8 + it) % saveSize] }
        } else if (type == 1) {
            // 4K read of a 64K chip: the short address is padded with zeros, so the first blocks come out right.
            ByteArray(length) { save[(block * 8 + it) % saveSize] }
        } else {
            // 64K read of a 4K chip: the chip loses sync, the answer is a shifted echo, never the real data.
            ByteArray(length) { ((save[(block * 8 + it) % saveSize].toInt() xor 0x5A) + 1).toByte() }
        }
    }

    fun eepromWrite(type: Int, block: Int, data: ByteArray) {
        if (kind != AgbSaveKind.EEPROM) { violations += "C6 write on a non-EEPROM cartridge"; return }
        if (type != eepromType) { violations += "EEPROM write with type $type on a type $eepromType chip"; return }
        data.forEachIndexed { i, b -> save[(block * 8 + i) % saveSize] = b }
    }

    // ---- ROM space (C1 reads, C2 writes) ----

    fun romByte(addr: Int): Int {
        if (hasRtc && gpioRe == 1 && addr in 0xC4..0xC9) {
            return when (addr) {
                0xC4 -> (if (phase == 2) outBit shl 1 else 0) or (gpioDat and 0x05)
                0xC6 -> gpioCnt
                0xC8 -> gpioRe
                else -> 0
            }
        }
        return rom.getOrElse(addr) { 0 }.toInt() and 0xFF
    }

    fun romSpaceWrite(addr: Int, value: Int) {
        writes += AgbWrite.RomSpace(addr, value)
        when {
            addr == 0x1000000 -> if ((kind == AgbSaveKind.SRAM && saveSize > 0x10000) || (kind == AgbSaveKind.FLASH && flashId in setOf(0xBF4B, 0xBF5B, 0xFFFF, 0xBF6D))) bank = value and 1
            hasRtc && addr == 0xC4 -> gpioData(value)
            hasRtc && addr == 0xC6 -> gpioCnt = value and 0xFF
            hasRtc && addr == 0xC8 -> gpioRe = value and 1
        }
    }

    private fun gpioData(v: Int) {
        val sck = v and 1
        val sio = (v shr 1) and 1
        val newCs = (v shr 2) and 1
        gpioDat = v and 7
        if (newCs == 0) {
            if (phase == 3) commitWrite()
            phase = 0
            cs = 0
            prevSck = sck
            return
        }
        if (cs == 0) { phase = 1; bits = 0; cmdByte = 0 }
        cs = 1
        if (sck == 1 && prevSck == 0) clockEdge(sio)
        prevSck = sck
    }

    private fun clockEdge(sio: Int) {
        when (phase) {
            1 -> {
                cmdByte = (cmdByte shl 1) or sio
                if (++bits == 8) startData()
            }
            2 -> {
                val byte = outBytes.getOrElse(bits / 8) { 0 }.toInt() and 0xFF
                outBit = (byte shr (bits % 8)) and 1
                bits++
            }
            3 -> {
                inByte = inByte or (sio shl (bits % 8))
                bits++
                if (bits % 8 == 0) {
                    inBytes[inCount++] = inByte.toByte()
                    inByte = 0
                    if (inCount == inBytes.size) commitWrite()
                }
            }
        }
    }

    private fun startData() {
        bits = 0
        when (cmdByte) {
            0x63 -> { phase = 2; outBytes = byteArrayOf(rtcStatus.toByte()) }
            0x65 -> { phase = 2; outBytes = rtcDate.copyOf() }
            0x62 -> { phase = 3; inBytes = ByteArray(1); inCount = 0; inByte = 0 }
            0x64 -> { phase = 3; inBytes = ByteArray(7); inCount = 0; inByte = 0 }
            else -> { phase = 0; violations += "unknown clock command 0x%02X".format(cmdByte) }
        }
    }

    private fun commitWrite() {
        if (inCount != inBytes.size) return
        if (inBytes.size == 1) {
            rtcStatus = inBytes[0].toInt() and 0xFF
            rtcWrites += "status %02X".format(rtcStatus)
        } else {
            inBytes.copyInto(rtcDate)
            rtcWrites += "date " + inBytes.joinToString(" ") { "%02X".format(it) }
        }
        phase = 0
        inCount = 0
    }

    /** `CA`: the firmware's own clock read, `[status, YY .. SS]`. */
    fun gpioRtcReply(): ByteArray = if (hasRtc) byteArrayOf(rtcStatus.toByte()) + rtcDate else ByteArray(8)
}

/** A 0x180 byte GBA header for [FakeAgbCart], with the logo and checksum valid. */
fun fakeAgbRom(title: String = "TESTGBA", code: String = "ABCD", marker: Int? = null, version: Int = 0): ByteArray =
    agbHeader(title = title, code = code, marker = marker).also {
        it[0xBC] = version.toByte()
        var sum = 0
        for (i in 0xA0..0xBC) sum += it[i].toInt() and 0xFF
        it[0xBD] = ((-sum - 0x19) and 0xFF).toByte()
    }.copyOf(0x400)

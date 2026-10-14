package com.therealnox.gbflash

import com.therealnox.gbflash.usb.Clock
import com.therealnox.gbflash.usb.SerialLink

fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

fun hex(s: String): ByteArray = s.filter { !it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Spec 2.2 synthetic reply: L15 / PCB 13 / ts 0x6A20681E / "GBFlash" / flags 01 01. */
val SPEC_FW_REPLY: ByteArray = hex("08 4C 00 0F 0D 6A 20 68 1E 07 47 42 46 6C 61 73 68 01 01")

/** Serial port stand-in with a virtual clock: an empty read "waits" by advancing time, instantly. */
class FakeSerialLink : SerialLink, Clock {
    var now = 0L
    private val rx = ArrayDeque<Byte>()
    val writes = mutableListOf<ByteArray>()
    var onWrite: (ByteArray) -> Unit = {}
    var closed = false
    var maxPerRead = Int.MAX_VALUE

    fun enqueue(data: ByteArray) = data.forEach { rx.addLast(it) }

    fun enqueue(vararg v: Int) = enqueue(bytes(*v))

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        if (rx.isEmpty()) {
            now += timeoutMs
            return 0
        }
        val n = minOf(dest.size, rx.size, maxPerRead)
        for (i in 0 until n) dest[i] = rx.removeFirst()
        return n
    }

    override fun write(data: ByteArray) {
        writes += data.copyOf()
        onWrite(data)
    }

    override fun purge() = rx.clear()

    override fun close() {
        closed = true
    }

    override fun nowMs() = now

    override fun sleep(ms: Long) {
        now += ms
    }

    /** First byte (opcode) of every write. */
    fun opcodes(): List<Int> = writes.map { it[0].toInt() and 0xFF }
}

/**
 * Minimal GBFlash firmware simulator driven by the bytes the host writes. Records what it sees so
 * tests can assert on the command stream and on voltage/power ordering.
 */
class FakeFirmware(
    private val link: FakeSerialLink,
    private val fwReply: ByteArray = SPEC_FW_REPLY,
    var rom: ByteArray = ByteArray(0),
    var saveWindow: ByteArray = ByteArray(0x10000) { 0xFF.toByte() },
) {
    private var buf = ByteArray(0)
    private val vars = HashMap<Pair<Int, Int>, Long>()
    private var romPos = 0
    private var savePos = 0

    var powered = false
        private set
    var voltage = "unknown"
        private set

    /** Set when A4/A5 arrived while the cart was powered. */
    var voltageChangedWhilePowered = false
        private set
    val voltageAtPowerOn = mutableListOf<String>()
    val setVarLog = mutableListOf<ByteArray>()

    /** Number of upcoming commands to leave unanswered (to test error handling). */
    var swallowReplies = 0

    /** Attached DMG cartridge; when set, B1/B2/B3/A9 are served from it. */
    var cart: FakeDmgCart? = null

    /** Count of B3 (DMG_CART_WRITE_SRAM) commands received; must stay 0 during backups. */
    var b3Count = 0
        private set

    /** Number of F2 (cart power on) commands. */
    var powerOnCount = 0
        private set

    /** Fault injection for ROM (header) reads from the attached cartridge: bytes sent instead of the real ones. */
    var romReadFilter: ((ByteArray) -> ByteArray)? = null

    /** Every B3 request: (address, data as sent). */
    val ramWrites = mutableListOf<Pair<Int, ByteArray>>()

    /** The RAM read count when the first B3 arrived; null if no B3 was ever sent. */
    var ramReadCountAtFirstB3: Int? = null
        private set

    /** Fault injection for B3: (1-based request number, address, data sent) -> bytes the cartridge actually receives. */
    var ramWriteFilter: ((Int, Int, ByteArray) -> ByteArray)? = null

    /** Number of RAM-mode B1 reads served (address >= 0xA000). */
    var ramReadCount = 0
        private set

    /** Number of CLK_TOGGLE commands. */
    var clockToggles = 0
        private set

    /** Things the real firmware would choke on, e.g. a RAM read without access mode 3 or CS pulse. */
    val violations = mutableListOf<String>()

    /**
     * Optional fault injection for RAM reads: (1-based RAM read number, address, data) -> bytes to
     * send. Return a shorter array to simulate a short reply.
     */
    var ramReadFilter: ((Int, Int, ByteArray) -> ByteArray)? = null

    private var address = 0

    init {
        link.onWrite = { data ->
            buf += data
            process()
        }
    }

    private fun reply(vararg v: Int) = reply(bytes(*v))

    private fun reply(b: ByteArray) {
        if (swallowReplies > 0) {
            swallowReplies--
            return
        }
        link.enqueue(b)
    }

    private fun need(n: Int): Boolean = buf.size >= n

    private fun consume(n: Int): ByteArray = buf.copyOf(n).also { buf = buf.copyOfRange(n, buf.size) }

    private fun u32(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xFF) }

    private fun process() {
        while (buf.isNotEmpty()) {
            when (val op = buf[0].toInt() and 0xFF) {
                0xA1 -> { consume(1); reply(fwReply) }
                0xA2, 0xA3, 0xA8, 0xAC, 0xB4, 0xC9 -> { consume(1); reply(1) }
                0xA4, 0xA5 -> {
                    consume(1)
                    val newVoltage = if (op == 0xA4) "3.3V" else "5V"
                    // Re-asserting the same voltage while powered is harmless; a change is not.
                    if (powered && newVoltage != voltage) voltageChangedWhilePowered = true
                    voltage = newVoltage
                    reply(1)
                }
                0xA6 -> {
                    if (!need(10)) return
                    val c = consume(10)
                    setVarLog += c
                    val key = (c[1].toInt() to u32(c, 2).toInt())
                    val value = u32(c, 6)
                    vars[key] = value
                    if (key == (4 to 0)) {
                        address = value.toInt()
                        romPos = (value * 1).toInt()
                        savePos = value.toInt()
                    }
                    reply(1)
                }
                0xAD -> {
                    if (!need(6)) return
                    val c = consume(6)
                    val v = vars[c[1].toInt() to u32(c, 2).toInt()] ?: 0L
                    reply(bytes((v shr 24).toInt(), (v shr 16).toInt(), (v shr 8).toInt(), v.toInt()))
                }
                0xF2 -> { consume(1); powered = true; powerOnCount++; voltageAtPowerOn += voltage; reply(1) }
                0xF3 -> { consume(1); powered = false; reply(1) }
                0xF4 -> { consume(1); reply(if (powered) 1 else 0) }
                0xFE -> {
                    if (!need(2)) return
                    val c = consume(2)
                    reply(c[1].toInt().inv() and 0xFF)
                }
                0xB2 -> {
                    if (!need(6)) return
                    val c = consume(6)
                    cart?.write(u32(c, 1).toInt(), c[5].toInt() and 0xFF)
                    reply(1)
                }
                0xB3 -> {
                    // B3 is followed by TRANSFER_SIZE data bytes in a second write (spec 3.1).
                    val n = (vars[2 to 0] ?: 0L).toInt()
                    if (!need(1 + n)) return
                    consume(1)
                    var data = consume(n)
                    b3Count++
                    if (ramReadCountAtFirstB3 == null) ramReadCountAtFirstB3 = ramReadCount
                    val mode = vars[1 to 1] ?: 0L
                    val writeCs = vars[1 to 9] ?: 0L
                    if (mode != 4L || writeCs != 1L) violations += "RAM write at 0x%X with mode $mode, write CS pulse $writeCs".format(address)
                    if (address < 0xA000 || address + n > 0xC000) violations += "RAM write outside 0xA000-0xBFFF at 0x%X+$n".format(address)
                    ramWrites += address to data.copyOf()
                    data = ramWriteFilter?.invoke(ramWrites.size, address, data) ?: data
                    cart?.let { c -> data.forEachIndexed { i, b -> c.write(address + i, b.toInt() and 0xFF) } }
                    address += n
                    reply(1)
                }
                0xA9 -> {
                    if (!need(5)) return
                    consume(5)
                    clockToggles++
                    reply(1)
                }
                0xB1 -> if (cart != null) {
                    consume(1)
                    val c = cart!!
                    val n = (vars[2 to 0] ?: 0L).toInt()
                    val mode = vars[1 to 1] ?: 0L
                    val readCs = vars[1 to 8] ?: 0L
                    val ram = address >= 0xA000
                    if (ram && (mode != 3L || readCs != 1L)) violations += "RAM read at 0x%X with mode $mode, read CS pulse $readCs".format(address)
                    if (!ram && mode != 1L) violations += "ROM read at 0x%X with access mode $mode".format(address)
                    var data = ByteArray(n) { c.read(address + it).toByte() }
                    if (!ram) data = romReadFilter?.invoke(data) ?: data
                    if (ram) {
                        ramReadCount++
                        data = ramReadFilter?.invoke(ramReadCount, address, data) ?: data
                    }
                    address += n
                    reply(data)
                } else {
                    consume(1)
                    val n = (vars[2 to 0] ?: 0L).toInt()
                    reply(ByteArray(n) { rom.getOrElse(romPos + it) { 0xFF.toByte() } })
                    romPos += n
                }
                0xC1 -> {
                    consume(1)
                    val n = (vars[2 to 0] ?: 0L).toInt()
                    reply(ByteArray(n) { rom.getOrElse(romPos + it) { 0xFF.toByte() } })
                    romPos += n
                }
                0xC3 -> {
                    consume(1)
                    val n = (vars[2 to 0] ?: 0L).toInt()
                    reply(ByteArray(n) { saveWindow.getOrElse(savePos + it) { 0xFF.toByte() } })
                    savePos += n
                }
                else -> error("FakeFirmware: unknown opcode 0x%02X".format(op))
            }
        }
    }
}

package com.therealnox.gbflash.protocol

import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import kotlin.random.Random

/**
 * Blocking command layer (spec sections 3, 4 and 5.1). Not thread-safe: [DeviceSession] serialises
 * every call. Assumes fw>=12 with cart power control (the only configuration milestone 1 supports).
 */
internal class Commands(
    private val transport: Transport,
    val firmware: FirmwareInfo,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    /** Platform the firmware is currently configured for, or null if none selected yet. */
    var platform: CartPlatform? = null
        private set

    // ---- variables ---------------------------------------------------------------------------

    /** `SET_VARIABLE`: `A6 <size> <key u32> <value u32>`, always 10 bytes, with resync (spec 3.1). */
    fun setVar(v: FwVar, value: Long) {
        transport.tryWrite(encodeSetVar(v, value))
    }

    /** `GET_VARIABLE`: `AD <size> <key u32>` -> u32 BE. */
    fun getVar(v: FwVar): Long {
        transport.write(byteArrayOf(Op.GET_VARIABLE.toByte(), (v.width / 8).toByte(), *int32(v.key.toLong())))
        val r = transport.readExact(4)
        return r.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    }

    // ---- liveness ----------------------------------------------------------------------------

    /** `FE <rand>` -> `~rand & 0xFF` (fw>=15 only). */
    fun ping() {
        val rnd = random.nextInt(256)
        transport.write(byteArrayOf(Op.PING.toByte(), rnd.toByte()))
        val reply = transport.readByte()
        val expected = rnd.inv() and 0xFF
        if (reply != expected) {
            throw UnexpectedResponseException("PING mismatch: sent 0x%02X, got 0x%02X, expected 0x%02X".format(rnd, reply, expected))
        }
    }

    /** `IsConnected` + `CheckActive` (spec 1.6): drain stale input, then PING (fw>=15) or read CART_MODE. */
    fun checkActive() {
        transport.drainInput()
        if (firmware.fwVer >= 15) {
            ping()
        } else {
            val mode = getVar(FwVar.CART_MODE)
            if (mode > 2) throw UnexpectedResponseException("CART_MODE reads $mode, device not responding sanely")
        }
    }

    // ---- power / mode ------------------------------------------------------------------------

    private fun requirePowerControl() {
        if (!firmware.cartPowerCtrl) {
            throw ProtocolException(
                "This board reports no cartridge power control; the SetPin fallback path is not implemented in milestone 1",
            )
        }
    }

    /** `SetAutoPowerOff(0)` (spec 4.4): disables the firmware's auto power-off. */
    fun disableAutoPowerOff() {
        setVar(FwVar.AUTO_POWEROFF_ENABLED, 0)
        setVar(FwVar.AUTO_POWEROFF_TIME, 0)
    }

    fun cartPowerOff() {
        transport.writeAck(Op.CART_PWR_OFF)
    }

    fun isCartPowered(): Boolean {
        transport.write(Op.QUERY_CART_PWR)
        return transport.readByte() != 0
    }

    /** `CartPowerOn` (spec 4.3). Does nothing if the cart is already powered. */
    fun cartPowerOn() {
        val p = platform ?: throw ProtocolException("Select GB/GBC or GBA before powering the cartridge")
        if (isCartPowered()) return
        transport.writeAck(p.modeOpcode)
        transport.writeAck(Op.CART_PWR_ON)
        // FlashGBX only logs a warning if the confirmation query does not say "on".
        isCartPowered()
        when (p) {
            CartPlatform.GB_GBC -> transport.writeAck(Op.DMG_MBC_RESET)
            CartPlatform.GBA -> transport.writeAck(Op.AGB_BOOTUP_SEQUENCE)
        }
    }

    /**
     * `SetMode` (spec 4.1). Deviation from FlashGBX: the cartridge is powered OFF first, so the
     * supply voltage is only ever changed while the cart is unpowered, and powered on afterwards
     * at the voltage that belongs to [target].
     */
    fun selectMode(target: CartPlatform) {
        requirePowerControl()
        cartPowerOff()
        platform = null
        transport.writeAck(target.modeOpcode)
        transport.writeAck(target.voltageOpcode)
        when (target) {
            CartPlatform.GB_GBC -> setVar(FwVar.DMG_READ_METHOD, 1)
            CartPlatform.GBA -> setVar(FwVar.AGB_READ_METHOD, 2)
        }
        setVar(FwVar.CART_MODE, target.cartModeValue)
        if (target == CartPlatform.GBA) setVar(FwVar.AGB_IRQ_ENABLED, 0)
        platform = target
        setVar(FwVar.ADDRESS, 0)
        cartPowerOn()
    }

    /** Safe shutdown (spec 1.6 Close): auto power-off off, cart power off. */
    fun powerDown() {
        setVar(FwVar.AUTO_POWEROFF_TIME, 0)
        cartPowerOff()
        platform = null
    }

    /** Restricted cartridge access for save backups (no SRAM writes possible). */
    internal fun dmgCartAccess() = DmgCartAccess(this, transport)

    /** The SRAM write path. Only [DeviceSession.restoreDmgSave] may call this. */
    internal fun dmgCartWriter() = DmgCartWriteAccess(this, transport)

    // ---- header ------------------------------------------------------------------------------

    /**
     * `ReadHeader` (spec 5.1 / 5.2 / 5.5). Without writing to cartridge memory. The caller must have
     * run [selectMode] for [target]; the voltage is re-asserted from the platform, never from a parameter.
     */
    fun readHeader(target: CartPlatform): CartHeader {
        check(platform == target) { "Mode not selected for $target" }
        checkActive()
        cartPowerOn()
        transport.writeAck(Op.DISABLE_PULLUPS)
        transport.writeAck(target.voltageOpcode)
        // AGB: FlashGBX writes 0xFF to ROM address 0 and re-reads when the logo is bad
        // (LK:1172-1175). That is a cart write, so it is intentionally not done.
        return when (target) {
            CartPlatform.GB_GBC -> readDmgHeader()
            CartPlatform.GBA -> AgbHeader.parse(readRom(CartPlatform.GBA, HEADER_LEN))
        }
    }

    private fun readDmgHeader(): DmgHeader {
        transport.writeAck(Op.DMG_MBC_RESET)
        setVar(FwVar.DMG_READ_CS_PULSE, 0)
        setVar(FwVar.DMG_WRITE_CS_PULSE, 0)
        var header = DmgHeader.parse(readRom(CartPlatform.GB_GBC, HEADER_LEN))
        if (!header.logoValid && !header.likelyNoCart) {
            // Spec 5.2: logo invalid -> re-read once with 0x280 bytes and re-parse.
            header = DmgHeader.parse(readRom(CartPlatform.GB_GBC, 0x280))
        }
        if (!header.logoValid || !header.headerChecksumValid) {
            // A cartridge swapped while still powered often reads garbage once. Power-cycle and retry once.
            log("Header invalid, power-cycling cartridge and retrying")
            transport.writeAck(Op.SET_ADDR_AS_INPUTS)
            cartPowerOff()
            transport.sleep(POWER_CYCLE_WAIT_MS)
            cartPowerOn() // mode opcode, power on, mapper reset
            transport.writeAck(Op.DISABLE_PULLUPS)
            transport.writeAck(CartPlatform.GB_GBC.voltageOpcode)
            transport.writeAck(Op.DMG_MBC_RESET)
            setVar(FwVar.DMG_READ_CS_PULSE, 0)
            setVar(FwVar.DMG_WRITE_CS_PULSE, 0)
            header = DmgHeader.parse(readRom(CartPlatform.GB_GBC, HEADER_LEN))
            if (!header.logoValid && !header.likelyNoCart) header = DmgHeader.parse(readRom(CartPlatform.GB_GBC, 0x280))
        }
        transport.writeAck(Op.SET_ADDR_AS_INPUTS)
        return header
    }

    /** `ReadROM(0, length)` with 64-byte requests (spec 5.1). [length] must be a multiple of 64. */
    private fun readRom(target: CartPlatform, length: Int): ByteArray {
        require(length % ROM_CHUNK == 0)
        setVar(FwVar.TRANSFER_SIZE, ROM_CHUNK.toLong())
        setVar(FwVar.ADDRESS, 0)
        val readOp = when (target) {
            CartPlatform.GB_GBC -> {
                setVar(FwVar.DMG_ACCESS_MODE, 1)
                Op.DMG_CART_READ
            }
            CartPlatform.GBA -> Op.AGB_CART_READ
        }
        val out = ByteArray(length)
        for (pos in 0 until length step ROM_CHUNK) {
            transport.write(readOp)
            transport.readExact(ROM_CHUNK).copyInto(out, pos)
        }
        return out
    }

    /**
     * Reads the 64 KiB GBA save window with `C3` (read only; no bank switch, no Flash commands).
     * Relies on the firmware advancing ADDRESS after each C3 (spec 3.1 assumption, unverified).
     */
    fun readAgbSaveWindow(): ByteArray {
        check(platform == CartPlatform.GBA) { "GBA mode not selected" }
        checkActive()
        cartPowerOn()
        transport.writeAck(CartPlatform.GBA.voltageOpcode)
        setVar(FwVar.TRANSFER_SIZE, SAVE_CHUNK.toLong())
        setVar(FwVar.ADDRESS, 0)
        val out = ByteArray(SAVE_WINDOW)
        for (pos in 0 until SAVE_WINDOW step SAVE_CHUNK) {
            transport.write(Op.AGB_CART_READ_SRAM)
            transport.readExact(SAVE_CHUNK).copyInto(out, pos)
        }
        return out
    }

    companion object {
        const val HEADER_LEN = 0x180
        private const val POWER_CYCLE_WAIT_MS = 300L
        private const val ROM_CHUNK = 64
        private const val SAVE_CHUNK = 0x1000
        const val SAVE_WINDOW = 0x10000

        fun encodeSetVar(v: FwVar, value: Long): ByteArray =
            byteArrayOf(Op.SET_VARIABLE.toByte(), (v.width / 8).toByte(), *int32(v.key.toLong()), *int32(value))

        private fun int32(x: Long): ByteArray = byteArrayOf(
            (x shr 24).toByte(),
            (x shr 16).toByte(),
            (x shr 8).toByte(),
            x.toByte(),
        )
    }
}

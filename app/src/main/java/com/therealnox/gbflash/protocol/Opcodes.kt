package com.therealnox.gbflash.protocol

/** Command opcodes (spec 3.1, LK:31-80). Only the ones used by milestone 1. */
internal object Op {
    const val QUERY_FW_INFO = 0xA1
    const val SET_MODE_AGB = 0xA2
    const val SET_MODE_DMG = 0xA3
    const val SET_VOLTAGE_3_3V = 0xA4
    const val SET_VOLTAGE_5V = 0xA5
    const val SET_VARIABLE = 0xA6
    const val SET_ADDR_AS_INPUTS = 0xA8
    const val DISABLE_PULLUPS = 0xAC
    const val GET_VARIABLE = 0xAD
    const val DMG_CART_READ = 0xB1
    const val DMG_MBC_RESET = 0xB4
    const val AGB_CART_READ = 0xC1
    const val AGB_CART_READ_SRAM = 0xC3
    const val AGB_BOOTUP_SEQUENCE = 0xC9
    const val CART_PWR_ON = 0xF2
    const val CART_PWR_OFF = 0xF3
    const val QUERY_CART_PWR = 0xF4
    const val PING = 0xFE
}

/** Firmware variables (spec 3.2, LK:82-110). [width] is in bits; the wire size byte is width / 8. */
internal enum class FwVar(val width: Int, val key: Int) {
    ADDRESS(32, 0x00),
    AUTO_POWEROFF_TIME(32, 0x01),
    TRANSFER_SIZE(16, 0x00),
    CART_MODE(8, 0x00),
    DMG_ACCESS_MODE(8, 0x01),
    DMG_READ_CS_PULSE(8, 0x08),
    DMG_WRITE_CS_PULSE(8, 0x09),
    DMG_READ_METHOD(8, 0x0B),
    AGB_READ_METHOD(8, 0x0C),
    AUTO_POWEROFF_ENABLED(8, 0x0F),
    AGB_IRQ_ENABLED(8, 0x10),
}

/**
 * The cartridge platform the user explicitly picked. This is the ONLY place that maps a platform to
 * a mode opcode and a supply voltage, and nothing else in the app can send A4/A5/A2/A3, so a GBA
 * session can never be put at 5 V (spec 4.2).
 */
enum class CartPlatform(
    val label: String,
    internal val modeOpcode: Int,
    internal val voltageOpcode: Int,
    internal val cartModeValue: Long,
) {
    GB_GBC("GB / GBC (5 V)", Op.SET_MODE_DMG, Op.SET_VOLTAGE_5V, 1),
    GBA("GBA (3.3 V)", Op.SET_MODE_AGB, Op.SET_VOLTAGE_3_3V, 2),
}

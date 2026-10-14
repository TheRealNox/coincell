package com.therealnox.gbflash

val DMG_LOGO: ByteArray = hex(
    "CEED6666CC0D000B03730083000C000D0008111F8889000EDCCC6EE6DDDDD999BBBB67636E0EECCCDDDC999FBBB9333E",
)

val AGB_LOGO: ByteArray = hex(
    "24FFAE51699AA2213D84820A84E409AD11248B98C0817F21A352BE199309CE2010464A4AF82731EC58C7E83382E3CEBF85F4DF94CE4B09C194568AC0" +
        "1372A7FC9F844D73A3CA9A615897A327FC039876231DC7610304AE56BF38840040A70EFDFF52FE036F9530F197FBC08560D68025A963BE03014E38" +
        "E2F9A234FFBB3E0344780090CB88113A9465C07C6387F03CAFD625E48B380AAC7221D4F807",
)

/** Builds a 0x180-byte DMG header with a correct logo and header checksum. */
fun dmgHeader(
    title: ByteArray = "TESTGAME".toByteArray(),
    cgbFlag: Int = 0,
    cartType: Int = 0x1B,
    romCode: Int = 1,
    ramCode: Int = 3,
    logo: ByteArray = DMG_LOGO,
): ByteArray {
    val b = ByteArray(0x180)
    logo.copyInto(b, 0x104)
    title.copyInto(b, 0x134, 0, minOf(title.size, 16))
    if (cgbFlag != 0) b[0x143] = cgbFlag.toByte()
    b[0x147] = cartType.toByte()
    b[0x148] = romCode.toByte()
    b[0x149] = ramCode.toByte()
    var x = 0
    for (i in 0x134..0x14C) x = (x - (b[i].toInt() and 0xFF) - 1) and 0xFF
    b[0x14D] = x.toByte()
    return b
}

/** Builds a 0x180-byte GBA header with a correct logo, 0x96 byte and header checksum. */
fun agbHeader(
    title: String = "TESTGBA",
    code: String = "ABCD",
    maker: String = "01",
    marker: Int? = null,
    logo: ByteArray = AGB_LOGO,
): ByteArray {
    val b = ByteArray(0x180)
    logo.copyInto(b, 0x04)
    title.toByteArray().copyInto(b, 0xA0)
    code.toByteArray().copyInto(b, 0xAC)
    maker.toByteArray().copyInto(b, 0xB0)
    b[0xB2] = 0x96.toByte()
    if (marker != null) {
        b[0xBE] = 0x4C
        b[0xBF] = marker.toByte()
    }
    var sum = 0
    for (i in 0xA0..0xBC) sum += b[i].toInt() and 0xFF
    b[0xBD] = ((-sum - 0x19) and 0xFF).toByte()
    return b
}

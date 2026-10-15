package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.gba.Sav3

/**
 * Builds empty-but-valid Generation 3 saves from scratch: the 14 sectors of save slot 0 with their ids, a save
 * counter and checksums, the small block marked the way PKHeX tells the games apart. Slot 1 stays blank.
 */
object SyntheticSaves3 {
    const val SECURITY_KEY = 0x1234ABCD

    fun build(game: Game, japanese: Boolean = false, trainerName: String = "ASH"): ByteArray {
        val data = ByteArray(Sav3.SIZE_RAW)
        val smallOfs = 0 // sector id 0 is the first sector of slot 0
        for (i in 0 until 14) {
            val ofs = i * 0x1000
            data[ofs + 0xFF4] = i.toByte()
            data[ofs + 0xFF8] = 0x25 // signature 0x08012025, not checked by PKHeX
            data[ofs + 0xFF9] = 0x20
            data[ofs + 0xFFA] = 0x01
            data[ofs + 0xFFB] = 0x08
            data[ofs + 0xFFC] = 1 // save counter
        }
        // Trainer name (0xFF-terminated), Japanese saves leave bytes 6-7 at zero, international ones 0xFF.
        val name = ByteArray(8) { 0xFF.toByte() }
        org.noxeng.coincell.pkcore.text.StringConverter3.setString(name, 0, if (japanese) 5 else 7, trainerName, 7, if (japanese) 1 else 2)
        if (japanese) {
            name[6] = 0
            name[7] = 0
        }
        name.copyInto(data, smallOfs)
        data[smallOfs + 0xA] = 0x39 // TID 12345
        data[smallOfs + 0xB] = 0x30
        data[smallOfs + 0xC] = 0x31 // SID 54321
        data[smallOfs + 0xD] = 0xD4.toByte()
        when (game) {
            Game.FIRERED_LEAFGREEN -> {
                data[smallOfs + 0xAC] = 1
                putU32(data, smallOfs + 0xF20, SECURITY_KEY.toLong())
            }
            Game.EMERALD -> {
                putU32(data, smallOfs + 0xAC, SECURITY_KEY.toLong())
                data[smallOfs + 0xA00] = 1 // data past the R/S structure
            }
            Game.RUBY_SAPPHIRE -> Unit
            else -> error("Generation 3 only")
        }
        // Box names "BOX 1".."BOX 14" in the storage object (sectors 5..13): name area at 0x8344 of storage.
        for (b in 0 until 14) {
            val buf = ByteArray(9) { 0xFF.toByte() }
            org.noxeng.coincell.pkcore.text.StringConverter3.setString(buf, 0, 9, "BOX ${b + 1}", 8, 2)
            for (j in 0 until 9) {
                val storageOfs = 0x8344 + b * 9 + j
                val sector = 5 + storageOfs / 0xF80
                data[sector * 0x1000 + storageOfs % 0xF80] = buf[j]
            }
        }
        for (i in 0 until 14) {
            val ofs = i * 0x1000
            val chk = Sav3.checksum32(data, ofs, 0xF80)
            data[ofs + 0xFF6] = chk.toByte()
            data[ofs + 0xFF7] = (chk shr 8).toByte()
        }
        return data
    }

    fun putU32(data: ByteArray, ofs: Int, v: Long) {
        for (i in 0 until 4) data[ofs + i] = (v shr (8 * i)).toByte()
    }
}

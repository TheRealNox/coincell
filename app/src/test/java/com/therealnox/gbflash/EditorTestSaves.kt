package com.therealnox.gbflash

import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.pkcore.SaveFile
import com.therealnox.gbflash.pkcore.gb.Pk1
import com.therealnox.gbflash.pkcore.gb.Pk2
import org.junit.Assume
import java.io.File

/** Saves for the Pokemon editor tests: synthetic ones built through `:pkcore`'s public API, and the user's own (optional). */
object EditorTestSaves {
    val crystalGame = GameInfo("DMG", "PM_CRYSTAL", "DMG_PM-CRYSTAL_F2E2", "0x10 MBC3", 0x200000L, 0xF2E2, 0x26, "BYTF")
    val blueGame = GameInfo("DMG", "POKEMON BLUE", "DMG_POKEMON-BLUE_56A4", "0x1B MBC5", 0x100000L, 0x56A4, 0x9C, null)
    val sapphire = GameInfo("AGB", "POKEMON SAPP", "AGB_POKEMON-SAPP_AXPF_5C", "GBA", null, 0, 0x5C, "AXPF")

    /** An empty but valid international Crystal save (the two list headers `PKHeX` looks at), with checksums. */
    private fun blankCrystal(): ByteArray {
        val data = ByteArray(0x8000)
        for (ofs in listOf(0x2865, 0x2D10)) {
            data[ofs] = 0
            data[ofs + 1] = 0xFF.toByte()
        }
        return SaveFile.load(data, 2).export()
    }

    private fun blankBlue(): ByteArray {
        val data = ByteArray(0x8000)
        for (ofs in listOf(0x2F2C, 0x30C0)) {
            data[ofs] = 0
            data[ofs + 1] = 0xFF.toByte()
        }
        return SaveFile.load(data, 2).export()
    }

    /** A Crystal save: trainer GOLD, ID 54321, money 3000; party Typhlosion L37 + Pidgey L12; one Rattata in box 1. English. */
    fun crystal(): ByteArray {
        val save = SaveFile.load(blankCrystal(), 2)
        save.updateTrainer(save.trainer.copy(name = "GOLD", id = 54321, money = 3000, rivalName = "SILVER"))
        save.setPokemon(com.therealnox.gbflash.pkcore.SlotRef.Party(0), pk2(157, 37, "TYPHLOSION"), updatePokedex = true)
        save.setPokemon(com.therealnox.gbflash.pkcore.SlotRef.Party(1), pk2(16, 12, "PIDGEY"), updatePokedex = true)
        save.setPokemon(com.therealnox.gbflash.pkcore.SlotRef.Box(0, 0), pk2(19, 4, "RATTATA"), updatePokedex = true)
        save.setPocket(com.therealnox.gbflash.pkcore.PocketType.ITEMS, listOf(com.therealnox.gbflash.pkcore.ItemStack(18, 5)))
        return save.export()
    }

    fun blue(): ByteArray {
        val save = SaveFile.load(blankBlue(), 2)
        save.updateTrainer(save.trainer.copy(name = "RED", id = 12345, money = 500))
        save.setPokemon(com.therealnox.gbflash.pkcore.SlotRef.Party(0), pk1(6, 36, "CHARIZARD"), updatePokedex = true)
        return save.export()
    }

    fun pk2(species: Int, level: Int, nick: String): Pk2 {
        val pk = Pk2(false)
        pk.fallbackLanguage = 2
        pk.species = species
        pk.level = level
        pk.trainerId = 54321
        pk.otName = "GOLD"
        pk.dv16 = 0xFAAA
        pk.friendship = 70
        pk.setMove(0, 33)
        pk.setMovePp(0, 35)
        pk.setMove(1, 98)
        pk.setMovePp(1, 30)
        pk.setNotNicknamed(2)
        pk.nickname = nick
        pk.resetPartyStats()
        return pk
    }

    fun pk1(species: Int, level: Int, nick: String): Pk1 {
        val pk = Pk1(false)
        pk.fallbackLanguage = 2
        pk.species = species
        pk.level = level
        pk.trainerId = 12345
        pk.otName = "RED"
        pk.dv16 = 0xFAAA
        pk.setMove(0, 33)
        pk.setMovePp(0, 35)
        pk.setNotNicknamed(2)
        pk.nickname = nick
        pk.resetPartyStats()
        return pk
    }

    // ---- the user's own saves (git-ignored, never copied into a tracked path) -----------------------------------------
    private val localDir: File? = listOf(File("../pkcore/src/test/fixtures-local"), File("pkcore/src/test/fixtures-local"))
        .firstOrNull { it.isDirectory }

    private fun local(name: String): ByteArray? = localDir?.let { File(it, name) }?.takeIf { it.isFile }?.readBytes()

    /** Pokemon Blue, 32 KiB of raw SRAM; the test is skipped when the personal save is absent. */
    fun realBlueOrSkip(): ByteArray {
        val bytes = local("POKEMON BLUE.sav")
        Assume.assumeTrue("pkcore/src/test/fixtures-local/POKEMON BLUE.sav not present", bytes != null)
        return bytes!!
    }

    /** French Crystal: the file has a 48 byte RTC footer, which is stripped (first 0x8000 bytes). */
    fun realCrystalOrSkip(): ByteArray {
        val bytes = local("Pokemon - Version Cristal (France).sav")
        Assume.assumeTrue("pkcore/src/test/fixtures-local/Pokemon - Version Cristal (France).sav not present", bytes != null)
        return bytes!!.copyOf(0x8000)
    }

    /** The 48 byte clock footer of the French Crystal file, or null. */
    fun realCrystalFooter(): ByteArray? = local("Pokemon - Version Cristal (France).sav")?.takeIf { it.size >= 0x8030 }?.copyOfRange(0x8000, 0x8030)
}

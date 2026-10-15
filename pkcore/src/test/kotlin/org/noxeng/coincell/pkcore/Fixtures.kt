package org.noxeng.coincell.pkcore

import org.junit.Assume
import java.io.File

/**
 * The user's personal saves live in `pkcore/src/test/fixtures-local/` (git-ignored). Tests that need them
 * call [blueOrSkip] / [crystalOrSkip] / [sapphireOrSkip], which skip the test through JUnit `Assume` when the file is absent.
 */
object Fixtures {
    private val dir: File = listOf(File("src/test/fixtures-local"), File("pkcore/src/test/fixtures-local"))
        .firstOrNull { it.isDirectory } ?: File("src/test/fixtures-local")

    private fun load(name: String): ByteArray? = File(dir, name).takeIf { it.isFile }?.readBytes()

    /** Pokemon Blue (English), 32 KiB of raw SRAM. */
    fun blueOrSkip(): ByteArray {
        val bytes = load("POKEMON BLUE.sav")
        Assume.assumeTrue("fixtures-local/POKEMON BLUE.sav not present", bytes != null)
        return bytes!!
    }

    /** French Crystal; the file has a 48-byte RTC footer which is stripped (first 0x8000 bytes). */
    fun crystalOrSkip(): ByteArray {
        val bytes = load("Pokemon - Version Cristal (France).sav")
        Assume.assumeTrue("fixtures-local/Pokemon - Version Cristal (France).sav not present", bytes != null)
        return bytes!!.copyOf(0x8000)
    }

    /** French Sapphire, 128 KiB of Flash; the file has FlashGBX's 16-byte RTC footer, which is stripped. */
    fun sapphireOrSkip(): ByteArray {
        val bytes = load("Pokemon - Version Saphir (France).sav")
        Assume.assumeTrue("fixtures-local/Pokemon - Version Saphir (France).sav not present", bytes != null)
        return bytes!!.copyOf(0x20000)
    }
}

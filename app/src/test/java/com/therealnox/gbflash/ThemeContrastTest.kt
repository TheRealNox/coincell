package com.therealnox.gbflash

import com.therealnox.gbflash.ui.theme.ColorMath
import com.therealnox.gbflash.ui.theme.ColorPair
import com.therealnox.gbflash.ui.theme.GbfPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/UI_DESIGN.md 5.9: custom tints are checked at build time by a unit test of the contrast ratio. */
class ThemeContrastTest {
    private val primaries = listOf(
        0xFF5B5FC7.toInt(), 0xFF6750A4.toInt(), 0xFF006C4C.toInt(),
        0xFFB3261E.toInt(), 0xFF00639B.toInt(), 0xFF8D5000.toInt(),
    )

    private fun check(name: String, p: ColorPair) {
        val c = ColorMath.contrast(p.container, p.onContainer)
        assertTrue("$name contrast $c", c >= 4.5)
    }

    @Test
    fun customColours_haveAtLeast4_5to1_inLightAndDark() {
        for (primary in primaries) for (dark in listOf(true, false)) {
            check("gb dark=$dark", GbfPalette.gb(primary, dark))
            check("gbc dark=$dark", GbfPalette.gbc(primary, dark))
            check("gba dark=$dark", GbfPalette.gba(primary, dark))
            check("success dark=$dark", GbfPalette.success(primary, dark))
        }
        for (dark in listOf(true, false)) check("powered dark=$dark", GbfPalette.powered(dark))
    }

    @Test
    fun contrastMath_matchesWcagReferenceValues() {
        assertEquals(21.0, ColorMath.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, ColorMath.contrast(0xFF123456.toInt(), 0xFF123456.toInt()), 0.0001)
        assertEquals(0.0, ColorMath.deltaE(0xFF336699.toInt(), 0xFF336699.toInt()), 0.0001)
    }
}

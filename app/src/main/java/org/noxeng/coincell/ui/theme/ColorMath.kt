package org.noxeng.coincell.ui.theme

import kotlin.math.pow
import kotlin.math.sqrt

/** Plain 0xAARRGGBB colour arithmetic, kept free of Compose so it is unit tested on the JVM (docs/UI_DESIGN.md 5.9). */
object ColorMath {
    fun lerp(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun lin(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * lin((argb ushr 16) and 0xFF) + 0.7152 * lin((argb ushr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)

    /** WCAG contrast ratio, 1.0 to 21.0. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** CIE76 colour difference (sRGB, D65). Below about 15 two tints are too alike to tell apart. */
    fun deltaE(a: Int, b: Int): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    private fun lab(argb: Int): Triple<Double, Double, Double> {
        val r = lin((argb ushr 16) and 0xFF)
        val g = lin((argb ushr 8) and 0xFF)
        val bl = lin(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * bl) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * bl
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * bl) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
}

/** A container colour with the colour of the content on it. */
data class ColorPair(val container: Int, val onContainer: Int)

/**
 * The fixed hues of the design (docs/UI_DESIGN.md 5.1), blended toward the scheme's primary so they sit
 * with the wallpaper colours, plus the success and the cart-powered (attention) colours.
 */
object GbfPalette {
    const val GB_BASE = 0xFF6B8E23.toInt()
    const val GBC_BASE = 0xFF7E57C2.toInt()
    const val GBA_BASE = 0xFF3F51B5.toInt()
    const val SUCCESS_BASE = 0xFF2E7D32.toInt()
    const val POWERED_BASE = 0xFFFFB300.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** How far the platform hues drift toward the primary colour. */
    const val HARMONIZE = 0.3f

    /** Below this colour difference two platform tints count as the same hue. */
    const val MIN_DELTA_E = 15.0

    fun tinted(base: Int, primary: Int, dark: Boolean, amount: Float = HARMONIZE): ColorPair {
        val h = ColorMath.lerp(base, primary, amount)
        return if (dark) {
            ColorPair(ColorMath.lerp(h, BLACK, 0.62f), ColorMath.lerp(h, WHITE, 0.82f))
        } else {
            ColorPair(ColorMath.lerp(h, WHITE, 0.78f), ColorMath.lerp(h, BLACK, 0.68f))
        }
    }

    fun gb(primary: Int, dark: Boolean) = tinted(GB_BASE, primary, dark)

    fun gbc(primary: Int, dark: Boolean) = tinted(GBC_BASE, primary, dark)

    fun gba(primary: Int, dark: Boolean) = tinted(GBA_BASE, primary, dark)

    fun success(primary: Int, dark: Boolean) = tinted(SUCCESS_BASE, primary, dark, 0.15f)

    /** The attention colour of a powered cartridge. Not taken from the wallpaper so it is always distinct. */
    fun powered(dark: Boolean): ColorPair = if (dark) {
        ColorPair(ColorMath.lerp(POWERED_BASE, BLACK, 0.6f), ColorMath.lerp(POWERED_BASE, WHITE, 0.85f))
    } else {
        ColorPair(ColorMath.lerp(POWERED_BASE, WHITE, 0.5f), ColorMath.lerp(POWERED_BASE, BLACK, 0.8f))
    }

    /** True if the three platform tints are all separable by [MIN_DELTA_E]. */
    fun platformsDistinct(primary: Int, dark: Boolean): Boolean {
        val c = listOf(gb(primary, dark), gbc(primary, dark), gba(primary, dark)).map { it.container }
        return ColorMath.deltaE(c[0], c[1]) >= MIN_DELTA_E &&
            ColorMath.deltaE(c[0], c[2]) >= MIN_DELTA_E &&
            ColorMath.deltaE(c[1], c[2]) >= MIN_DELTA_E
    }
}

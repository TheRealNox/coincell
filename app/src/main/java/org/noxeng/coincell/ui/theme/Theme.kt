package org.noxeng.coincell.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.noxeng.coincell.library.DisplayPlatform

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light"),
}

@Immutable
class ContentColors(val container: Color, val onContainer: Color) {
    constructor(p: ColorPair) : this(Color(p.container), Color(p.onContainer))
}

/** Semantic colours that Material 3 has no role for (docs/UI_DESIGN.md 5.1). */
@Immutable
class GbfExtendedColors(
    val gb: ContentColors,
    val gbc: ContentColors,
    val gba: ContentColors,
    val success: ContentColors,
    /** Attention colour: the cartridge is powered. */
    val powered: ContentColors,
) {
    fun platform(p: DisplayPlatform): ContentColors = when (p) {
        DisplayPlatform.GB -> gb
        DisplayPlatform.GBC -> gbc
        DisplayPlatform.GBA -> gba
    }
}

private fun Color.toArgb32(): Int =
    (0xFF shl 24) or ((red * 255f + 0.5f).toInt() shl 16) or ((green * 255f + 0.5f).toInt() shl 8) or (blue * 255f + 0.5f).toInt()

internal fun extendedColors(scheme: ColorScheme, dark: Boolean): GbfExtendedColors {
    val primary = scheme.primary.toArgb32()
    // Wallpaper colours can pull the three hues together; then identity falls back to three different
    // scheme roles. Every badge also carries its own text, so the design never depends on hue.
    val distinct = GbfPalette.platformsDistinct(primary, dark)
    val gb: ContentColors
    val gbc: ContentColors
    val gba: ContentColors
    if (distinct) {
        gb = ContentColors(GbfPalette.gb(primary, dark))
        gbc = ContentColors(GbfPalette.gbc(primary, dark))
        gba = ContentColors(GbfPalette.gba(primary, dark))
    } else {
        gb = ContentColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        gbc = ContentColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        gba = ContentColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    return GbfExtendedColors(
        gb = gb,
        gbc = gbc,
        gba = gba,
        success = ContentColors(GbfPalette.success(primary, dark)),
        powered = ContentColors(GbfPalette.powered(dark)),
    )
}

val LocalExtendedColors = staticCompositionLocalOf<GbfExtendedColors> { error("GbfTheme missing") }

object GbfTheme {
    val extended: GbfExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}

// Static fallback (Android 11 and older, or dynamic colour switched off): an indigo scheme from seed 5B5FC7.
private val StaticLight = lightColorScheme(
    primary = Color(0xFF4C50B8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF), onPrimaryContainer = Color(0xFF05006C),
    secondary = Color(0xFF5C5D72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E0F9), onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF78536B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD7F0), onTertiaryContainer = Color(0xFF2E1126),
    background = Color(0xFFFBF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC), onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC7C5D0),
)

private val StaticDark = darkColorScheme(
    primary = Color(0xFFBFC2FF), onPrimary = Color(0xFF1B1E8F),
    primaryContainer = Color(0xFF34379F), onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC5C4DD), onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559), onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFE8B9D5), onTertiary = Color(0xFF46263B),
    tertiaryContainer = Color(0xFF5E3C52), onTertiaryContainer = Color(0xFFFFD7F0),
    background = Color(0xFF131316), onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF131316), onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F9A), outlineVariant = Color(0xFF46464F),
)

/** Material 3 with Material You dynamic colour on Android 12+, plus the app's extended colours. */
@Composable
fun GbfTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> StaticDark
        else -> StaticLight
    }
    CompositionLocalProvider(LocalExtendedColors provides extendedColors(scheme, dark)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

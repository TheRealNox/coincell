package com.therealnox.gbflash.ui.settings

import android.content.Context
import com.therealnox.gbflash.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Appearance preferences. Deliberately nothing about the cartridge: the platform choice is never stored
 * (docs/UI_DESIGN.md principle 4).
 */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("gbflash_prefs", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean(KEY_DYNAMIC, true))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setDynamicColor(on: Boolean) {
        _dynamicColor.value = on
        prefs.edit().putBoolean(KEY_DYNAMIC, on).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
    }
}

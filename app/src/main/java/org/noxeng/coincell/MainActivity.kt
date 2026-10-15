package org.noxeng.coincell

import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import org.noxeng.coincell.ui.AppShell
import org.noxeng.coincell.ui.DeviceViewModel
import org.noxeng.coincell.ui.theme.GbfTheme
import org.noxeng.coincell.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val deviceViewModel: DeviceViewModel by viewModels()
    private var usbIntentTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GbfApp
        val startOnDevice = intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        setContent {
            val mode by app.prefs.themeMode.collectAsState()
            val dynamic by app.prefs.dynamicColor.collectAsState()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            // System bar icons follow the app theme, which can differ from the system setting.
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            GbfTheme(mode = mode, dynamicColor = dynamic) {
                AppShell(
                    device = deviceViewModel.controller,
                    store = app.libraryStore,
                    prefs = app.prefs,
                    versionName = app.versionName,
                    startOnDevice = startOnDevice,
                    usbIntentTick = usbIntentTick.intValue,
                )
            }
        }
        // Covers both a normal launch and a launch via USB_DEVICE_ATTACHED (the system then
        // already grants permission for the device).
        deviceViewModel.controller.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) usbIntentTick.intValue++
        deviceViewModel.controller.refresh()
    }
}

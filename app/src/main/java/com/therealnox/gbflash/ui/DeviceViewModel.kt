package com.therealnox.gbflash.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therealnox.gbflash.GbfApp
import com.therealnox.gbflash.device.DeviceController
import com.therealnox.gbflash.usb.UsbConnector
import com.therealnox.gbflash.usb.UsbReaderGateway

/**
 * The one app-scoped device/session holder (activity ViewModel). It owns the [DeviceController]; every screen
 * observes `controller.state`. Clearing it (the activity is finished) powers the cartridge down.
 */
class DeviceViewModel(app: Application) : AndroidViewModel(app) {
    private val gbf = app as GbfApp

    val controller = DeviceController(
        gateway = UsbReaderGateway(UsbConnector(app)),
        library = gbf.library,
        scope = viewModelScope,
        appVersion = gbf.versionName,
        onLibraryChanged = gbf.libraryStore::reload,
    )

    override fun onCleared() {
        controller.shutdown()
    }
}

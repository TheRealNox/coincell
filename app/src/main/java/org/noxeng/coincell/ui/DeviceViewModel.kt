package org.noxeng.coincell.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.noxeng.coincell.GbfApp
import org.noxeng.coincell.device.DeviceController
import org.noxeng.coincell.usb.UsbConnector
import org.noxeng.coincell.usb.UsbReaderGateway

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
        agbDatabase = gbf.agbDatabase,
        dmgDatabase = gbf.dmgDatabase,
    )

    override fun onCleared() {
        controller.shutdown()
    }
}

package com.therealnox.gbflash

import android.app.Application
import com.therealnox.gbflash.cart.AgbDatabase
import com.therealnox.gbflash.library.LibraryStore
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.ui.settings.AppPrefs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons that do not touch the reader: the app-private save library (`filesDir/saves/`), its
 * offline browser state, and the preferences. The reader session itself is owned by the activity-scoped
 * [com.therealnox.gbflash.ui.DeviceViewModel], so closing the app powers the cartridge down.
 */
class GbfApp : Application() {
    val library: SaveLibrary by lazy { SaveLibrary(File(filesDir, "saves")) }
    val libraryStore: LibraryStore by lazy { LibraryStore(library, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    val prefs: AppPrefs by lazy { AppPrefs(this) }

    /** FlashGBX GBA database (assets/db_agb.tsv, GPL-3.0). Empty if the asset cannot be read: detection then falls back to probing. */
    val agbDatabase: AgbDatabase by lazy {
        try {
            assets.open("db_agb.tsv").use { AgbDatabase.parse(it) }
        } catch (_: Exception) {
            AgbDatabase.EMPTY
        }
    }

    val versionName: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
}

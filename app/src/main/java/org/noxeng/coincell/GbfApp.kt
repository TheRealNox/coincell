package org.noxeng.coincell

import android.app.Application
import org.noxeng.coincell.cart.AgbDatabase
import org.noxeng.coincell.cart.DmgDatabase
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.sprites.SpriteStore
import org.noxeng.coincell.ui.settings.AppPrefs
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons that do not touch the reader: the app-private save library (`filesDir/saves/`), its
 * offline browser state, and the preferences. The reader session itself is owned by the activity-scoped
 * [org.noxeng.coincell.ui.DeviceViewModel], so closing the app powers the cartridge down.
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

    /** FlashGBX GB/GBC database (assets/db_dmg.tsv, GPL-3.0), for friendly default names. Empty if unreadable. */
    val dmgDatabase: DmgDatabase by lazy {
        try {
            assets.open("db_dmg.tsv").use { DmgDatabase.parse(it) }
        } catch (_: Exception) {
            DmgDatabase.EMPTY
        }
    }

    /** The optional Pokemon sprite pack (pkassets/): decoded sprites, or null results when the pack is not in the app. */
    val sprites: SpriteStore by lazy { SpriteStore(assets) }

    val versionName: String
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
}

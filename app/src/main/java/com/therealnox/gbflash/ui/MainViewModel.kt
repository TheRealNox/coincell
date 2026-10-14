package com.therealnox.gbflash.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.save.BackupCancelledException
import com.therealnox.gbflash.save.BackupPlan
import com.therealnox.gbflash.save.SaveCheck
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.DebugLog
import com.therealnox.gbflash.protocol.DeviceSession
import com.therealnox.gbflash.protocol.FirmwareInfo
import com.therealnox.gbflash.protocol.IncompatibleDeviceException
import com.therealnox.gbflash.usb.UsbConnector
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnectionStatus { NO_DEVICE, NEEDS_PERMISSION, CONNECTING, CONNECTED, FAILED }

data class UiState(
    val status: ConnectionStatus = ConnectionStatus.NO_DEVICE,
    val statusDetail: String? = null,
    val firmware: FirmwareInfo? = null,
    /** Explicit user choice; null until picked. Never inferred. */
    val platform: CartPlatform? = null,
    val busy: Boolean = false,
    val header: CartHeader? = null,
    val headerPlatform: CartPlatform? = null,
    val agbSaveHint: String? = null,
    val error: String? = null,
    /** Informational message (e.g. "Backup cancelled"). */
    val info: String? = null,
    /** True when the header read allows a save backup for this cartridge. */
    val backupAvailable: Boolean = false,
    /** Why a backup is not possible (unsupported mapper, no RAM, ...). */
    val backupUnavailableReason: String? = null,
    /** Bytes read / total bytes to read (twice the save size) while a backup runs. */
    val backupProgress: Pair<Long, Long>? = null,
    /** The backup that was just stored, shown as a summary. */
    val lastBackup: BackupEntry? = null,
    val lastBackupWarnings: List<String> = emptyList(),
    /** Library entries of the game in the inserted cartridge, newest first. */
    val backups: List<BackupEntry> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val debugLog = DebugLog()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val usb = UsbConnector(app)
    private var session: DeviceSession? = null
    private var currentDevice: UsbDevice? = null
    private var connecting = false
    private val library = SaveLibrary(File(app.filesDir, "saves"))
    private var plan: BackupPlan? = null
    private var game: GameInfo? = null
    private val cancelRequested = AtomicBoolean(false)
    private var pendingExport: Pair<BackupEntry, Boolean>? = null

    init {
        usb.start(
            onPermissionResult = { device, granted ->
                if (granted) {
                    connect(device)
                } else {
                    _state.update {
                        it.copy(status = ConnectionStatus.NEEDS_PERMISSION, statusDetail = "USB permission denied")
                    }
                }
            },
            onDetached = { device ->
                if (device.deviceId == currentDevice?.deviceId) onDetached()
            },
        )
    }

    /** Looks for the GBFlash and connects (asking for USB permission if needed). */
    fun refresh() {
        if (session != null || connecting) return
        val device = usb.findDevice()
        when {
            device == null -> _state.update {
                it.copy(status = ConnectionStatus.NO_DEVICE, statusDetail = "No CH340 (1A86:7523) found", error = null)
            }
            usb.hasPermission(device) -> connect(device)
            else -> {
                _state.update {
                    it.copy(status = ConnectionStatus.NEEDS_PERMISSION, statusDetail = "Waiting for USB permission", error = null)
                }
                usb.requestPermission(device)
            }
        }
    }

    private fun connect(device: UsbDevice) {
        if (session != null || connecting) return
        connecting = true
        _state.update {
            it.copy(status = ConnectionStatus.CONNECTING, statusDetail = "Connecting...", error = null, firmware = null)
        }
        viewModelScope.launch {
            try {
                debugLog.add("Opening ${device.deviceName} at ${UsbConnector.BAUD} baud")
                val link = withContext(Dispatchers.IO) { usb.open(device) }
                val s = DeviceSession.open(link, debugLog::add)
                session = s
                currentDevice = device
                _state.update {
                    it.copy(status = ConnectionStatus.CONNECTED, statusDetail = null, firmware = s.firmware)
                }
            } catch (e: IncompatibleDeviceException) {
                debugLog.add("Rejected: ${e.message}")
                _state.update {
                    it.copy(status = ConnectionStatus.FAILED, statusDetail = e.message, firmware = e.info)
                }
            } catch (e: Exception) {
                debugLog.add("Connect failed: $e")
                _state.update { it.copy(status = ConnectionStatus.FAILED, statusDetail = e.message ?: e.toString()) }
            } finally {
                connecting = false
            }
        }
    }

    fun selectPlatform(platform: CartPlatform) {
        _state.update {
            if (it.platform == platform) it else clearCartState(it).copy(platform = platform)
        }
        plan = null
        game = null
    }

    fun readHeader() {
        val s = session ?: return
        val platform = _state.value.platform ?: return
        if (_state.value.busy) return
        plan = null
        game = null
        _state.update { clearCartState(it).copy(busy = true) }
        viewModelScope.launch {
            try {
                debugLog.add("--- Read header as ${platform.label} ---")
                val header = s.readHeader(platform)
                var backups = emptyList<BackupEntry>()
                var available = false
                var reason: String? = null
                if (header is DmgHeader && platform == CartPlatform.GB_GBC) {
                    val p = BackupPlan.forHeader(header)
                    plan = p
                    available = p is BackupPlan.Ready
                    reason = (p as? BackupPlan.Unavailable)?.reason
                    val g = GameInfo.fromDmg(header)
                    game = g
                    backups = withContext(Dispatchers.IO) { library.list(g.key) }
                }
                _state.update {
                    it.copy(
                        header = header, headerPlatform = platform, backupAvailable = available,
                        backupUnavailableReason = reason, backups = backups,
                    )
                }
            } catch (e: Exception) {
                debugLog.add("Read header failed: $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun clearCartState(it: UiState) = it.copy(
        header = null, headerPlatform = null, agbSaveHint = null, error = null, info = null,
        backupAvailable = false, backupUnavailableReason = null, lastBackup = null,
        lastBackupWarnings = emptyList(), backups = emptyList(),
    )

    /**
     * Reads and verifies the save of the inserted GB / GBC cartridge into memory, then stores it in
     * the library. Only possible after a successful GB / GBC header read; never for GBA.
     */
    fun backupSave() {
        val s = session ?: return
        val st = _state.value
        val p = plan as? BackupPlan.Ready ?: return
        val g = game ?: return
        if (st.busy || st.platform != CartPlatform.GB_GBC || st.headerPlatform != CartPlatform.GB_GBC) return
        cancelRequested.set(false)
        _state.update { it.copy(busy = true, error = null, info = null, lastBackup = null, backupProgress = 0L to p.saveSize * 2L) }
        viewModelScope.launch {
            try {
                debugLog.add("--- Back up save (${p.mapperName}, ${p.saveSize} bytes) ---")
                val result = s.backupDmgSave(
                    plan = p,
                    isCancelled = cancelRequested::get,
                    onProgress = { done, total -> _state.update { it.copy(backupProgress = done to total) } },
                    log = debugLog::add,
                )
                val suspect = SaveCheck.suspectReason(result.data)
                val fw = s.firmware
                val entry = withContext(Dispatchers.IO) {
                    library.store(
                        g,
                        NewBackup(
                            save = result.data,
                            rtcFooter = result.rtc?.toFooter(),
                            rtcProbePresent = result.rtcProbePresent,
                            rtcDeclared = result.rtcDeclared,
                            firmware = fw.versionString + (fw.deviceName?.let { " ($it)" } ?: ""),
                            appVersion = appVersion(),
                            verified = true,
                            suspectReason = suspect,
                        ),
                    )
                }
                val all = withContext(Dispatchers.IO) { library.list(g.key) }
                debugLog.add("Backup stored: ${entry.id} CRC32 %08X".format(entry.crc32))
                _state.update { it.copy(lastBackup = entry, lastBackupWarnings = result.warnings, backups = all) }
            } catch (e: BackupCancelledException) {
                debugLog.add("Backup cancelled by user")
                _state.update { it.copy(info = "Backup cancelled. Nothing was stored; cartridge RAM was disabled again.") }
            } catch (e: Exception) {
                debugLog.add("Backup failed: $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false, backupProgress = null) }
            }
        }
    }

    /** Asks a running backup to stop; the cartridge cleanup still runs. */
    fun cancelBackup() = cancelRequested.set(true)

    fun deleteBackup(entry: BackupEntry) {
        val g = game ?: return
        viewModelScope.launch {
            try {
                val all = withContext(Dispatchers.IO) {
                    library.delete(entry)
                    library.list(g.key)
                }
                _state.update {
                    it.copy(backups = all, lastBackup = it.lastBackup?.takeIf { l -> l.id != entry.id })
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Delete failed: ${e.message}") }
            }
        }
    }

    /** Remembers what the next SAF "create document" result should contain. */
    fun beginExport(entry: BackupEntry, includeRtc: Boolean) {
        pendingExport = entry to includeRtc
    }

    fun exportFileName(): String = SaveCheck.exportFileName(game?.title ?: "save")

    /** Writes the pending export (save.sav [+ rtc.bin], spec 7 format) to the chosen document. */
    fun exportTo(uri: Uri?) {
        val (entry, withRtc) = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = library.exportBytes(entry, withRtc)
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Cannot open the chosen file")
                    out.use { it.write(bytes) }
                }
                _state.update { it.copy(info = "Exported ${entry.id}${if (withRtc && entry.rtcIncluded) " with RTC" else ""}.") }
            } catch (e: Exception) {
                debugLog.add("Export failed: $e")
                _state.update { it.copy(error = "Export failed: ${e.message}") }
            }
        }
    }

    private fun appVersion(): String = try {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    fun probeAgbSave() {
        val s = session ?: return
        if (_state.value.busy || _state.value.platform != CartPlatform.GBA) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                debugLog.add("--- Read-only GBA save window probe ---")
                val hint = s.probeAgbSaveReadOnly()
                _state.update { it.copy(agbSaveHint = hint.summary) }
            } catch (e: Exception) {
                debugLog.add("Probe failed: $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** User-initiated disconnect: powers the cartridge off, then closes the port. */
    fun disconnect() {
        val s = session ?: return
        session = null
        currentDevice = null
        viewModelScope.launch {
            s.close()
            debugLog.add("Disconnected (cart powered off)")
            _state.value = UiState(status = ConnectionStatus.NO_DEVICE, statusDetail = "Disconnected")
        }
    }

    private fun onDetached() {
        session?.abandon()
        session = null
        currentDevice = null
        debugLog.add("USB device detached")
        _state.value = UiState(status = ConnectionStatus.NO_DEVICE, statusDetail = "USB device detached")
    }

    override fun onCleared() {
        cancelRequested.set(true)
        usb.stop()
        val s = session
        session = null
        if (s != null) {
            // viewModelScope is already cancelled here; make a best effort to power the cart down.
            CoroutineScope(Dispatchers.IO).launch { s.close() }
        }
    }
}

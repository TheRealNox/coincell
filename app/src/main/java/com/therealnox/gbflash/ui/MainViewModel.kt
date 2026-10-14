package com.therealnox.gbflash.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.DmgMapper
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.BackupKind
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.restore.RestoreCoordinator
import com.therealnox.gbflash.restore.RestorePolicy
import com.therealnox.gbflash.restore.RestoreRequest
import com.therealnox.gbflash.restore.SafetyCopy
import com.therealnox.gbflash.save.BackupCancelledException
import com.therealnox.gbflash.save.BackupPlan
import com.therealnox.gbflash.save.RestorePhase
import com.therealnox.gbflash.save.SaveCheck
import com.therealnox.gbflash.save.SaveImport
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
    /** Entries of other cartridges of the same game (same title, other checksum), newest first. */
    val otherCartridgeBackups: List<BackupEntry> = emptyList(),
    /** The restore flow, or null when none is open. */
    val restore: RestoreUi? = null,
    /** The RTC question of a backup, shown as a Yes/No dialog. */
    val rtcPrompt: RtcPrompt? = null,
    /** The cartridge is powered (after a header read, backup or restore) and should be powered off before swapping. */
    val cartPowered: Boolean = false,
) {
    /**
     * Restore needs a connected reader in GB / GBC mode and a header read in this session, because the
     * review compares the backup with that header.
     */
    val restoreAvailable: Boolean
        get() = status == ConnectionStatus.CONNECTED && platform == CartPlatform.GB_GBC &&
            headerPlatform == CartPlatform.GB_GBC && header is DmgHeader && backupAvailable && !busy && restore == null
}

/** [beforeStart]: asked before the backup starts; otherwise a running backup waits for the answer. */
data class RtcPrompt(val beforeStart: Boolean)

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
    private var coordinator: RestoreCoordinator? = null
    private var rtcAnswer: java.util.concurrent.CompletableFuture<Boolean>? = null

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
        val changed = _state.value.platform != platform
        _state.update {
            if (it.platform == platform) it else clearCartState(it).copy(platform = platform, cartPowered = false)
        }
        plan = null
        game = null
        // Changing the cartridge type powers the cartridge off first (the voltage may only change unpowered).
        if (changed) powerOffCart(quiet = true)
    }

    /** "Power off cart (before swapping)": F3, keeps the connection. The shown header no longer applies. */
    fun powerOffCart(quiet: Boolean = false) {
        val s = session ?: return
        if (_state.value.busy || _state.value.restore != null) return
        viewModelScope.launch {
            try {
                s.powerOffCart()
                debugLog.add("Cartridge powered off")
                plan = null
                game = null
                _state.update {
                    clearCartState(it).copy(
                        cartPowered = false,
                        info = if (quiet) it.info else "Cartridge powered off. You can swap it now, then read the header again.",
                    )
                }
            } catch (e: Exception) {
                debugLog.add("Power off failed: $e")
                _state.update { it.copy(error = "Power off failed: ${e.message}") }
            }
        }
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
                var others = emptyList<BackupEntry>()
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
                    others = withContext(Dispatchers.IO) { library.listOtherCartridges(g) }
                }
                _state.update {
                    it.copy(
                        header = header, headerPlatform = platform, backupAvailable = available,
                        backupUnavailableReason = reason, backups = backups, otherCartridgeBackups = others,
                    )
                }
            } catch (e: Exception) {
                debugLog.add("Read header failed: $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false, cartPowered = true) }
            }
        }
    }

    private fun clearCartState(it: UiState) = it.copy(
        header = null, headerPlatform = null, agbSaveHint = null, error = null, info = null,
        backupAvailable = false, backupUnavailableReason = null, lastBackup = null,
        lastBackupWarnings = emptyList(), backups = emptyList(), otherCartridgeBackups = emptyList(),
        restore = null,
    )

    /**
     * Reads and verifies the save of the inserted GB / GBC cartridge into memory, then stores it in
     * the library. Only possible after a successful GB / GBC header read; never for GBA.
     */
    fun backupSave() {
        val p = plan as? BackupPlan.Ready ?: return
        val st = _state.value
        if (st.busy || st.restore != null || st.rtcPrompt != null) return
        // FlashGBX asks about the RTC before reading when the header declares a timer.
        if (p.rtcDeclared) {
            _state.update { it.copy(rtcPrompt = RtcPrompt(beforeStart = true)) }
        } else {
            runBackup(includeRtc = true)
        }
    }

    /** Answer of the RTC dialog (before the start, or while a backup waits after the RTC probe). */
    fun answerRtcPrompt(yes: Boolean) {
        val prompt = _state.value.rtcPrompt ?: return
        _state.update { it.copy(rtcPrompt = null) }
        if (prompt.beforeStart) runBackup(includeRtc = yes) else rtcAnswer?.complete(yes)
    }

    /** Closes the dialog without answering: a backup that has not started is dropped, a waiting one is answered No. */
    fun dismissRtcPrompt() {
        val prompt = _state.value.rtcPrompt ?: return
        _state.update { it.copy(rtcPrompt = null) }
        if (!prompt.beforeStart) rtcAnswer?.complete(false)
    }

    private fun runBackup(includeRtc: Boolean) {
        val s = session ?: return
        val st = _state.value
        val p = plan as? BackupPlan.Ready ?: return
        val g = game ?: return
        if (st.busy || st.restore != null || st.platform != CartPlatform.GB_GBC || st.headerPlatform != CartPlatform.GB_GBC) return
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
                    includeRtc = includeRtc,
                    askRtc = if (p.rtcDeclared) null else ::askRtcBlocking,
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

    /** Called on the I/O thread after the RTC probe found a clock the header did not declare. */
    private fun askRtcBlocking(): Boolean {
        val answer = java.util.concurrent.CompletableFuture<Boolean>()
        rtcAnswer = answer
        _state.update { it.copy(rtcPrompt = RtcPrompt(beforeStart = false)) }
        return try {
            answer.get()
        } finally {
            rtcAnswer = null
        }
    }

    /** Asks a running backup to stop; the cartridge cleanup still runs. */
    fun cancelBackup() {
        cancelRequested.set(true)
        rtcAnswer?.complete(false)
    }

    fun deleteBackup(entry: BackupEntry) {
        val g = game ?: return
        if (_state.value.busy || _state.value.restore != null) return
        viewModelScope.launch {
            try {
                val (all, others) = withContext(Dispatchers.IO) {
                    library.delete(entry)
                    library.list(g.key) to library.listOtherCartridges(g)
                }
                _state.update {
                    it.copy(backups = all, otherCartridgeBackups = others, lastBackup = it.lastBackup?.takeIf { l -> l.id != entry.id })
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


    // ---- restore (milestone 3) ---------------------------------------------------------------

    /** Opens the review for restoring [entry]. Only with a connected reader in GB / GBC mode and a fresh header. */
    fun startRestore(entry: BackupEntry, undoOf: BackupEntry? = null) {
        val st = _state.value
        val header = st.header as? DmgHeader
        if (session == null || header == null || st.busy) return
        if (st.status != ConnectionStatus.CONNECTED || st.platform != CartPlatform.GB_GBC || st.headerPlatform != CartPlatform.GB_GBC) return
        if (st.restore != null) return
        viewModelScope.launch {
            val sourceGame = withContext(Dispatchers.IO) { library.readGame(entry.gameKey) }
            val review = RestorePolicy.review(entry, sourceGame, header)
            debugLog.add("Restore review: ${entry.id} -> ${review.cartGame.key}: ${review.match}")
            _state.update {
                it.copy(
                    restore = RestoreUi.Review(review, restoreClock = review.clockOptionOffered, undoOf = undoOf),
                    error = null, info = null,
                )
            }
        }
    }

    fun setRestoreTitle(text: String) = updateReview { it.copy(typedTitle = text) }

    fun setAdjustClock(on: Boolean) = updateReview { it.copy(adjustClock = on) }

    fun setRestoreClock(on: Boolean) = updateReview { it.copy(restoreClock = on && it.review.clockOptionOffered) }

    private fun updateReview(change: (RestoreUi.Review) -> RestoreUi.Review) {
        _state.update { st -> (st.restore as? RestoreUi.Review)?.let { st.copy(restore = change(it)) } ?: st }
    }

    /** Leaves the review, the confirm step or a finished flow. Never while the cartridge is being accessed. */
    fun closeRestore() {
        _state.update { st -> if (st.busy) st else st.copy(restore = null) }
    }

    /** "Continue" in the review: takes the safety copy (reads only). */
    fun continueRestore() {
        val s = session ?: return
        val ui = _state.value.restore as? RestoreUi.Review ?: return
        if (_state.value.busy || !RestorePolicy.mayStart(ui.review, ui.typedTitle)) return
        val request = RestoreRequest(ui.review, ui.typedTitle, ui.restoreClock, ui.adjustClock, ui.undoOf)
        val c = coordinator(s)
        cancelRequested.set(false)
        _state.update { it.copy(busy = true, restore = RestoreUi.SafetyCopyRunning(request, 0L to 1L)) }
        viewModelScope.launch {
            try {
                val result = c.takeSafetyCopy(request, cancelRequested::get) { d, t ->
                    _state.update { st ->
                        if (st.restore is RestoreUi.SafetyCopyRunning) st.copy(restore = RestoreUi.SafetyCopyRunning(request, d to t)) else st
                    }
                }
                val next = when (result) {
                    is SafetyCopy.Ready -> RestoreUi.Confirm(request, result)
                    SafetyCopy.Cancelled -> RestoreUi.Stopped(request, "Cancelled. Nothing was written to the cartridge.", cancelled = true)
                    is SafetyCopy.Failed -> RestoreUi.Stopped(request, result.message, cancelled = false)
                }
                refreshLists()
                _state.update { it.copy(restore = next) }
            } catch (e: Exception) {
                debugLog.add("Safety copy crashed: $e")
                _state.update {
                    it.copy(restore = RestoreUi.Stopped(request, "The safety copy failed, nothing was written: ${e.message}", cancelled = false))
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Hold-to-confirm (or its dialog alternative) was completed: writes and verifies. */
    fun confirmRestoreWrite() {
        val s = session ?: return
        val ui = _state.value.restore as? RestoreUi.Confirm ?: return
        if (_state.value.busy) return
        val c = coordinator(s)
        val total = ui.request.review.source.size.toLong()
        cancelRequested.set(false)
        _state.update { it.copy(busy = true, restore = RestoreUi.Working(ui.request, ui.safety, RestorePhase.WRITE, 0L to total)) }
        viewModelScope.launch {
            try {
                val result = c.writeAndVerify(ui.request, ui.safety, cancelRequested::get) { phase, d, t ->
                    _state.update { st ->
                        val w = st.restore as? RestoreUi.Working
                        if (w != null) st.copy(restore = w.copy(phase = phase, progress = d to t)) else st
                    }
                }
                refreshLists()
                _state.update { it.copy(restore = RestoreUi.Done(ui.request, result)) }
            } catch (e: Exception) {
                debugLog.add("Restore crashed: $e")
                _state.update { it.copy(error = "Restore error: ${e.message}", restore = null) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Stops the safety copy (nothing written) or the write (cartridge left partly written). */
    fun stopRestore() {
        cancelRequested.set(true)
        _state.update { st ->
            val w = st.restore as? RestoreUi.Working
            if (w != null) st.copy(restore = w.copy(stopRequested = true)) else st
        }
    }

    /** "Undo restore": restores the safety copy through the same flow (review, safety copy, confirm, write, verify). */
    fun undoRestore() {
        val done = _state.value.restore as? RestoreUi.Done ?: return
        val r = done.result
        if (!r.verified) return
        _state.update { it.copy(restore = null) }
        startRestore(r.safetyCopy, undoOf = r.source)
    }

    /** After a failed or interrupted write: restores the pre-restore copy through the same flow. */
    fun restoreSafetyCopyAfterFailure(result: com.therealnox.gbflash.restore.RestoreResult) {
        _state.update { it.copy(restore = null) }
        startRestore(result.safetyCopy)
    }

    private fun coordinator(s: DeviceSession): RestoreCoordinator =
        coordinator ?: RestoreCoordinator(s, library, appVersion(), log = debugLog::add).also { coordinator = it }

    private suspend fun refreshLists() {
        val g = game ?: return
        val (all, others) = withContext(Dispatchers.IO) { library.list(g.key) to library.listOtherCartridges(g) }
        _state.update { it.copy(backups = all, otherCartridgeBackups = others) }
    }

    // ---- import (milestone 3) ----------------------------------------------------------------

    /** True if the file picker may open; otherwise explains why not (a header must have been read). */
    fun requestImport(): Boolean {
        val st = _state.value
        if (st.busy || st.restore != null) return false
        if (game == null || plan !is BackupPlan.Ready || st.header !is DmgHeader) {
            _state.update {
                it.copy(
                    info = null,
                    error = "Read a cartridge header first: a save is imported into the game of the inserted cartridge, " +
                        "and its size must match that cartridge.",
                )
            }
            return false
        }
        _state.update { it.copy(error = null) }
        return true
    }

    /** Stores the chosen `.sav` as an "imported" library entry of the current cartridge game. */
    fun importSav(uri: Uri?) {
        if (uri == null) return
        val g = game ?: return
        val p = plan as? BackupPlan.Ready ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val entry = withContext(Dispatchers.IO) {
                    val name = displayName(uri)
                    val limit = SaveImport.maxFileSize(p.saveSize)
                    val bytes = app.contentResolver.openInputStream(uri)?.use { readLimited(it, limit) }
                        ?: throw IOException("Cannot open the chosen file")
                    if (bytes.size > limit) {
                        throw IOException("The file is larger than $limit bytes; this cartridge's save is ${p.saveSize} bytes.")
                    }
                    when (val r = SaveImport.parse(bytes, p.saveSize, clockCart = p.mapper == DmgMapper.MBC3)) {
                        is SaveImport.Result.Rejected -> throw IOException(r.reason)
                        is SaveImport.Result.Ok -> library.store(
                            g,
                            NewBackup(
                                save = r.save,
                                rtcFooter = r.rtcFooter,
                                rtcProbePresent = null,
                                rtcDeclared = p.rtcDeclared,
                                firmware = "",
                                appVersion = appVersion(),
                                verified = false,
                                suspectReason = SaveCheck.suspectReason(r.save),
                                note = "Imported from $name",
                                kind = BackupKind.IMPORTED,
                                originalFileName = name,
                            ),
                        )
                    }
                }
                debugLog.add("Imported ${entry.originalFileName}: ${entry.id} CRC32 %08X".format(entry.crc32))
                refreshLists()
                _state.update {
                    it.copy(
                        info = "Imported ${entry.originalFileName ?: "file"} as ${entry.id} (CRC32 %08X). ".format(entry.crc32) +
                            "It is not verified against a cartridge; restore it like any backup.",
                    )
                }
            } catch (e: Exception) {
                debugLog.add("Import failed: $e")
                _state.update { it.copy(error = "Import failed: ${e.message}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        val app = getApplication<Application>()
        val fromProvider = try {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        return fromProvider ?: (uri.lastPathSegment ?: "file.sav")
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit + 1 - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
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
        if (_state.value.busy) return
        session = null
        currentDevice = null
        plan = null
        game = null
        coordinator = null
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
        plan = null
        game = null
        coordinator = null
        cancelRequested.set(true)
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

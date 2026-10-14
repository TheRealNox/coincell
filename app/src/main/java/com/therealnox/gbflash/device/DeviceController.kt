package com.therealnox.gbflash.device

import com.therealnox.gbflash.cart.AgbDatabase
import com.therealnox.gbflash.cart.DmgDatabase
import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.AgbRtcSnapshot
import com.therealnox.gbflash.cart.AgbSaveDetection
import com.therealnox.gbflash.cart.AgbSaveTypes
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.DmgMapper
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.BackupKind
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.DebugLog
import com.therealnox.gbflash.protocol.DeviceSession
import com.therealnox.gbflash.protocol.IncompatibleDeviceException
import com.therealnox.gbflash.restore.RestoreCoordinator
import com.therealnox.gbflash.restore.RestorePolicy
import com.therealnox.gbflash.restore.RestoreRequest
import com.therealnox.gbflash.restore.RestoreResult
import com.therealnox.gbflash.restore.SafetyCopy
import com.therealnox.gbflash.save.AgbBackupPlan
import com.therealnox.gbflash.save.BackupCancelledException
import com.therealnox.gbflash.save.BackupPlan
import com.therealnox.gbflash.save.RestorePhase
import com.therealnox.gbflash.save.SaveCheck
import com.therealnox.gbflash.save.SaveImport
import com.therealnox.gbflash.usb.ReaderGateway
import com.therealnox.gbflash.usb.ReaderRef
import com.therealnox.gbflash.usb.SerialLink
import com.therealnox.gbflash.usb.UsbConnector
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single, app-wide owner of the reader session. Every screen observes [state] (the Device Status Bar
 * reads it everywhere); the per-screen view models only call into it. The engines (protocol, backup,
 * restore) are used exactly as before; this class holds the UI-facing state around them.
 *
 * Voltage rule: the cartridge platform is an explicit choice that is forgotten at every power-off,
 * disconnect and detach ([state].platform becomes null), never stored, never inferred.
 */
class DeviceController(
    private val gateway: ReaderGateway,
    private val library: SaveLibrary,
    private val scope: CoroutineScope,
    val debugLog: DebugLog = DebugLog(),
    private val appVersion: String = "?",
    /** Called after the library changed on disk (backup, safety copy, import) so the browser reloads. */
    private val onLibraryChanged: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val openSession: suspend (SerialLink) -> DeviceSession = { DeviceSession.open(it, debugLog::add) },
    /** The cartridge is powered off after this long without an operation. */
    private val idlePowerOffMillis: Long = DEFAULT_IDLE_POWER_OFF_MS,
    /** FlashGBX's GBA database (save type lookup by header SHA-1). */
    private val agbDatabase: AgbDatabase = AgbDatabase.EMPTY,
    /** FlashGBX's GB/GBC database (friendly default name by header SHA-1). */
    private val dmgDatabase: DmgDatabase = DmgDatabase.EMPTY,
) {
    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state

    private var session: DeviceSession? = null
    private var currentReader: ReaderRef? = null
    private var connecting = false
    private var plan: BackupPlan? = null
    private var agbPlan: AgbBackupPlan? = null
    private var game: GameInfo? = null
    private val cancelRequested = AtomicBoolean(false)
    private var coordinator: RestoreCoordinator? = null
    private var rtcAnswer: CompletableFuture<Boolean>? = null

    init {
        gateway.start(
            onPermissionResult = { reader, granted ->
                if (granted) {
                    connect(reader)
                } else {
                    _state.update { it.copy(status = ConnectionStatus.NEEDS_PERMISSION, statusDetail = "USB permission denied") }
                }
            },
            onDetached = { reader -> if (reader.id == currentReader?.id) onDetached() },
        )
        watchIdle()
    }

    // ---- connection ----------------------------------------------------------------------------

    /** Looks for the GBFlash and connects (asking for USB permission if needed). */
    fun refresh() {
        if (session != null || connecting) return
        val reader = gateway.find()
        when {
            reader == null -> _state.update {
                it.copy(status = ConnectionStatus.NO_DEVICE, statusDetail = "No CH340 (1A86:7523) found", error = null)
            }
            gateway.hasPermission(reader) -> connect(reader)
            else -> {
                _state.update {
                    it.copy(status = ConnectionStatus.NEEDS_PERMISSION, statusDetail = "Waiting for USB permission", error = null)
                }
                gateway.requestPermission(reader)
            }
        }
    }

    private fun connect(reader: ReaderRef) {
        if (session != null || connecting) return
        connecting = true
        _state.update {
            it.copy(status = ConnectionStatus.CONNECTING, statusDetail = "Connecting...", error = null, firmware = null)
        }
        scope.launch {
            try {
                debugLog.add("Opening ${reader.name} at ${UsbConnector.BAUD} baud")
                val link = withContext(io) { gateway.open(reader) }
                val s = openSession(link)
                session = s
                currentReader = reader
                _state.update { it.copy(status = ConnectionStatus.CONNECTED, statusDetail = null, firmware = s.firmware) }
            } catch (e: IncompatibleDeviceException) {
                debugLog.add("Rejected: ${e.message}")
                _state.update { it.copy(status = ConnectionStatus.FAILED, statusDetail = e.message, firmware = e.info) }
            } catch (e: Exception) {
                debugLog.add("Connect failed: $e")
                _state.update { it.copy(status = ConnectionStatus.FAILED, statusDetail = e.message ?: e.toString()) }
            } finally {
                connecting = false
            }
        }
    }

    /** User-initiated disconnect: powers the cartridge off, then closes the port. Forgets the platform. */
    fun disconnect() {
        val s = session ?: return
        if (_state.value.busy) return
        forgetSession()
        scope.launch {
            s.close()
            debugLog.add("Disconnected (cart powered off)")
            _state.value = DeviceState(status = ConnectionStatus.NO_DEVICE, statusDetail = "Disconnected")
        }
    }

    private fun onDetached() {
        session?.abandon()
        forgetSession()
        cancelRequested.set(true)
        debugLog.add("USB device detached")
        val before = _state.value
        val interrupted = when {
            before.backup is BackupUi.Running -> "The reader was unplugged during the backup. Nothing was stored."
            before.restore is RestoreUi.Working ->
                "The reader was unplugged during the restore. The save on the cartridge may be incomplete. " +
                    "Reconnect, read the cartridge again and restore the safety copy from your library."
            before.restore is RestoreUi.SafetyCopyRunning -> "The reader was unplugged during the safety copy. Nothing was written to the cartridge."
            else -> null
        }
        // Everything about the cartridge, including the platform choice, is forgotten.
        _state.value = DeviceState(status = ConnectionStatus.NO_DEVICE, statusDetail = "USB device detached", interrupted = interrupted)
    }

    private fun forgetSession() {
        session = null
        currentReader = null
        plan = null
        agbPlan = null
        game = null
        coordinator = null
    }

    // ---- platform, power, header ---------------------------------------------------------------

    /**
     * The user confirmed the platform in the confirm sheet: powers the cartridge on at that platform's
     * voltage and reads the header. The session layer powers the cartridge off before it changes mode
     * (so the voltage only changes unpowered); if a different platform is still active it is powered
     * off here first as well.
     */
    fun confirmPlatform(platform: CartPlatform) {
        val s = session ?: return
        val st = _state.value
        if (st.status != ConnectionStatus.CONNECTED || st.busy || st.restore != null || st.backup is BackupUi.Running) return
        val previous = st.platform
        plan = null
        agbPlan = null
        game = null
        _state.update {
            clearCartState(it).copy(platform = platform, busy = true, busyLabel = "Reading ${platform.shortName} header", cartPowered = false)
        }
        scope.launch {
            try {
                if (previous != null && previous != platform) s.powerOffCart()
                debugLog.add("--- Read header as ${platform.label} ---")
                val header = s.readHeader(platform)
                var available = false
                var reason: String? = null
                var cartGame: GameInfo? = null
                var agbSave: AgbSaveState? = null
                if (header is DmgHeader && platform == CartPlatform.GB_GBC) {
                    val p = BackupPlan.forHeader(header)
                    plan = p
                    available = p is BackupPlan.Ready
                    reason = (p as? BackupPlan.Unavailable)?.reason
                    val dbName = dmgDatabase.lookup(header)?.name
                    debugLog.add("Database: ${dbName ?: "no entry"}")
                    cartGame = GameInfo.fromDmg(header, databaseName = dbName).also { game = it }
                } else if (header is AgbHeader && platform == CartPlatform.GBA) {
                    // Detection order (a) patch marker, (b) FlashGBX database; (c) probing is the user's explicit choice.
                    val detection = AgbSaveDetection.fromHeader(header, agbDatabase)
                    val saveState = AgbSaveState(detection)
                    agbSave = saveState
                    debugLog.add("GBA save type: ${detection.label} from ${detection.source?.label ?: "nothing"}; database: ${detection.dbName ?: "no entry"}")
                    val p = AgbBackupPlan.forHeader(header, saveState.effectiveType, saveState.effectiveSource)
                    agbPlan = p
                    available = p is AgbBackupPlan.Ready
                    reason = (p as? AgbBackupPlan.Unavailable)?.reason
                    cartGame = GameInfo.fromAgb(header, databaseName = detection.dbName).also { game = it }
                }
                _state.update {
                    it.copy(
                        header = header, headerPlatform = platform, cartGame = cartGame, agbSave = agbSave,
                        backupAvailable = available, backupUnavailableReason = reason,
                    )
                }
            } catch (e: Exception) {
                debugLog.add("Read header failed: $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                // Even a failed read leaves the cartridge powered (the mode was set): offer the power-off.
                _state.update { it.copy(busy = false, busyLabel = null, cartPowered = true) }
            }
        }
    }

    /**
     * "Power off cart" / "Swap cartridge": F3, keeps the connection. The platform and the header are
     * forgotten, so the next cartridge needs a new explicit choice.
     */
    fun powerOffCart(reason: String? = null) {
        val s = session ?: return
        val st = _state.value
        if (st.busy || st.backup is BackupUi.Running) return
        if (st.restore != null && st.restore !is RestoreUi.Done && st.restore !is RestoreUi.Stopped) return
        scope.launch {
            try {
                s.powerOffCart()
                debugLog.add("Cartridge powered off")
                plan = null
                agbPlan = null
                game = null
                _state.update {
                    clearCartState(it).copy(
                        platform = null,
                        cartPowered = false,
                        info = reason ?: "Cartridge power is off. It is safe to remove the cartridge now.",
                    )
                }
            } catch (e: Exception) {
                debugLog.add("Power off failed: $e")
                _state.update { it.copy(error = "Power off failed: ${e.message}") }
            }
        }
    }

    private fun clearCartState(it: DeviceState) = it.copy(
        header = null, headerPlatform = null, cartGame = null, agbSaveHint = null, agbSave = null, error = null, info = null,
        backupAvailable = false, backupUnavailableReason = null, backup = null, restore = null,
    )

    fun dismissMessages() {
        _state.update { it.copy(error = null, info = null, interrupted = null) }
    }

    /** Dismisses the finished backup screen's state. */
    fun closeBackup() {
        _state.update { if (it.backup is BackupUi.Running) it else it.copy(backup = null) }
    }

    /** Read-only GBA save window probe (Settings, Advanced). */
    fun probeAgbSave() {
        val s = session ?: return
        val st = _state.value
        if (st.busy || st.platform != CartPlatform.GBA || st.restore != null) return
        _state.update { it.copy(busy = true, busyLabel = "Probing GBA save window", error = null) }
        scope.launch {
            try {
                debugLog.add("--- Read-only GBA save window probe ---")
                val hint = s.probeAgbSaveReadOnly()
                _state.update { it.copy(agbSaveHint = hint.summary) }
            } catch (e: Exception) {
                debugLog.add("Probe failed: $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null) }
            }
        }
    }

    // ---- GBA save type -------------------------------------------------------------------------

    /** The user picked [type] from the list (null: back to the detected one). */
    fun chooseAgbSaveType(type: Int?) {
        val st = _state.value
        val header = st.header as? AgbHeader ?: return
        val current = st.agbSave ?: return
        if (st.busy || st.restore != null || st.backup is BackupUi.Running) return
        debugLog.add("GBA save type chosen by the user: ${type?.let { AgbSaveTypes.label(it) } ?: "the detected one"}")
        applyAgbSelection(header, current.copy(userChoice = type))
    }

    private fun applyAgbSelection(header: AgbHeader, save: AgbSaveState) {
        val p = AgbBackupPlan.forHeader(header, save.effectiveType, save.effectiveSource)
        agbPlan = p
        _state.update {
            it.copy(
                agbSave = save,
                backupAvailable = p is AgbBackupPlan.Ready,
                backupUnavailableReason = (p as? AgbBackupPlan.Unavailable)?.reason,
            )
        }
    }

    /**
     * Step (c) of the detection order: probes the cartridge (Flash ID, SRAM, EEPROM). Only on request, because
     * it makes the brief identification writes of [com.therealnox.gbflash.cart.AgbCartIo]. The result replaces the
     * database lookup and is shown as "probe", for the user to confirm or change.
     */
    fun detectAgbSaveType() {
        val s = session ?: return
        val st = _state.value
        val header = st.header as? AgbHeader ?: return
        if (st.busy || st.platform != CartPlatform.GBA || st.restore != null || st.backup is BackupUi.Running || !header.logoValid) return
        cancelRequested.set(false)
        _state.update { it.copy(busy = true, busyLabel = "Probing the save memory", error = null, info = null, agbSave = it.agbSave?.copy(probing = true)) }
        scope.launch {
            try {
                debugLog.add("--- Probe the GBA save memory ---")
                val detection = s.detectAgbSave(cancelRequested::get, { _, _ -> }, debugLog::add)
                debugLog.add("Probe result: ${detection.label}${detection.chipLabel?.let { " ($it)" } ?: ""}${detection.note?.let { ": $it" } ?: ""}")
                val current = _state.value.agbSave ?: AgbSaveState(detection)
                applyAgbSelection(header, current.copy(detection = detection, userChoice = null, probing = false))
            } catch (e: BackupCancelledException) {
                debugLog.add("Probe cancelled")
            } catch (e: Exception) {
                debugLog.add("Probe failed: $e")
                _state.update { it.copy(error = "The probe failed: ${e.message ?: e}") }
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null, agbSave = it.agbSave?.copy(probing = false)) }
            }
        }
    }

    // ---- idle power-off (docs/UI_DESIGN.md 3.5) -------------------------------------------------

    private fun idleKey(s: DeviceState): Boolean {
        val resting = s.restore == null || s.restore is RestoreUi.Done || s.restore is RestoreUi.Stopped
        return s.status == ConnectionStatus.CONNECTED && s.cartPowered && !s.busy && s.backup !is BackupUi.Running &&
            s.rtcPrompt == null && resting
    }

    private fun watchIdle() {
        scope.launch {
            _state.map(::idleKey).distinctUntilChanged().collectLatest { idle ->
                if (idle) {
                    delay(idlePowerOffMillis)
                    debugLog.add("Idle for ${idlePowerOffMillis / 1000} s: powering the cartridge off")
                    powerOffCart("Cartridge power was switched off after being idle. It is safe to remove the cartridge now.")
                }
            }
        }
    }

    // ---- backup --------------------------------------------------------------------------------

    /**
     * Reads and verifies the save of the inserted GB / GBC cartridge into memory, then stores it in
     * the library. Only possible after a successful GB / GBC header read; never for GBA.
     */
    fun backupSave() {
        if (_state.value.platform == CartPlatform.GBA) {
            val gp = agbPlan as? AgbBackupPlan.Ready ?: return
            val gst = _state.value
            if (gst.busy || gst.restore != null || gst.rtcPrompt != null) return
            runAgbBackup(gp)
            return
        }
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

    /** The friendly label of [g]: the custom name stored in the library, else [GameInfo.defaultLabel]. */
    private fun labelOf(g: GameInfo): String = g.withNameFrom(runCatching { library.readGame(g.key) }.getOrNull()).label

    private fun GameInfo.secondaryTitleFor(label: String): String? = title.trim().takeIf { it.isNotEmpty() && it != label }

    private fun runBackup(includeRtc: Boolean) {
        val s = session ?: return
        val st = _state.value
        val p = plan as? BackupPlan.Ready ?: return
        val g = game ?: return
        if (st.busy || st.restore != null || st.platform != CartPlatform.GB_GBC || st.headerPlatform != CartPlatform.GB_GBC) return
        cancelRequested.set(false)
        val title = labelOf(g)
        _state.update {
            it.copy(busy = true, busyLabel = "Backing up", error = null, info = null, backup = BackupUi.Running(title, 0L to p.saveSize * 2L, g.secondaryTitleFor(title)))
        }
        scope.launch {
            var outcome: BackupUi = BackupUi.Cancelled
            try {
                debugLog.add("--- Back up save (${p.mapperName}, ${p.saveSize} bytes) ---")
                val result = s.backupDmgSave(
                    plan = p,
                    isCancelled = cancelRequested::get,
                    onProgress = { done, total ->
                        _state.update { st2 -> if (st2.backup is BackupUi.Running) st2.copy(backup = BackupUi.Running(title, done to total, g.secondaryTitleFor(title))) else st2 }
                    },
                    log = debugLog::add,
                    includeRtc = includeRtc,
                    askRtc = if (p.rtcDeclared) null else ::askRtcBlocking,
                )
                val suspect = SaveCheck.suspectReason(result.data)
                val fw = s.firmware
                val entry = withContext(io) {
                    library.store(
                        g,
                        NewBackup(
                            save = result.data,
                            rtcFooter = result.rtc?.toFooter(),
                            rtcProbePresent = result.rtcProbePresent,
                            rtcDeclared = result.rtcDeclared,
                            firmware = fw.versionString + (fw.deviceName?.let { " ($it)" } ?: ""),
                            appVersion = appVersion,
                            verified = true,
                            suspectReason = suspect,
                        ),
                    )
                }
                debugLog.add("Backup stored: ${entry.id} CRC32 %08X".format(entry.crc32))
                onLibraryChanged()
                outcome = BackupUi.Result(entry, title, result.warnings, g.secondaryTitleFor(title))
            } catch (e: BackupCancelledException) {
                debugLog.add("Backup cancelled by user")
                outcome = BackupUi.Cancelled
            } catch (e: Exception) {
                debugLog.add("Backup failed: $e")
                outcome = BackupUi.Failed(e.message ?: e.toString())
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null, backup = outcome) }
            }
        }
    }

    /**
     * GBA backup: probes for a clock (if the header allows one), asks FlashGBX's question when it finds one, reads the
     * save twice per chunk and stores it with the save type and its source.
     */
    private fun runAgbBackup(p: AgbBackupPlan.Ready) {
        val s = session ?: return
        val st = _state.value
        val g = game ?: return
        if (st.busy || st.restore != null || st.platform != CartPlatform.GBA || st.headerPlatform != CartPlatform.GBA) return
        cancelRequested.set(false)
        val title = labelOf(g)
        _state.update {
            it.copy(busy = true, busyLabel = "Backing up", error = null, info = null, backup = BackupUi.Running(title, 0L to p.saveSize * 2L, g.secondaryTitleFor(title)))
        }
        scope.launch {
            var outcome: BackupUi = BackupUi.Cancelled
            try {
                debugLog.add("--- Back up GBA save (${p.saveLabel} from ${p.source.label}, ${p.saveSize} bytes) ---")
                val result = s.backupAgbSave(
                    plan = p,
                    isCancelled = cancelRequested::get,
                    onProgress = { done, total ->
                        _state.update { st2 -> if (st2.backup is BackupUi.Running) st2.copy(backup = BackupUi.Running(title, done to total, g.secondaryTitleFor(title))) else st2 }
                    },
                    log = debugLog::add,
                    askRtc = ::askRtcBlocking,
                )
                val suspect = SaveCheck.suspectReason(result.data)
                val fw = s.firmware
                val entry = withContext(io) {
                    library.store(
                        g,
                        NewBackup(
                            save = result.data,
                            rtcFooter = result.rtc?.toFooter(),
                            rtcProbePresent = result.rtcProbePresent,
                            rtcDeclared = false,
                            firmware = fw.versionString + (fw.deviceName?.let { " ($it)" } ?: ""),
                            appVersion = appVersion,
                            verified = true,
                            suspectReason = suspect,
                            saveType = p.saveType,
                            saveTypeSource = p.source,
                            flashChip = result.flashChipLabel,
                        ),
                    )
                }
                debugLog.add("Backup stored: ${entry.id} CRC32 %08X".format(entry.crc32))
                onLibraryChanged()
                outcome = BackupUi.Result(entry, title, result.warnings, g.secondaryTitleFor(title))
            } catch (e: BackupCancelledException) {
                debugLog.add("Backup cancelled by user")
                outcome = BackupUi.Cancelled
            } catch (e: Exception) {
                debugLog.add("Backup failed: $e")
                outcome = BackupUi.Failed(e.message ?: e.toString())
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null, backup = outcome) }
            }
        }
    }

    /** Called on the I/O thread after the RTC probe found a clock the header did not declare. */
    private fun askRtcBlocking(): Boolean {
        val answer = CompletableFuture<Boolean>()
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

    // ---- restore (milestone 3, unchanged rules) -------------------------------------------------

    /** Opens the review for restoring [entry]. Only with a connected reader in GB / GBC mode and a fresh header. */
    fun startRestore(entry: BackupEntry) {
        if (_state.value.restore != null) return
        beginRestore(entry, undoOf = null)
    }

    private fun beginRestore(entry: BackupEntry, undoOf: BackupEntry?) {
        val st = _state.value
        if (st.platform == CartPlatform.GBA) {
            beginAgbRestore(entry, undoOf)
            return
        }
        val header = st.header as? DmgHeader
        if (session == null || header == null || st.busy || st.backup is BackupUi.Running) return
        if (st.status != ConnectionStatus.CONNECTED || st.platform != CartPlatform.GB_GBC || st.headerPlatform != CartPlatform.GB_GBC) return
        _state.update { it.copy(restore = RestoreUi.Loading, error = null, info = null) }
        scope.launch {
            val sourceGame = withContext(io) { library.readGame(entry.gameKey) }
            val review = RestorePolicy.review(entry, sourceGame, header)
            debugLog.add("Restore review: ${entry.id} -> ${review.cartGame.key}: ${review.match}")
            _state.update {
                // The user may have left the flow while the review was being prepared.
                if (it.restore is RestoreUi.Loading) {
                    it.copy(restore = RestoreUi.Review(review, restoreClock = review.clockOptionOffered, undoOf = undoOf))
                } else {
                    it
                }
            }
        }
    }

    /**
     * Review of a GBA restore. The only cartridge access is the read-only clock probe, and only when the backup has a
     * clock to offer.
     */
    private fun beginAgbRestore(entry: BackupEntry, undoOf: BackupEntry?) {
        val s = session ?: return
        val st = _state.value
        val header = st.header as? AgbHeader ?: return
        val p = agbPlan ?: return
        if (st.busy || st.backup is BackupUi.Running) return
        if (st.status != ConnectionStatus.CONNECTED || st.platform != CartPlatform.GBA || st.headerPlatform != CartPlatform.GBA) return
        _state.update { it.copy(restore = RestoreUi.Loading, error = null, info = null) }
        scope.launch {
            val sourceGame = withContext(io) { library.readGame(entry.gameKey) }
            var clockPresent: Boolean? = false
            if (entry.agbRtc != null && p is AgbBackupPlan.Ready) {
                _state.update { it.copy(busy = true, busyLabel = "Checking the cartridge clock") }
                clockPresent = try {
                    s.probeAgbClock(debugLog::add).also { r -> if (!r.present) debugLog.add("No clock: ${r.note}") }.present
                } catch (e: Exception) {
                    debugLog.add("Clock probe failed: $e")
                    null
                } finally {
                    _state.update { it.copy(busy = false, busyLabel = null) }
                }
            }
            val review = RestorePolicy.reviewAgb(entry, sourceGame, header, p, clockPresent)
            debugLog.add("Restore review: ${entry.id} -> ${review.cartGame.key}: ${review.match}")
            _state.update {
                if (it.restore is RestoreUi.Loading) {
                    it.copy(restore = RestoreUi.Review(review, restoreClock = review.clockOptionOffered, undoOf = undoOf))
                } else {
                    it
                }
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
        _state.update { it.copy(busy = true, busyLabel = "Safety copy", restore = RestoreUi.SafetyCopyRunning(request, 0L to 1L)) }
        scope.launch {
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
                onLibraryChanged()
                _state.update { it.copy(restore = next) }
            } catch (e: Exception) {
                debugLog.add("Safety copy crashed: $e")
                _state.update {
                    it.copy(restore = RestoreUi.Stopped(request, "The safety copy failed, nothing was written: ${e.message}", cancelled = false))
                }
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null) }
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
        _state.update {
            it.copy(busy = true, busyLabel = "Restoring", restore = RestoreUi.Working(ui.request, ui.safety, RestorePhase.WRITE, 0L to total))
        }
        scope.launch {
            try {
                val result: RestoreResult = c.writeAndVerify(ui.request, ui.safety, cancelRequested::get) { phase, d, t ->
                    _state.update { st ->
                        val w = st.restore as? RestoreUi.Working
                        if (w != null) st.copy(restore = w.copy(phase = phase, progress = d to t)) else st
                    }
                }
                onLibraryChanged()
                _state.update { it.copy(restore = RestoreUi.Done(ui.request, result)) }
            } catch (e: Exception) {
                debugLog.add("Restore crashed: $e")
                _state.update { it.copy(error = "Restore error: ${e.message}", restore = null) }
            } finally {
                _state.update { it.copy(busy = false, busyLabel = null) }
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
        beginRestore(r.safetyCopy, undoOf = r.source)
    }

    /** After a failed or interrupted write: restores the pre-restore copy through the same flow. */
    fun restoreSafetyCopyAfterFailure(result: RestoreResult) {
        if (_state.value.restore !is RestoreUi.Done) return
        beginRestore(result.safetyCopy, undoOf = null)
    }

    private fun coordinator(s: DeviceSession): RestoreCoordinator =
        coordinator ?: RestoreCoordinator(s, library, appVersion, log = debugLog::add).also { coordinator = it }

    // ---- import (milestone 3) ------------------------------------------------------------------

    /** The largest file the import may read for the inserted cartridge, or null if no cartridge is ready. */
    fun importLimit(): Int? {
        val st = _state.value
        if (st.platform == CartPlatform.GBA) {
            val gp = agbPlan as? AgbBackupPlan.Ready ?: return null
            if (st.busy || st.restore != null || game == null || st.header !is AgbHeader) return null
            return SaveImport.maxFileSize(gp.saveSize, AgbRtcSnapshot.FOOTER_SIZE)
        }
        val p = plan as? BackupPlan.Ready ?: return null
        if (st.busy || st.restore != null || game == null || st.header !is DmgHeader) return null
        return SaveImport.maxFileSize(p.saveSize)
    }

    /**
     * Stores a `.sav` as an "imported" library entry of the current cartridge game. [read] runs on an I/O
     * thread, gets the size limit, and returns the file content (or throws).
     */
    fun importSav(name: String, read: (limit: Int) -> ByteArray) {
        if (_state.value.platform == CartPlatform.GBA) {
            importAgbSav(name, read)
            return
        }
        val g = game ?: return
        val p = plan as? BackupPlan.Ready ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, busyLabel = "Importing", error = null, info = null) }
        scope.launch {
            try {
                val entry = withContext(io) {
                    val limit = SaveImport.maxFileSize(p.saveSize)
                    val bytes = read(limit)
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
                                appVersion = appVersion,
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
                onLibraryChanged()
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
                _state.update { it.copy(busy = false, busyLabel = null) }
            }
        }
    }

    private fun importAgbSav(name: String, read: (limit: Int) -> ByteArray) {
        val g = game ?: return
        val p = agbPlan as? AgbBackupPlan.Ready ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, busyLabel = "Importing", error = null, info = null) }
        scope.launch {
            try {
                val entry = withContext(io) {
                    val limit = SaveImport.maxFileSize(p.saveSize, AgbRtcSnapshot.FOOTER_SIZE)
                    val bytes = read(limit)
                    if (bytes.size > limit) {
                        throw IOException("The file is larger than $limit bytes; this cartridge's save is ${p.saveSize} bytes.")
                    }
                    when (val r = SaveImport.parseAgb(bytes, p.saveSize)) {
                        is SaveImport.Result.Rejected -> throw IOException(r.reason)
                        is SaveImport.Result.Ok -> library.store(
                            g,
                            NewBackup(
                                save = r.save,
                                rtcFooter = r.rtcFooter,
                                rtcProbePresent = null,
                                rtcDeclared = false,
                                firmware = "",
                                appVersion = appVersion,
                                verified = false,
                                suspectReason = SaveCheck.suspectReason(r.save),
                                note = "Imported from $name",
                                kind = BackupKind.IMPORTED,
                                originalFileName = name,
                                saveType = p.saveType,
                                saveTypeSource = p.source,
                            ),
                        )
                    }
                }
                debugLog.add("Imported ${entry.originalFileName}: ${entry.id} CRC32 %08X".format(entry.crc32))
                onLibraryChanged()
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
                _state.update { it.copy(busy = false, busyLabel = null) }
            }
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /** Powers the cartridge down and stops listening. Called when the app's view model is cleared. */
    fun shutdown() {
        cancelRequested.set(true)
        gateway.stop()
        val s = session
        forgetSession()
        if (s != null) {
            // The scope is already cancelled here; make a best effort to power the cart down.
            CoroutineScope(Dispatchers.IO).launch { s.close() }
        }
    }

    companion object {
        const val DEFAULT_IDLE_POWER_OFF_MS = 3 * 60 * 1000L
    }
}

package com.therealnox.gbflash.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.DebugLog
import com.therealnox.gbflash.protocol.DeviceSession
import com.therealnox.gbflash.protocol.FirmwareInfo
import com.therealnox.gbflash.protocol.IncompatibleDeviceException
import com.therealnox.gbflash.usb.UsbConnector
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
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val debugLog = DebugLog()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val usb = UsbConnector(app)
    private var session: DeviceSession? = null
    private var currentDevice: UsbDevice? = null
    private var connecting = false

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
            if (it.platform == platform) it else it.copy(platform = platform, header = null, headerPlatform = null, agbSaveHint = null, error = null)
        }
    }

    fun readHeader() {
        val s = session ?: return
        val platform = _state.value.platform ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, header = null, headerPlatform = null, agbSaveHint = null) }
        viewModelScope.launch {
            try {
                debugLog.add("--- Read header as ${platform.label} ---")
                val header = s.readHeader(platform)
                _state.update { it.copy(header = header, headerPlatform = platform) }
            } catch (e: Exception) {
                debugLog.add("Read header failed: $e")
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
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
        usb.stop()
        val s = session
        session = null
        if (s != null) {
            // viewModelScope is already cancelled here; make a best effort to power the cart down.
            CoroutineScope(Dispatchers.IO).launch { s.close() }
        }
    }
}

package org.noxeng.coincell.ui.device

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.noxeng.coincell.cart.AgbHeader
import org.noxeng.coincell.cart.AgbSaveTypes
import org.noxeng.coincell.device.AgbSaveState
import org.noxeng.coincell.cart.CartHeader
import org.noxeng.coincell.cart.DmgHeader
import org.noxeng.coincell.device.ConnectionStatus
import org.noxeng.coincell.device.DeviceController
import org.noxeng.coincell.device.DeviceState
import org.noxeng.coincell.device.voltsLabel
import org.noxeng.coincell.library.DisplayPlatform
import org.noxeng.coincell.library.GameSummary
import org.noxeng.coincell.library.LibraryState
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.protocol.CartPlatform
import org.noxeng.coincell.ui.LocalShell
import org.noxeng.coincell.ui.components.displayName
import org.noxeng.coincell.ui.components.readLimited
import org.noxeng.coincell.ui.ScreenFrame
import org.noxeng.coincell.ui.components.CartridgeGlyph
import org.noxeng.coincell.ui.components.KeyValueRow
import org.noxeng.coincell.ui.components.NeutralChip
import org.noxeng.coincell.ui.components.PlatformBadge
import org.noxeng.coincell.ui.components.SectionHeader
import org.noxeng.coincell.ui.components.Severity
import org.noxeng.coincell.ui.components.StatusCard
import org.noxeng.coincell.ui.components.StatusChip
import org.noxeng.coincell.ui.components.VerifiedChip
import org.noxeng.coincell.ui.components.WarningChip
import org.noxeng.coincell.ui.components.formatDateShort
import org.noxeng.coincell.ui.library.inReaderKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Device tab: what the library knows about the cartridge in the reader. */
class DeviceScreenViewModel(store: LibraryStore, deviceState: StateFlow<DeviceState>) : ViewModel() {
    val cartInLibrary: StateFlow<GameSummary?> = combine(store.state, deviceState) { library, device ->
        val key = device.cartGame?.key
        if (key == null || inReaderKey(device) == null) null else (library as? LibraryState.Loaded)?.games?.firstOrNull { it.key == key }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun DeviceScreen(
    vm: DeviceScreenViewModel,
    onBackupStarted: () -> Unit,
    onOpenGame: (String) -> Unit,
    onGoLibrary: () -> Unit,
    onOpenFirmware: () -> Unit,
) {
    val shell = LocalShell.current
    val device = shell.device
    val state by device.state.collectAsState()
    val inLibrary by vm.cartInLibrary.collectAsState()
    ScreenFrame(title = "Device") {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.status) {
                ConnectionStatus.NO_DEVICE -> NoReader(state, onCheck = device::refresh, onGoLibrary = onGoLibrary)
                ConnectionStatus.NEEDS_PERMISSION -> PermissionCard(state, onAllow = device::refresh)
                ConnectionStatus.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Connecting to the reader...")
                }
                ConnectionStatus.FAILED -> StatusCard(
                    "Connection failed",
                    (state.statusDetail ?: "The reader did not answer.") + "\n\nUnplug the reader and plug it in again.",
                    Severity.ERROR,
                ) { Button(onClick = device::refresh) { Text("Try again") } }
                ConnectionStatus.CONNECTED -> Connected(state, device, inLibrary, onBackupStarted, onOpenGame, onOpenFirmware)
            }
        }
    }
}

@Composable
private fun NoReader(state: DeviceState, onCheck: () -> Unit, onGoLibrary: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.UsbOff, contentDescription = null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Plug in your GBFlash reader", style = MaterialTheme.typography.titleLarge)
        Text(
            "Connect it with a USB-C cable (OTG). You can also browse, export and organise saves without a reader.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.statusDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = onCheck, Modifier.heightIn(min = 48.dp)) { Text("Check connection") }
        TextButton(onClick = onGoLibrary, Modifier.heightIn(min = 48.dp)) { Text("Browse library") }
    }
}

@Composable
private fun PermissionCard(state: DeviceState, onAllow: () -> Unit) {
    val denied = state.statusDetail?.contains("denied", ignoreCase = true) == true
    val context = LocalContext.current
    StatusCard(
        title = if (denied) "Permission denied" else "Allow access to the reader",
        body = if (denied) {
            "Coincell cannot talk to the reader without USB permission."
        } else {
            "Android asks once for permission to use the USB reader."
        },
        severity = if (denied) Severity.ERROR else Severity.WARNING,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAllow, Modifier.heightIn(min = 48.dp)) { Text(if (denied) "Ask again" else "Allow access") }
            if (denied) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    Modifier.heightIn(min = 48.dp),
                ) { Text("Open system settings") }
            }
        }
    }
}

@Composable
private fun Connected(
    state: DeviceState,
    device: DeviceController,
    inLibrary: GameSummary?,
    onBackupStarted: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenFirmware: () -> Unit,
) {
    state.error?.let { msg ->
        StatusCard("Something went wrong", msg, Severity.ERROR) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.cartPowered) {
                    Button(onClick = device::powerOffCart, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Power off and try again") }
                }
                TextButton(onClick = device::dismissMessages, Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
            }
        }
    }
    state.info?.let { msg ->
        StatusCard("Note", msg, Severity.INFO) {
            TextButton(onClick = device::dismissMessages, Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
        }
    }
    val header = state.header
    when {
        state.platform == null && !state.busy -> {
            Text("Cartridge power: off", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlatformPicker(enabled = !state.busy, onConfirm = device::confirmPlatform)
        }
        state.busy && header == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(state.busyLabel ?: "Working...")
        }
        header != null -> CartridgeCard(state, header, device, inLibrary, onBackupStarted, onOpenGame)
        state.platform != null -> {
            // The read failed (see the error above): the cartridge is powered, so offer the power-off.
            StatusCard(
                "No cartridge header",
                "The cartridge could not be read as ${state.platform.label}. Power off, check the contacts and the cartridge type, then pick again.",
                Severity.WARNING,
            ) {
                Button(onClick = device::powerOffCart, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Power off and re-pick") }
            }
        }
    }
    ReaderCard(state, onDisconnect = device::disconnect, onDetails = onOpenFirmware)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CartridgeCard(
    state: DeviceState,
    header: CartHeader,
    device: DeviceController,
    inLibrary: GameSummary?,
    onBackupStarted: () -> Unit,
    onOpenGame: (String) -> Unit,
) {
    val context = LocalContext.current
    var swap by remember { mutableStateOf(false) }
    val platform = if (state.headerPlatform == CartPlatform.GBA) DisplayPlatform.GBA else if ((header as? DmgHeader)?.gameCode != null) DisplayPlatform.GBC else DisplayPlatform.GB
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            device.importSav(displayName(context, uri)) { limit -> readLimited(context, uri, limit) }
        }
    }
    val blank = header.likelyNoCart || !header.logoValid
    // The custom name from the library (if any) and, for GBA, the database name: the same label as everywhere else.
    val cartInfo = state.cartGame?.withNameFrom(inLibrary?.game)

    if (blank) {
        StatusCard(
            "No cartridge found, or the wrong type is selected",
            "The logo area does not read as a ${state.headerPlatform?.label ?: "cartridge"}. Power off now, check the type, and clean the gold contacts with isopropyl alcohol if needed.",
            Severity.ERROR,
        ) {
            Button(onClick = device::powerOffCart, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Power off and re-pick") }
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CartridgeGlyph(platform, size = 72.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(cartInfo?.label ?: header.title.ifEmpty { "(no title)" }, style = MaterialTheme.typography.titleLarge)
                    org.noxeng.coincell.ui.components.HeaderTitleLine(cartInfo?.secondaryTitle)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NeutralChip(platform.label, Icons.Filled.PowerSettingsNew)
                        state.headerPlatform?.let { StatusChip(it.voltsLabel, Icons.Filled.Bolt, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
                        if ((header as? DmgHeader)?.hasRtcDeclared == true) NeutralChip("Clock", Icons.Filled.Schedule)
                    }
                }
            }
            when (header) {
                is DmgHeader -> {
                    KeyValueRow("Mapper", header.mapperName)
                    KeyValueRow("ROM", header.romSizeBytes?.let { "${it / 1024} KiB" } ?: "unknown")
                    KeyValueRow("Save", header.save.label + (header.save.sizeBytes?.let { " ($it B)" } ?: ""))
                    header.gameCode?.let { KeyValueRow("Game code", it) }
                }
                is AgbHeader -> {
                    KeyValueRow("Game code", header.gameCode)
                    KeyValueRow("Maker", header.makerCode)
                    val save = state.agbSave
                    if (save != null) {
                        KeyValueRow("Save type", save.effectiveType?.let { AgbSaveTypes.label(it) } ?: "Unknown")
                        save.effectiveSource?.let { KeyValueRow("Found by", it.label) }
                        save.detection.dbName?.let { KeyValueRow("Database", it) }
                        save.detection.chipLabel?.let { KeyValueRow("Flash chip", it) }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (header.headerChecksumValid) VerifiedChip() else WarningChip("Header checksum invalid")
                if (header.logoValid) NeutralChip("Logo OK", Icons.Filled.Bolt) else WarningChip("Logo invalid")
            }
            if (inLibrary != null) {
                TextButton(onClick = { onOpenGame(inLibrary.key) }, Modifier.heightIn(min = 48.dp)) {
                    Text("In your library as \"${cartInfo?.label ?: inLibrary.title}\": ${inLibrary.count} ${if (inLibrary.count == 1) "backup" else "backups"}, latest ${formatDateShort(inLibrary.latestMillis)}")
                }
            } else if (state.backupAvailable) {
                Text("Not in your library yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (header is AgbHeader) {
                state.agbSave?.let { save -> AgbSaveTypeControls(save, state, device) }
            }
            state.backupUnavailableReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }

            // ---- actions -----------------------------------------------------------------------
            if (header is DmgHeader || header is AgbHeader) {
                Button(
                    onClick = { device.backupSave(); onBackupStarted() },
                    enabled = state.backupAvailable && !state.busy && state.restore == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp))
                    Text("Back up save", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(
                    onClick = { inLibrary?.let { onOpenGame(it.key) } },
                    enabled = inLibrary != null && state.restoreAvailable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null, Modifier.size(18.dp))
                    Text("Restore a backup...", Modifier.padding(start = 8.dp))
                }
                if (inLibrary == null) {
                    Text(
                        "No backup of this game yet. To restore another game's backup, open it in the Library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { device.importLimit()?.let { importLauncher.launch(arrayOf("*/*")) } },
                    enabled = state.backupAvailable && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Import a .sav file...") }
            }
            FilledTonalButton(
                onClick = { device.powerOffCart(); swap = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, Modifier.size(18.dp))
                Text("Swap cartridge", Modifier.padding(start = 8.dp))
            }
            TextButton(onClick = { device.powerOffCart() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Power off cart (before swapping)")
            }
            Text(
                "The cartridge stays powered after a read, backup or restore. Power it off before removing it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (swap) {
        SwapSheet(state, onDone = { swap = false })
    }
}

/** The guided swap (docs/UI_DESIGN.md 3.5): power off first, then say when removing the cartridge is safe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwapSheet(state: DeviceState, onDone: () -> Unit) {
    val off = !state.cartPowered && state.platform == null
    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, Modifier.size(32.dp))
                Text(if (off) "Cartridge power is off" else "Powering the cartridge off...", style = MaterialTheme.typography.titleLarge)
            }
            if (off) {
                Text("It is safe to remove the cartridge now. Insert the new cartridge, then tell Coincell what kind it is.")
                Text(
                    "Remove the cartridge only while power is off. The status bar shows \"Reader connected, cart power off\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onDone, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose cartridge type") }
            } else if (state.error != null) {
                Text(state.error)
                OutlinedButton(onClick = onDone, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close") }
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ReaderCard(state: DeviceState, onDisconnect: () -> Unit, onDetails: () -> Unit) {
    val fw = state.firmware ?: return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Reader")
            Text("${fw.deviceName ?: "GBFlash"}  ·  firmware ${fw.versionString}, PCB ${fw.pcbLabel}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDisconnect, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Disconnect (cart power off)") }
                TextButton(onClick = onDetails, Modifier.heightIn(min = 48.dp)) { Text("Details") }
            }
        }
    }
}

/**
 * GBA save type: why the app believes it, the "Detect" probe for unknown cartridges, and the list to change it. The
 * probe is never automatic: it makes the brief Flash-ID writes described in the text.
 */
@Composable
private fun AgbSaveTypeControls(save: AgbSaveState, state: DeviceState, device: DeviceController) {
    var choose by remember { mutableStateOf(false) }
    save.detection.note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (save.effectiveType == null) {
        StatusCard(
            "Save type unknown",
            "This cartridge is not in the FlashGBX database. Let the app probe the cartridge, or choose the save type yourself.",
            Severity.WARNING,
        )
    } else if (save.uncertain) {
        Text(
            "The save type comes from a probe, which can be wrong. Check it, or change it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (save.effectiveType == null || save.detection.source != org.noxeng.coincell.cart.SaveTypeSource.DATABASE) {
            OutlinedButton(onClick = device::detectAgbSaveType, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (save.probing) "Probing..." else "Detect save type")
            }
        }
        TextButton(onClick = { choose = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change save type") }
    }
    if (save.effectiveType == null || save.detection.source != org.noxeng.coincell.cart.SaveTypeSource.DATABASE) {
        Text(
            "Detecting briefly sends the Flash chip its ID command and, on SRAM carts, flips and restores one byte. Back up first if the save is precious.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (choose) {
        AlertDialog(
            onDismissRequest = { choose = false },
            title = { Text("Save type") },
            text = {
                Column {
                    val detected = save.detection.typeId
                    SaveTypeOption("Use the detected one (${detected?.let { AgbSaveTypes.label(it) } ?: "unknown"})", save.userChoice == null) {
                        choose = false
                        device.chooseAgbSaveType(null)
                    }
                    AgbSaveTypes.SUPPORTED.forEach { type ->
                        SaveTypeOption(AgbSaveTypes.label(type), save.userChoice == type) {
                            choose = false
                            device.chooseAgbSaveType(type)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SaveTypeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

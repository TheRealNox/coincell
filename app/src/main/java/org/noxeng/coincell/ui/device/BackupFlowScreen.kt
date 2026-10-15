package org.noxeng.coincell.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.device.BackupUi
import org.noxeng.coincell.device.ConnectionStatus
import org.noxeng.coincell.library.BackupEntry
import org.noxeng.coincell.library.LibraryStore
import org.noxeng.coincell.ui.LocalShell
import org.noxeng.coincell.ui.ScreenFrame
import org.noxeng.coincell.ui.components.BackupChips
import org.noxeng.coincell.ui.components.MonoText
import org.noxeng.coincell.ui.components.Severity
import org.noxeng.coincell.ui.components.StatusCard
import org.noxeng.coincell.ui.components.crcText
import org.noxeng.coincell.ui.components.formatDateTime
import org.noxeng.coincell.ui.components.formatSizeKiB
import org.noxeng.coincell.ui.theme.GbfTheme
import kotlinx.coroutines.launch

/** Keeps the screen on while a cartridge operation runs. */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Backup progress and result (docs/UI_DESIGN.md 4.3). Full screen, no bottom bar, no status bar: this screen is
 * the detailed view of the operation.
 */
@Composable
fun BackupFlowScreen(
    onClose: () -> Unit,
    onViewInLibrary: (BackupEntry) -> Unit,
) {
    val shell = LocalShell.current
    val device = shell.device
    val state by device.state.collectAsState()
    val backup = state.backup
    val running = backup is BackupUi.Running
    // Nothing to show: the RTC question was dismissed or the flow was left.
    LaunchedEffect(backup == null && state.rtcPrompt == null) {
        if (backup == null && state.rtcPrompt == null) onClose()
    }
    val close = { device.closeBackup(); onClose() }
    BackHandler(enabled = running) { /* Cancel is the explicit button; back never hides a running operation. */ }
    BackHandler(enabled = !running) { close() }
    ScreenFrame(
        title = when (backup) {
            is BackupUi.Running, null -> "Backing up"
            is BackupUi.Result -> "Backup complete"
            is BackupUi.Failed -> "Backup failed"
            BackupUi.Cancelled -> "Backup cancelled"
        },
        statusBar = false,
        onBack = null,
        actions = {
            IconButton(onClick = { if (running) device.cancelBackup() else close() }) {
                Icon(Icons.Filled.Close, contentDescription = if (running) "Cancel backup" else "Close")
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (backup) {
                null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Waiting for the answer to the clock question.
                    CircularProgressIndicator()
                }
                is BackupUi.Running -> RunningContent(backup, onCancel = device::cancelBackup)
                is BackupUi.Result -> ResultContent(
                    backup,
                    connected = state.status == ConnectionStatus.CONNECTED,
                    onViewInLibrary = { close(); onViewInLibrary(backup.entry) },
                    onPowerOff = { device.powerOffCart(); close() },
                    onDone = close,
                    store = shell.store,
                    onMessage = shell::message,
                )
                is BackupUi.Failed -> {
                    StatusCard("The backup failed", backup.message + "\n\nNothing was stored.", Severity.ERROR)
                    Text(
                        "Likely causes: dirty contacts, a weak battery, or the cartridge moved. Clean the gold contacts with isopropyl alcohol and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { device.closeBackup(); device.backupSave() },
                        enabled = state.status == ConnectionStatus.CONNECTED && state.backupAvailable && !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Try again") }
                    OutlinedButton(onClick = { device.powerOffCart(); close() }, enabled = state.status == ConnectionStatus.CONNECTED, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Power off cart")
                    }
                    TextButton(onClick = close, Modifier.heightIn(min = 48.dp)) { Text("Back") }
                }
                BackupUi.Cancelled -> {
                    StatusCard("Backup cancelled", "Nothing was stored.", Severity.INFO)
                    Button(onClick = close, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun RunningContent(b: BackupUi.Running, onCancel: () -> Unit) {
    KeepScreenOn()
    val (done, total) = b.progress
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val percent = (fraction * 100).toInt()
    // Announced to TalkBack in 10 % steps only.
    val spoken = "${percent / 10 * 10} percent"
    Box(
        Modifier.size(220.dp).semantics {
            stateDescription = spoken
            liveRegion = LiveRegionMode.Polite
        },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 12.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent %", style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
            Text("${done / 1024} / ${total / 1024} KiB", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
    Text(b.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    org.noxeng.coincell.ui.components.HeaderTitleLine(b.headerTitle)
    Text(
        "Reading and verifying: every block is read twice.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        "Keep the reader plugged in. No save data is written to the cartridge.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    OutlinedButton(onClick = onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultContent(
    r: BackupUi.Result,
    connected: Boolean,
    onViewInLibrary: () -> Unit,
    onPowerOff: () -> Unit,
    onDone: () -> Unit,
    store: LibraryStore,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf(r.entry) }
    var noteDialog by remember { mutableStateOf(false) }
    val e = entry
    val success = GbfTheme.extended.success
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = null,
        Modifier.size(64.dp),
        tint = if (e.suspect) MaterialTheme.colorScheme.tertiary else success.onContainer,
    )
    Text(if (e.suspect) "Stored, but it looks empty" else "Backup complete", style = MaterialTheme.typography.headlineMedium)
    Text(r.title, style = MaterialTheme.typography.titleLarge)
    org.noxeng.coincell.ui.components.HeaderTitleLine(r.headerTitle)
    Text(formatDateTime(e.timestampMillis), style = MaterialTheme.typography.bodyMedium)
    MonoText("${formatSizeKiB(e.size)}   CRC32 ${crcText(e.crc32)}")
    BackupChips(e)
    e.suspectReason?.let {
        StatusCard(
            "This save looks empty",
            "$it\n\nStored anyway so you can check it. Is the battery dead, or is this game just unplayed?",
            Severity.WARNING,
        )
    }
    if (e.saveType != null) {
        Text(
            "Save type: ${org.noxeng.coincell.cart.AgbSaveTypes.label(e.saveType)}${e.saveTypeSource?.let { " (from ${it.label})" } ?: ""}",
            style = MaterialTheme.typography.bodyMedium,
        )
        e.flashChip?.let { Text("Flash chip: $it", style = MaterialTheme.typography.bodyMedium) }
    }
    if (e.clock == null && e.rtcDeclared) {
        StatusCard("Clock not read", "The header declares an RTC but it could not be read.", Severity.WARNING)
    }
    if (e.clock != null && e.rtcProbePresent == false) {
        StatusCard(
            "Clock stored",
            "The RTC probe saw no clock (a freshly reset RTC looks absent), but the header declares one, so it was stored. Export without the clock if unwanted.",
            Severity.INFO,
        )
    }
    r.warnings.forEach { StatusCard("Note", it, Severity.INFO) }
    if (e.note.isNotEmpty()) Text("Note: ${e.note}", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { noteDialog = true }, Modifier.heightIn(min = 48.dp)) { Text(if (e.note.isEmpty()) "Add note" else "Edit note") }

    Button(onClick = onViewInLibrary, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View in library") }
    FilledTonalButton(onClick = onPowerOff, enabled = connected, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Power off cart")
    }
    TextButton(onClick = onDone, Modifier.heightIn(min = 48.dp)) { Text("Done") }
    Text(
        "The cartridge is still powered. Power it off before removing it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (noteDialog) {
        var text by remember { mutableStateOf(e.note) }
        AlertDialog(
            onDismissRequest = { noteDialog = false },
            title = { Text("Note") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(LibraryStore.NOTE_MAX_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note for this backup") },
                    supportingText = { Text("${text.length} / ${LibraryStore.NOTE_MAX_LENGTH}") },
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    noteDialog = false
                    scope.launch {
                        try {
                            entry = store.updateNote(e, text)
                        } catch (ex: Exception) {
                            onMessage("Could not save the note: ${ex.message}")
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { noteDialog = false }) { Text("Cancel") } },
        )
    }
}

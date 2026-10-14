package com.therealnox.gbflash.ui.restore

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.therealnox.gbflash.ui.ScreenFrame
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.AgbSaveTypes
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.device.ConnectionStatus
import com.therealnox.gbflash.device.DeviceController
import com.therealnox.gbflash.device.DeviceState
import com.therealnox.gbflash.device.RestoreUi
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.BackupKind
import com.therealnox.gbflash.restore.RestoreMatch
import com.therealnox.gbflash.restore.RestorePolicy
import com.therealnox.gbflash.restore.RestoreResult
import com.therealnox.gbflash.restore.RestoreReview
import com.therealnox.gbflash.restore.RestoreStatus
import com.therealnox.gbflash.save.RestorePhase
import kotlinx.coroutines.launch

private const val HOLD_MILLIS = 1500

/**
 * The restore flow as its own full-screen destination (docs/UI_DESIGN.md 3.4 / 4.7): no bottom bar, no
 * status bar (this screen is the detailed view of the operation). Behaviour is unchanged from milestone 3.
 */
@Composable
fun RestoreScreen(device: DeviceController, onClose: () -> Unit) {
    val state by device.state.collectAsState()
    val ui = state.restore
    // The flow ended (closed, or the reader went away): leave the screen.
    LaunchedEffect(ui == null) { if (ui == null) onClose() }
    ScreenFrame(
        title = "Restore to cartridge",
        statusBar = false,
        actions = {
            IconButton(onClick = device::closeRestore, enabled = !state.busy) {
                Icon(Icons.Filled.Close, contentDescription = "Close the restore")
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            RestoreFlow(state, device)
        }
    }
}

/** Back never interrupts a running cartridge operation; elsewhere it leaves the flow. */
@Composable
fun RestoreFlow(state: DeviceState, vm: DeviceController) {
    val ui = state.restore ?: return
    BackHandler { if (!state.busy) vm.closeRestore() }
    Column(Modifier.padding(0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Stepper(ui, flash = ui.isFlash())
        when (ui) {
            RestoreUi.Loading -> LoadingStep()
            is RestoreUi.Review -> ReviewStep(ui, vm)
            is RestoreUi.SafetyCopyRunning -> SafetyCopyStep(ui, vm)
            is RestoreUi.Stopped -> StoppedStep(ui, vm)
            is RestoreUi.Confirm -> ConfirmStep(ui, vm)
            is RestoreUi.Working -> WorkingStep(ui, vm)
            is RestoreUi.Done -> DoneStep(ui, state, vm)
        }
    }
}

@Composable
private fun LoadingStep() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text("Preparing the review...")
    }
}

@Composable
private fun Stepper(ui: RestoreUi, flash: Boolean) {
    val current = when (ui) {
        RestoreUi.Loading, is RestoreUi.Review -> 0
        is RestoreUi.SafetyCopyRunning, is RestoreUi.Stopped -> 1
        is RestoreUi.Confirm -> 1
        is RestoreUi.Working -> if (ui.phase == RestorePhase.WRITE) 2 else 3
        is RestoreUi.Done -> 4
    }
    val steps = listOf("Review", "Safety copy", if (flash) "Erase and write" else "Write", "Verify")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, label ->
            val mark = if (i < current) "✓ " else ""
            Text(
                mark + label + if (i < steps.lastIndex) "  >" else "",
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---- review ------------------------------------------------------------------------------------

@Composable
private fun ReviewStep(ui: RestoreUi.Review, vm: DeviceController) {
    val r = ui.review
    val h = r.header
    Section("On the cartridge now")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(h.title.ifEmpty { "(empty title)" }, style = MaterialTheme.typography.titleMedium)
            when (h) {
                is DmgHeader -> {
                    Text("GB / GBC, ${h.mapperName}")
                    Text("Global checksum 0x%04X, header checksum 0x%02X".format(h.globalChecksumStored, h.headerChecksumStored))
                    Text("Save space: ${h.save.sizeBytes?.let { RestorePolicy.formatSize(it) } ?: "unknown"}")
                }
                is AgbHeader -> {
                    Text("GBA, game code ${h.gameCode}")
                    Text("Header checksum 0x%02X".format(h.headerChecksumStored))
                    Text("Save type: ${r.agbSaveType?.let { AgbSaveTypes.label(it) } ?: "unknown"}")
                }
            }
            Text("Its current save will be replaced.", fontWeight = FontWeight.Bold)
            Text("Before anything is written, the save currently on the cartridge is backed up and checked (read twice), and kept in your library as a \"Pre-restore copy\". This step cannot be skipped.")
        }
    }
    Section("Will be written")
    EntryCard(r.source, r)
    MatchBlock(ui, r, vm)
    if (r.clockOptionOffered) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Real Time Clock", style = MaterialTheme.typography.titleMedium)
                Text("A Real Time Clock cartridge was detected. Do you want the Real Time Clock register values to be written as well?")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.selectable(selected = ui.restoreClock, role = Role.RadioButton) { vm.setRestoreClock(true) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.restoreClock, onClick = null)
                        Text("Yes", Modifier.padding(start = 6.dp, end = 16.dp))
                    }
                    Row(Modifier.selectable(selected = !ui.restoreClock, role = Role.RadioButton) { vm.setRestoreClock(false) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !ui.restoreClock, onClick = null)
                        Text("No", Modifier.padding(start = 6.dp))
                    }
                }
                Row(
                    Modifier.selectable(selected = ui.adjustClock, enabled = ui.restoreClock, role = Role.Checkbox) { vm.setAdjustClock(!ui.adjustClock) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ui.adjustClock, onCheckedChange = null, enabled = ui.restoreClock)
                    Text("Adjust RTC", Modifier.padding(start = 6.dp))
                }
                Text(
                    "Adjust RTC advances the stored clock by the time since the backup was made. Unticked, the stored values are written exactly. " +
                        "No leaves the cartridge clock alone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else if (r.clockNote.isNotEmpty()) {
        Text(r.clockNote, style = MaterialTheme.typography.bodySmall)
    } else if ((r.header as? DmgHeader)?.hasRtcDeclared == true) {
        Text("The cartridge clock will not be changed.", style = MaterialTheme.typography.bodySmall)
    }
    if (ui.undoOf != null) {
        Text("This undoes the restore of ${ui.undoOf.id}: the save that was on the cartridge before it is written back.", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::closeRestore) { Text(if (r.canProceed) "Cancel" else "Choose a different backup") }
        if (r.canProceed) {
            Button(onClick = vm::continueRestore, enabled = RestorePolicy.mayStart(r, ui.typedTitle)) { Text("Continue") }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun EntryCard(e: BackupEntry, r: RestoreReview?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r?.sourceGame?.title?.ifEmpty { "(empty title)" } ?: e.gameKey, style = MaterialTheme.typography.titleMedium)
            Text("${formatTime(e.timestampMillis)}   [${e.kind.label}]")
            Text("${RestorePolicy.formatSize(e.size)}   CRC32 %08X".format(e.crc32))
            if (e.kind == BackupKind.IMPORTED) {
                Text("Imported from ${e.originalFileName ?: "a file"}; never compared with a cartridge.")
            } else if (e.verified) {
                Text("Verified when it was read (every chunk read twice).")
            }
            if (e.note.isNotEmpty()) Text("Note: ${e.note}")
            e.suspectReason?.let { Text("WARNING: $it", color = MaterialTheme.colorScheme.error) }
            e.saveType?.let { Text("Save type: ${AgbSaveTypes.label(it)}${e.saveTypeSource?.let { s -> " (${s.label})" } ?: ""}") }
            e.clock?.let { Text("Clock in backup: ${it.describe()}") }
        }
    }
}

@Composable
private fun MatchBlock(ui: RestoreUi.Review, r: RestoreReview, vm: DeviceController) {
    when (val m = r.match) {
        RestoreMatch.SameGame -> StatusCard("Same game", "Platform, title and checksum match.", error = false)
        is RestoreMatch.DifferentCartridge -> StatusCard(
            "Different cartridge of the same game",
            "The title and save size match, but the cartridge checksum differs (backup 0x%04X, this cartridge 0x%04X). ".format(m.sourceChecksum, m.cartChecksum) +
                "This is normal for a second copy or a reproduction cartridge. No typing is needed.",
            error = false,
        )
        is RestoreMatch.DifferentTitle -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("These are different games", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Backup: ${m.sourceTitle}\nCartridge: ${m.requiredTitle}", color = MaterialTheme.colorScheme.onErrorContainer)
                    Text("Writing this may corrupt the game or make it unplayable. Type ${m.requiredTitle} to continue.", color = MaterialTheme.colorScheme.onErrorContainer)
                    OutlinedTextField(
                        value = ui.typedTitle,
                        onValueChange = vm::setRestoreTitle,
                        label = { Text(m.requiredTitle) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is RestoreMatch.Blocked -> StatusCard("This restore is blocked", m.reason, error = true)
    }
}

@Composable
private fun StatusCard(title: String, body: String, error: Boolean) {
    val container = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = content)
            Text(body, color = content)
        }
    }
}

// ---- safety copy / stopped ---------------------------------------------------------------------

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun SafetyCopyStep(ui: RestoreUi.SafetyCopyRunning, vm: DeviceController) {
    KeepScreenOn()
    val (done, total) = ui.progress
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Phase 1 of 3: safety copy", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("Reading and checking the current save of the cartridge ($done / $total bytes). Nothing is written in this phase.")
            DontUnplug()
            OutlinedButton(onClick = vm::stopRestore) { Text("Cancel (nothing has been written)") }
        }
    }
}

@Composable
private fun StoppedStep(ui: RestoreUi.Stopped, vm: DeviceController) {
    StatusCard(if (ui.cancelled) "Restore cancelled" else "Restore did not start", ui.message + "\nThe cartridge was not changed.", error = !ui.cancelled)
    Button(onClick = vm::closeRestore) { Text("Done") }
}

@Composable
private fun DontUnplug() {
    Text("Don't unplug the reader or remove the cartridge. Keep this screen open.", fontWeight = FontWeight.Bold)
}

// ---- confirm -----------------------------------------------------------------------------------

@Composable
private fun ConfirmStep(ui: RestoreUi.Confirm, vm: DeviceController) {
    var showDialog by remember { mutableStateOf(false) }
    val r = ui.request.review
    val safety = ui.safety.entry
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Safety copy ready", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ui.safety.reusedExisting) {
                    "The cartridge holds exactly the save of ${safety.id} (read twice and compared), so that backup is the safety copy and no new copy was needed."
                } else {
                    "The current save was backed up as ${safety.id} (CRC32 %08X, ${RestorePolicy.formatSize(safety.size)}, verified).".format(safety.crc32)
                },
            )
        }
    }
    Text("Ready to write", style = MaterialTheme.typography.titleMedium)
    Text("Write the ${r.source.kind.label.lowercase()} of ${formatTime(r.source.timestampMillis)} (CRC32 %08X) to the cartridge ${r.header.title.ifEmpty { "(empty title)" }}.".format(r.source.crc32))
    if (ui.request.restoreClock && r.clockOptionOffered) {
        Text(if (ui.request.adjustClock) "The cartridge clock will also be set (adjusted by the elapsed time)." else "The cartridge clock will also be set to the stored values.")
    }
    HoldToConfirmButton(
        label = "Hold to restore",
        onConfirm = vm::confirmRestoreWrite,
        onOpenDialog = { showDialog = true },
    )
    Text("Press and hold for 1.5 seconds. With TalkBack, double tap or use the \"Restore now\" action to get a confirmation dialog.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = vm::closeRestore) { Text("Cancel") }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Write to the cartridge?") },
            text = { Text("This replaces the save on the cartridge with the backup of ${formatTime(r.source.timestampMillis)}. The previous save is kept as ${safety.id}.") },
            confirmButton = {
                TextButton(onClick = { showDialog = false; vm.confirmRestoreWrite() }) {
                    Text("Restore now", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Press and hold for [HOLD_MILLIS] to fire [onConfirm]; releasing earlier resets it. For TalkBack and
 * other accessibility services the same control exposes click, long click and a custom action that
 * open [onOpenDialog] (a normal confirmation dialog) instead.
 */
@Composable
private fun HoldToConfirmButton(label: String, onConfirm: () -> Unit, onOpenDialog: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                            onConfirm()
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            if (progress.value < 1f) {
                                job.cancel()
                                scope.launch { progress.snapTo(0f) }
                            }
                        }
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Open confirmation dialog") { onOpenDialog(); true }
                onLongClick(label = "Open confirmation dialog") { onOpenDialog(); true }
                customActions = listOf(CustomAccessibilityAction("Restore now") { onOpenDialog(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

// ---- write / verify ----------------------------------------------------------------------------

@Composable
private fun WorkingStep(ui: RestoreUi.Working, vm: DeviceController) {
    val flash = ui.isFlash()
    KeepScreenOn()
    var askStop by remember { mutableStateOf(false) }
    val (done, total) = ui.progress
    val writing = ui.phase == RestorePhase.WRITE
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (writing) (if (flash) "Phase 2 of 3: erasing and writing the save, sector by sector" else "Phase 2 of 3: writing the save") else "Phase 3 of 3: verifying (reading everything back twice)",
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("$done / $total bytes (${if (total > 0) done * 100 / total else 0}%)")
            if (writing && flash) Text("Flash memory is erased one sector at a time before it is written, so this takes longer than other save types (up to a few minutes).")
            DontUnplug()
            if (writing) {
                if (ui.stopRequested) {
                    Text("Stopping after the current block...")
                } else {
                    TextButton(onClick = { askStop = true }) { Text("Stop...", color = MaterialTheme.colorScheme.error) }
                }
            } else {
                Text("The read-back cannot be stopped; it only reads.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (askStop) {
        AlertDialog(
            onDismissRequest = { askStop = false },
            title = { Text("Stop writing?") },
            text = {
                Text(
                    "Stopping now leaves the cartridge partly written: part of the old save and part of the new one. " +
                        "The game may not load. Restore the safety copy ${ui.safety.entry.id} (or run this restore again) to repair it.",
                )
            },
            confirmButton = { TextButton(onClick = { askStop = false }) { Text("Keep writing") } },
            dismissButton = {
                TextButton(onClick = { askStop = false; vm.stopRestore() }) { Text("Stop anyway", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

// ---- result ------------------------------------------------------------------------------------

@Composable
private fun DoneStep(ui: RestoreUi.Done, state: DeviceState, vm: DeviceController) {
    val r = ui.result
    val ok = r.status == RestoreStatus.VERIFIED
    val title = when (r.status) {
        RestoreStatus.VERIFIED -> "Restored and verified"
        RestoreStatus.VERIFY_MISMATCH -> "Restore failed: cartridge contents uncertain"
        RestoreStatus.FAILED_UNCERTAIN -> "Restore failed: cartridge contents uncertain"
        RestoreStatus.CANCELLED_PARTIAL -> "Restore stopped: cartridge partly written"
        RestoreStatus.NOT_WRITTEN -> "Nothing was written"
    }
    StatusCard(title, r.message, error = !ok && r.status != RestoreStatus.NOT_WRITTEN)
    if (ok) {
        Text("Read-back matches the backup byte for byte: CRC32 %08X".format(r.readBackCrc32 ?: r.expectedCrc32), fontWeight = FontWeight.Bold)
        r.clockLine?.let { Text(it) }
        Text(
            if (r.safetyCopyReused) "No new safety copy was needed (${r.safetyCopy.id})." else "Safety copy kept in your library: ${r.safetyCopy.id}.",
        )
    } else if (r.wroteData) {
        SafetyPointer(r)
    }
    r.warnings.forEach { Text("Note: $it") }
    if (!ok && r.wroteData) {
        Text("What to do: keep the reader connected, read the header again, and restore the safety copy below. Likely causes: dirty contacts, weak battery, a loose cartridge.", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::closeRestore, enabled = !state.busy) { Text("Done") }
        OutlinedButton(onClick = { vm.powerOffCart() }, enabled = !state.busy && state.cartPowered) { Text("Power off cart") }
        val canRestoreAgain = state.status == ConnectionStatus.CONNECTED && state.header != null && !state.busy
        if (ok) {
            TextButton(onClick = vm::undoRestore, enabled = canRestoreAgain) { Text("Undo restore") }
        } else if (r.wroteData) {
            Button(
                onClick = { vm.restoreSafetyCopyAfterFailure(r) },
                enabled = canRestoreAgain,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Restore safety copy") }
        }
    }
    if (ok) Text("\"Undo restore\" writes the previous save back through the same steps (review, safety check, hold to confirm, write, verify).", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SafetyPointer(r: RestoreResult) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your previous save is safe", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            SelectionContainer {
                Text(
                    "Safety copy ${r.safetyCopy.id} (CRC32 %08X, ${RestorePolicy.formatSize(r.safetyCopy.size)}) is stored in your library. ".format(r.safetyCopy.crc32) +
                        "Restore it to put the old save back.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

private fun RestoreUi.isFlash(): Boolean {
    val review = when (this) {
        is RestoreUi.Review -> review
        is RestoreUi.Working -> request.review
        is RestoreUi.Confirm -> request.review
        is RestoreUi.SafetyCopyRunning -> request.review
        is RestoreUi.Stopped -> request.review
        is RestoreUi.Done -> request.review
        RestoreUi.Loading -> return false
    }
    return review.agbSaveType?.let { AgbSaveTypes.isFlash(it) } == true
}

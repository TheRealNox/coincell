package com.therealnox.gbflash.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameSummary
import com.therealnox.gbflash.save.SaveCheck
import com.therealnox.gbflash.ui.LocalShell
import com.therealnox.gbflash.ui.components.crcText
import com.therealnox.gbflash.ui.components.formatDateTime
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entry points for the actions that need a dialog, a sheet or the system file picker. */
class BackupActions(
    val export: (BackupEntry) -> Unit,
    val delete: (BackupEntry) -> Unit,
)

/** `<Title>_<yyyy-MM-dd_HH-mm>.sav` (docs/UI_DESIGN.md 3.3). */
internal fun exportName(title: String, millis: Long): String {
    val base = SaveCheck.exportFileName(title).removeSuffix(".sav")
    return base + "_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(millis)) + ".sav"
}

/**
 * Export (RTC choice sheet, then the system file picker) and delete (confirm dialog, then snackbar Undo).
 * Neither needs the reader. [game] is used to say whether the backup is the last one of its game.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberBackupActions(game: GameSummary?, onDeleted: () -> Unit = {}): BackupActions {
    val shell = LocalShell.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportChoice by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteChoice by remember { mutableStateOf<BackupEntry?>(null) }
    var pending by remember { mutableStateOf<Pair<BackupEntry, Boolean>?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val job = pending
        pending = null
        if (uri != null && job != null) {
            val (entry, withRtc) = job
            scope.launch {
                try {
                    val bytes = shell.store.exportBytes(entry, withRtc)
                    withContext(Dispatchers.IO) {
                        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open the chosen file")
                        out.use { it.write(bytes) }
                    }
                    shell.message("Exported ${entry.id}${if (withRtc && entry.rtcIncluded) " with clock" else ""}")
                } catch (e: Exception) {
                    shell.message("Export failed: ${e.message}")
                }
            }
        }
    }
    fun start(entry: BackupEntry, withRtc: Boolean) {
        pending = entry to withRtc
        launcher.launch(exportName(game?.game?.title ?: "save", entry.timestampMillis))
    }

    exportChoice?.let { entry ->
        ModalBottomSheet(onDismissRequest = { exportChoice = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Export .sav", style = MaterialTheme.typography.titleLarge)
                Text("This backup includes the cartridge clock (RTC). Add it as a footer after the save (FlashGBX format: ${if (entry.agbRtc != null) 16 else 48} bytes), or export the save only?")
                Button(onClick = { exportChoice = null; start(entry, true) }, Modifier.fillMaxWidth()) { Text("Save + clock (${if (entry.agbRtc != null) 16 else 48} byte footer)") }
                OutlinedButton(onClick = { exportChoice = null; start(entry, false) }, Modifier.fillMaxWidth()) { Text("Save only") }
                TextButton(onClick = { exportChoice = null }, Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
    deleteChoice?.let { entry ->
        val last = game != null && game.backups.size <= 1
        AlertDialog(
            onDismissRequest = { deleteChoice = null },
            title = { Text("Delete this backup?") },
            text = {
                Text(
                    "${game?.title ?: "Backup"}, ${formatDateTime(entry.timestampMillis)} (CRC32 ${crcText(entry.crc32)}) will be removed from this phone. " +
                        "You can undo for a few seconds." +
                        if (last) "\n\nThis is the only backup of this game. The game will disappear from your library." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteChoice = null; shell.deleteBackup(entry); onDeleted() }) {
                    Text("Delete backup", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteChoice = null }) { Text("Keep") } },
        )
    }
    return remember(game) {
        BackupActions(
            export = { e -> if (e.rtcIncluded) exportChoice = e else start(e, false) },
            delete = { e -> deleteChoice = e },
        )
    }
}

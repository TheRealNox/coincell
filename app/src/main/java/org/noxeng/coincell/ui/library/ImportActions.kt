package org.noxeng.coincell.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import org.noxeng.coincell.library.DisplayPlatform
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.GameSummary
import org.noxeng.coincell.library.ImportGuess
import org.noxeng.coincell.library.ImportGuesses
import org.noxeng.coincell.save.SaveImport
import org.noxeng.coincell.ui.LocalShell
import org.noxeng.coincell.ui.components.displayName
import org.noxeng.coincell.ui.components.formatSizeKiB
import org.noxeng.coincell.ui.components.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file read from the picker, waiting for the user to say what it is. */
private class PickedFile(val name: String, val bytes: ByteArray, val guess: ImportGuess)

/**
 * "Import a .sav file..." from the Library: the system file picker, then a dialog for the platform and the name
 * (pre-filled for Pokemon saves). The file becomes the first backup of a new game made from a file. Returns the
 * function that opens the picker; [onImported] gets the new game's key.
 */
@Composable
fun rememberImportNewGame(onImported: (String) -> Unit): () -> Unit {
    val shell = LocalShell.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<PickedFile?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    displayName(context, uri) to readLimited(context, uri, SaveImport.MAX_LOOSE_FILE)
                }
                if (bytes.size > SaveImport.MAX_LOOSE_FILE) {
                    shell.message("$name is larger than any cartridge save, so it is not a save file.")
                } else {
                    picked = PickedFile(name, bytes, withContext(Dispatchers.Default) { ImportGuesses.of(name, bytes) })
                }
            } catch (e: Exception) {
                shell.message("Could not read the file: ${e.message}")
            }
        }
    }
    picked?.let { file ->
        ImportNewGameDialog(
            file,
            onDismiss = { picked = null },
            onImport = { platform, gameName ->
                picked = null
                // In the app's scope: leaving the Library meanwhile must not drop the message or the navigation.
                shell.launch {
                    try {
                        val entry = shell.store.importNewGame(platform, gameName, file.name, file.bytes, shell.device.appVersion)
                        shell.message("Imported ${file.name}")
                        onImported(entry.gameKey)
                    } catch (e: Exception) {
                        shell.message("Not imported: ${e.message}")
                    }
                }
            },
        )
    }
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
private fun ImportNewGameDialog(file: PickedFile, onDismiss: () -> Unit, onImport: (DisplayPlatform, String) -> Unit) {
    var platform by remember { mutableStateOf(file.guess.platform) }
    var name by remember { mutableStateOf(file.guess.name) }
    val check = platform?.let { SaveImport.parseLoose(file.bytes, gba = it == DisplayPlatform.GBA) }
    val ok = check as? SaveImport.Result.Ok
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a save") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${file.name} (${file.bytes.size} bytes)", style = MaterialTheme.typography.bodyMedium)
                Text("Platform", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplayPlatform.entries.forEach { p ->
                        FilterChip(selected = platform == p, onClick = { platform = p }, label = { Text(p.label) })
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(GameInfo.NAME_MAX_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Game name") },
                )
                when {
                    platform == null -> Hint("Pick the platform: the file size alone does not tell GB, GBC and GBA apart.")
                    check is SaveImport.Result.Rejected -> Text(check.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    ok != null -> Hint(
                        "${formatSizeKiB(ok.save.size)} save" + (if (ok.rtcFooter != null) " with clock data" else "") +
                            ". It is kept as an imported game until you link it to its cartridge.",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(platform!!, name.trim()) }, enabled = ok != null && name.isNotBlank()) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * "Import a .sav file..." on a game's page: the file must match that game's save (same size, clock footer only when
 * the game has a clock). No dialog: the picker, then a snackbar.
 */
@Composable
fun rememberImportIntoGame(game: GameSummary?): () -> Unit {
    val shell = LocalShell.current
    val context = LocalContext.current.applicationContext
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val g = game ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        // In the app's scope, like the other library writes: leaving the page meanwhile must not drop the result.
        shell.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    displayName(context, uri) to readLimited(context, uri, SaveImport.MAX_LOOSE_FILE)
                }
                shell.store.importIntoGame(g, name, bytes, shell.device.appVersion)
                shell.message("Imported $name")
            } catch (e: Exception) {
                shell.message("Not imported: ${e.message}")
            }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * "Link to the cartridge in the reader" for a game made from a file: a confirmation, then every backup moves into the
 * cartridge's game. Returns the function that asks; [onLinked] gets the cartridge game's key.
 */
@Composable
fun rememberLinkToCartridge(onLinked: (String) -> Unit): (GameSummary, GameInfo, Int) -> Unit {
    val shell = LocalShell.current
    var ask by remember { mutableStateOf<Triple<GameSummary, GameInfo, Int>?>(null) }
    ask?.let { (fileGame, cart, size) ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Link to ${cart.label}?") },
            text = {
                Text(
                    "${if (fileGame.count == 1) "The backup" else "The ${fileGame.count} backups"} of ${fileGame.title} move to " +
                        "${cart.label} (${cart.identityText}), the cartridge in the reader. From then on they are treated like " +
                        "this cartridge's backups. Nothing is written to the cartridge.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ask = null
                    // The imported game disappears when linked, and with it this screen: run in the app's scope.
                    shell.launch {
                        try {
                            val linked = shell.store.linkToCartridge(fileGame.key, cart, size)
                            shell.message("Linked to ${linked.label}")
                            onLinked(linked.key)
                        } catch (e: Exception) {
                            shell.message("Not linked: ${e.message}")
                        }
                    }
                }) { Text("Link") }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
        )
    }
    return { g, cart, size -> ask = Triple(g, cart, size) }
}

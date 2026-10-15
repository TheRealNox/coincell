package org.noxeng.coincell.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.library.GameInfo

/** The real cartridge header title, shown under a friendly name so the cartridge identity is always visible. */
@Composable
fun HeaderTitleLine(headerTitle: String?, modifier: Modifier = Modifier) {
    if (headerTitle == null) return
    Text(
        "Cartridge title: $headerTitle",
        modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Rename dialog: the field starts with the current label. [onSave] gets the typed name (already capped); a name equal
 * to the cartridge name, or blank, is the same as "Reset to cartridge name" (the caller passes null then).
 */
@Composable
fun RenameGameDialog(game: GameInfo, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(game.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(GameInfo.NAME_MAX_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = { Text("${text.length} / ${GameInfo.NAME_MAX_LENGTH}") },
                )
                Text(
                    "Only changes how this app labels the game. The cartridge and the backups are untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onSave(null) }, enabled = game.displayName != null) { Text("Reset to cartridge name") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = GameInfo.normalizeName(text)
                onSave(if (clean == null || clean == game.defaultLabel) null else clean)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

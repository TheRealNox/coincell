package com.therealnox.gbflash.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.BackupKind
import com.therealnox.gbflash.ui.theme.GbfTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- formatting ------------------------------------------------------------------------------

fun formatDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))

fun formatDateShort(millis: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))

fun formatSizeKiB(bytes: Int): String =
    if (bytes >= 1024 && bytes % 1024 == 0) "${bytes / 1024} KiB" else "$bytes B"

fun crcText(crc: Long): String = "%08X".format(crc)

// ---- text ------------------------------------------------------------------------------------

/** Data (CRC32, checksums, sizes, hex) in monospace with tabular figures (docs/UI_DESIGN.md 5.2). */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text,
        modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Label on the left, value on the right ("key-value card" row). */
@Composable
fun KeyValueRow(label: String, value: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer(Modifier.weight(1f)) {
            if (mono) MonoText(value) else Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- chips -----------------------------------------------------------------------------------

/**
 * Read-only status chip: icon plus text plus colour (never colour alone, docs/UI_DESIGN.md 5.4). The text is the
 * accessible name, the icon is decoration.
 */
@Composable
fun StatusChip(
    text: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun VerifiedChip() {
    val c = GbfTheme.extended.success
    StatusChip("Verified", Icons.Filled.CheckCircle, c.container, c.onContainer)
}

@Composable
fun NeutralChip(text: String, icon: ImageVector) {
    StatusChip(text, icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
}

@Composable
fun WarningChip(text: String, icon: ImageVector = Icons.Filled.Warning) {
    StatusChip(text, icon, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
}

/** The chips of one backup (Verified, Clock, Looks empty, Pre-restore copy, Imported ...). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupChips(e: BackupEntry, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (e.verified) VerifiedChip()
        if (e.rtcIncluded) {
            NeutralChip("Clock", Icons.Filled.Schedule)
        } else if (e.rtcDeclared) {
            WarningChip("Clock not read", Icons.Filled.Schedule)
        }
        if (e.suspect) WarningChip("Looks empty")
        when (e.kind) {
            BackupKind.PRE_RESTORE -> StatusChip(
                "Pre-restore copy", Icons.Filled.History,
                MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BackupKind.IMPORTED -> StatusChip(
                "Imported", Icons.Filled.Download,
                MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BackupKind.EDITED -> StatusChip(
                "Edited", Icons.Filled.Edit,
                MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
            )
            BackupKind.BACKUP -> Unit
        }
        // Imported and edited saves were never read from a cartridge, so "unverified" would say nothing.
        if (!e.verified && e.kind != BackupKind.IMPORTED && e.kind != BackupKind.EDITED) WarningChip("Unverified")
    }
}

// ---- cards -----------------------------------------------------------------------------------

enum class Severity { INFO, SUCCESS, WARNING, ERROR }

/** Status card: icon, title, one sentence, optional action row (docs/UI_DESIGN.md 5.3). */
@Composable
fun StatusCard(
    title: String,
    body: String?,
    severity: Severity,
    modifier: Modifier = Modifier,
    actions: @Composable (() -> Unit)? = null,
) {
    val extended = GbfTheme.extended
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when (severity) {
        Severity.INFO -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Icons.Filled.Info)
        Severity.SUCCESS -> Triple(extended.success.container, extended.success.onContainer, Icons.Filled.CheckCircle)
        Severity.WARNING -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, Icons.Filled.Warning)
        Severity.ERROR -> Triple(scheme.errorContainer, scheme.onErrorContainer, Icons.Filled.Error)
    }
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The severity is also spelled out for screen readers; the icon is decoration.
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (body != null) SelectionContainer { Text(body, style = MaterialTheme.typography.bodyMedium) }
            actions?.invoke()
        }
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.size(8.dp))

/** Decorative content that a screen reader should skip. */
fun Modifier.decorative(): Modifier = clearAndSetSemantics { }

fun Modifier.described(text: String): Modifier = semantics { contentDescription = text }

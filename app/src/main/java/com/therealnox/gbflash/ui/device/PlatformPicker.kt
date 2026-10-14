package com.therealnox.gbflash.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.therealnox.gbflash.device.shortName
import com.therealnox.gbflash.device.voltsLabel
import com.therealnox.gbflash.library.DisplayPlatform
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.ui.components.CartridgeGlyph
import com.therealnox.gbflash.ui.components.SectionHeader
import com.therealnox.gbflash.ui.components.Severity
import com.therealnox.gbflash.ui.components.StatusCard
import com.therealnox.gbflash.ui.components.StatusChip

/**
 * The platform picker (docs/UI_DESIGN.md 4.2). There is no default selection and nothing is remembered: the choice
 * lives in `remember` (not saveable), so a recreated screen starts empty again. A tile only opens the confirm
 * sheet; the cartridge is powered only by the button in that sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformPicker(enabled: Boolean, onConfirm: (CartPlatform) -> Unit) {
    var choice by remember { mutableStateOf<CartPlatform?>(null) }
    var compare by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Which cartridge is in the reader?", style = MaterialTheme.typography.titleLarge)
        Text(
            "Choose the type yourself. The reader cannot tell, and the wrong choice can damage a GBA cartridge.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PlatformTile(
                platform = CartPlatform.GB_GBC,
                glyph = DisplayPlatform.GB,
                title = "GB / GBC",
                subtitle = "Game Boy, Game Boy Color",
                description = "Game Boy and Game Boy Color cartridge, 5 volts. Warning: do not use for Game Boy Advance cartridges.",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { choice = CartPlatform.GB_GBC },
            )
            PlatformTile(
                platform = CartPlatform.GBA,
                glyph = DisplayPlatform.GBA,
                title = "GBA",
                subtitle = "Game Boy Advance",
                description = "Game Boy Advance cartridge, 3.3 volts.",
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { choice = CartPlatform.GBA },
            )
        }
        TextButton(onClick = { compare = true }, Modifier.heightIn(min = 48.dp)) { Text("How do I tell them apart?") }
    }

    choice?.let { p ->
        ModalBottomSheet(onDismissRequest = { choice = null }) {
            ConfirmPlatformSheet(
                platform = p,
                onConfirm = { choice = null; onConfirm(p) },
                onBack = { choice = null },
            )
        }
    }
    if (compare) {
        ModalBottomSheet(onDismissRequest = { compare = false }) { CompareSheet(onClose = { compare = false }) }
    }
}

@Composable
private fun PlatformTile(
    platform: CartPlatform,
    glyph: DisplayPlatform,
    title: String,
    subtitle: String,
    description: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            role = Role.Button
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 176.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Drawn to scale relative to each other: the GBA is clearly the smaller cartridge.
            Column(Modifier.heightIn(min = 72.dp), verticalArrangement = Arrangement.Center) { CartridgeGlyph(glyph, size = 80.dp) }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val scheme = MaterialTheme.colorScheme
            StatusChip(
                "Powers at ${platform.voltsLabel}",
                Icons.Filled.Bolt,
                if (platform == CartPlatform.GB_GBC) scheme.tertiaryContainer else scheme.secondaryContainer,
                if (platform == CartPlatform.GB_GBC) scheme.onTertiaryContainer else scheme.onSecondaryContainer,
            )
        }
    }
}

/** Different content per choice: GB / GBC is the dangerous mistake (5 V on a GBA cartridge), so it carries the warning. */
@Composable
private fun ConfirmPlatformSheet(platform: CartPlatform, onConfirm: () -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CartridgeGlyph(if (platform == CartPlatform.GBA) DisplayPlatform.GBA else DisplayPlatform.GB, size = 72.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${platform.shortName} cartridge", style = MaterialTheme.typography.titleLarge)
                StatusChip(platform.voltsLabel, Icons.Filled.Bolt, scheme.tertiaryContainer, scheme.onTertiaryContainer)
            }
        }
        if (platform == CartPlatform.GB_GBC) {
            StatusCard(
                title = "A GBA cartridge must NOT be in this mode",
                body = "This powers the cartridge with 5 V. 5 V can damage a Game Boy Advance cartridge.",
                severity = Severity.ERROR,
            )
            Text(
                "Is your cartridge wide, with a flat label and two cut corners (Game Boy or Game Boy Color)? " +
                    "GBA cartridges are small and narrow.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CartridgeGlyph(DisplayPlatform.GB, size = 64.dp)
                    Text("GB / GBC: wide", style = MaterialTheme.typography.labelMedium)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CartridgeGlyph(DisplayPlatform.GBA, size = 64.dp)
                    Text("GBA: small", style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Text(
                "Choose this only for a Game Boy Advance cartridge: small and narrow. The cartridge is powered with 3.3 V. " +
                    "If it turns out to be a GB or GBC cartridge, the read fails (blank logo) and you can pick again; 3.3 V does not harm those.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onConfirm, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Bolt, contentDescription = null, Modifier.size(18.dp))
            val label = if (platform == CartPlatform.GB_GBC) "Power on at 5 V (GB / GBC)" else "Power on at 3.3 V (GBA)"
            Text(label, Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back, I picked the wrong one") }
    }
}

@Composable
private fun CompareSheet(onClose: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("GB, GBC and GBA cartridges")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CartridgeGlyph(DisplayPlatform.GB, size = 96.dp)
                Text("GB / GBC", style = MaterialTheme.typography.labelLarge)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CartridgeGlyph(DisplayPlatform.GBA, size = 96.dp)
                Text("GBA", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            "GBA cartridges are about 3.5 x 6 cm. Game Boy and Game Boy Color cartridges are about 6.5 x 5.8 cm, " +
                "with notched bottom corners. When in doubt, choose GBA first: 3.3 V is the safe voltage, a GB or GBC cartridge just reads blank and you can pick again.",
        )
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                "Never power a GBA cartridge in GB / GBC mode: that is 5 V. The app never guesses and never remembers your choice.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = onClose, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close") }
    }
}

package org.noxeng.coincell.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.noxeng.coincell.device.OperationAction
import org.noxeng.coincell.device.StatusBarState
import org.noxeng.coincell.device.shortName
import org.noxeng.coincell.device.voltsLabel
import org.noxeng.coincell.ui.theme.GbfTheme

/** What the trailing button of the status bar does. */
enum class StatusAction { CONNECT, ALLOW, CHOOSE_TYPE, POWER_OFF, CANCEL, FIX, OPEN }

private class Look(
    val icon: ImageVector?,
    val text: String,
    val container: Color,
    val content: Color,
    val action: StatusAction?,
    val actionLabel: String?,
    val spoken: String,
)

/** Spoken form of the bar, e.g. "Cart powered, 5 volts, GB / GBC". */
fun statusBarDescription(s: StatusBarState): String = when (s) {
    is StatusBarState.NoReader -> "No reader connected"
    StatusBarState.PermissionNeeded -> "USB permission needed"
    StatusBarState.Connecting -> "Connecting to the reader"
    StatusBarState.ConnectedNoPlatform -> "Reader connected, cartridge power off"
    is StatusBarState.CartPowered ->
        if (s.platform != null) "Cart powered, ${s.platform.voltsLabel.replace(" V", " volts")}, ${s.platform.shortName}" else "Cart powered"
    is StatusBarState.Operation -> s.label + (s.fraction?.let { ", ${(it * 100).toInt()} percent" } ?: "")
    is StatusBarState.Error -> "Reader problem: ${s.message}"
}

/**
 * The Device Status Bar (docs/UI_DESIGN.md 2.2, 5.4): one 48 dp row under the top app bar on every screen that
 * does not own the screen with an operation. Tapping the body opens [onClick] (the Device tab or the running
 * operation); the trailing text button is the recovery or next action for the state.
 */
@Composable
fun DeviceStatusBar(
    state: StatusBarState,
    onClick: () -> Unit,
    onAction: (StatusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val powered = GbfTheme.extended.powered
    val look = when (state) {
        is StatusBarState.NoReader -> Look(Icons.Filled.UsbOff, "No reader connected", scheme.surfaceVariant, scheme.onSurfaceVariant, StatusAction.CONNECT, "Connect", statusBarDescription(state))
        StatusBarState.PermissionNeeded -> Look(Icons.Filled.Key, "USB permission needed", scheme.tertiaryContainer, scheme.onTertiaryContainer, StatusAction.ALLOW, "Allow", statusBarDescription(state))
        StatusBarState.Connecting -> Look(null, "Connecting...", scheme.surfaceVariant, scheme.onSurfaceVariant, null, null, statusBarDescription(state))
        StatusBarState.ConnectedNoPlatform -> Look(Icons.Filled.Usb, "Reader connected, cart power off", scheme.secondaryContainer, scheme.onSecondaryContainer, StatusAction.CHOOSE_TYPE, "Choose type", statusBarDescription(state))
        is StatusBarState.CartPowered -> Look(Icons.Filled.Power, "Cart powered", powered.container, powered.onContainer, StatusAction.POWER_OFF, "Power off", statusBarDescription(state))
        is StatusBarState.Operation -> Look(
            null, state.label, scheme.primaryContainer, scheme.onPrimaryContainer,
            when (state.action) {
                OperationAction.CANCEL -> StatusAction.CANCEL
                OperationAction.OPEN -> StatusAction.OPEN
                OperationAction.NONE -> null
            },
            when (state.action) {
                OperationAction.CANCEL -> "Cancel"
                OperationAction.OPEN -> "Open"
                OperationAction.NONE -> null
            },
            statusBarDescription(state),
        )
        is StatusBarState.Error -> Look(Icons.Filled.Error, "Reader problem: ${state.message}", scheme.errorContainer, scheme.onErrorContainer, StatusAction.FIX, "Fix", statusBarDescription(state))
    }
    val live = if (state is StatusBarState.Error || state is StatusBarState.CartPowered) LiveRegionMode.Assertive else LiveRegionMode.Polite
    Surface(modifier.fillMaxWidth(), color = look.container, contentColor = look.content) {
        Column {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The body is one focusable element with the composed description; the action button stays separate.
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onClick)
                        .clearAndSetSemantics {
                            contentDescription = look.spoken
                            liveRegion = live
                            role = Role.Button
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        look.icon != null -> Icon(look.icon, contentDescription = null, Modifier.size(24.dp))
                        else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = look.content)
                    }
                    Text(look.text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state is StatusBarState.CartPowered && state.platform != null) {
                        StatusChip(state.platform.voltsLabel, Icons.Filled.Bolt, look.content.copy(alpha = 0.16f), look.content)
                        Text(state.platform.shortName, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (look.action != null && look.actionLabel != null) {
                    TextButton(onClick = { onAction(look.action) }, Modifier.heightIn(min = 48.dp)) {
                        Text(look.actionLabel, color = look.content)
                    }
                }
            }
            if (state is StatusBarState.Operation) {
                val f = state.fraction
                val bar = Modifier.fillMaxWidth()
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = bar)
                } else {
                    LinearProgressIndicator(modifier = bar)
                }
            }
        }
    }
}

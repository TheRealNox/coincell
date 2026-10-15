package org.noxeng.coincell.device

import org.noxeng.coincell.protocol.CartPlatform
import org.noxeng.coincell.save.RestorePhase

/** What the Device Status Bar shows (docs/UI_DESIGN.md 5.4). One value per hardware state. */
sealed interface StatusBarState {
    /** No reader plugged in. Quiet, not an error. */
    data class NoReader(val detail: String?) : StatusBarState

    data object PermissionNeeded : StatusBarState

    data object Connecting : StatusBarState

    /** Reader open, no cartridge power, no platform chosen. */
    data object ConnectedNoPlatform : StatusBarState

    /** The cartridge is powered at the voltage of [platform]. The attention state. */
    data class CartPowered(val platform: CartPlatform?) : StatusBarState

    /** An operation is running; [fraction] null means indeterminate. */
    data class Operation(val label: String, val fraction: Float?, val action: OperationAction) : StatusBarState

    /** Connection failed. */
    data class Error(val message: String) : StatusBarState
}

/** The trailing action of an [StatusBarState.Operation] strip. */
enum class OperationAction {
    /** A backup or safety copy: stopping is safe. */
    CANCEL,

    /** Writing: stopping needs the confirmation dialog on the restore screen, so the strip only links there. */
    OPEN,
    NONE,
}

fun statusBarState(s: DeviceState): StatusBarState {
    val backup = s.backup
    val restore = s.restore
    return when {
        backup is BackupUi.Running -> StatusBarState.Operation(
            "Backing up ${backup.title}",
            fraction(backup.progress),
            OperationAction.CANCEL,
        )
        restore is RestoreUi.SafetyCopyRunning -> StatusBarState.Operation(
            "Safety copy before restore",
            fraction(restore.progress),
            OperationAction.CANCEL,
        )
        restore is RestoreUi.Working -> StatusBarState.Operation(
            if (restore.phase == RestorePhase.WRITE) "Restoring: writing" else "Restoring: verifying",
            fraction(restore.progress),
            OperationAction.OPEN,
        )
        s.busy -> StatusBarState.Operation(s.busyLabel ?: "Working", null, OperationAction.NONE)
        else -> when (s.status) {
            ConnectionStatus.NO_DEVICE -> StatusBarState.NoReader(s.statusDetail)
            ConnectionStatus.NEEDS_PERMISSION -> StatusBarState.PermissionNeeded
            ConnectionStatus.CONNECTING -> StatusBarState.Connecting
            ConnectionStatus.FAILED -> StatusBarState.Error(s.statusDetail ?: "Could not talk to the reader")
            ConnectionStatus.CONNECTED ->
                if (s.cartPowered) StatusBarState.CartPowered(s.platform) else StatusBarState.ConnectedNoPlatform
        }
    }
}

private fun fraction(p: Pair<Long, Long>): Float? =
    if (p.second > 0) (p.first.toFloat() / p.second).coerceIn(0f, 1f) else null

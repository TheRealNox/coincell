package com.therealnox.gbflash.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.therealnox.gbflash.device.DeviceController
import com.therealnox.gbflash.device.statusBarState
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.LibraryStore
import com.therealnox.gbflash.ui.components.DeviceStatusBar
import com.therealnox.gbflash.ui.components.StatusAction
import com.therealnox.gbflash.ui.components.formatDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What every screen can reach: the reader, the offline library, app-level messages and the status bar actions. */
class Shell(
    val device: DeviceController,
    val store: LibraryStore,
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    val onStatusAction: (StatusAction) -> Unit,
    val onStatusClick: () -> Unit,
) {
    /** A short message that outlives the screen that raised it. */
    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    /** Delete with Undo: the backup disappears at once, the files go when the snackbar expires (8 s). */
    fun deleteBackup(entry: BackupEntry) {
        scope.launch {
            try {
                store.deleteWithUndo(entry) {
                    val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
                        snackbar.showSnackbar(
                            message = "Deleted the backup of ${formatDateTime(entry.timestampMillis)}",
                            actionLabel = "Undo",
                            withDismissAction = true,
                            duration = SnackbarDuration.Indefinite,
                        )
                    }
                    result == SnackbarResult.ActionPerformed
                }
            } catch (e: Exception) {
                snackbar.showSnackbar("Delete failed: ${e.message}")
            }
        }
    }

    companion object {
        const val UNDO_WINDOW_MS = 8_000L
    }
}

val LocalShell = staticCompositionLocalOf<Shell> { error("No Shell provided") }

/**
 * Standard screen: top app bar, optionally the Device Status Bar under it, then the content. The outer
 * Scaffold in [AppShell] owns the bottom bar and the snackbars.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    statusBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val shell = LocalShell.current
    // Opaque, so screens never show through each other while a navigation transition runs.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = actions,
        )
        if (statusBar) {
            val device by shell.device.state.collectAsState()
            DeviceStatusBar(statusBarState(device), onClick = shell.onStatusClick, onAction = shell.onStatusAction)
        }
        content()
    }
}

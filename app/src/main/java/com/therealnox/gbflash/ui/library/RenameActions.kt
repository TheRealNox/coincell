package com.therealnox.gbflash.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.ui.LocalShell
import com.therealnox.gbflash.ui.components.RenameGameDialog
import kotlinx.coroutines.launch

/**
 * Hosts the rename dialog. Returns the function that opens it for a game; saving goes through
 * [com.therealnox.gbflash.library.LibraryStore.renameGame] and ends with a snackbar.
 */
@Composable
fun rememberRenameGame(): (GameInfo) -> Unit {
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<GameInfo?>(null) }
    target?.let { game ->
        RenameGameDialog(
            game = game,
            onDismiss = { target = null },
            onSave = { name ->
                target = null
                scope.launch {
                    try {
                        val updated = shell.store.renameGame(game.key, name)
                        shell.message(if (name == null) "Name reset to ${updated.label}" else "Renamed to ${updated.label}")
                    } catch (e: Exception) {
                        shell.message("Could not rename: ${e.message}")
                    }
                }
            },
        )
    }
    return { target = it }
}

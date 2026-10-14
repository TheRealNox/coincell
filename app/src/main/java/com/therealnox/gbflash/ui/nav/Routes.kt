package com.therealnox.gbflash.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes (docs/UI_DESIGN.md 2.3). */
@Serializable data object LibraryRoute

@Serializable data object DeviceRoute

@Serializable data object SettingsRoute

@Serializable data class GameRoute(val gameKey: String)

@Serializable data class BackupRoute(val gameKey: String, val backupId: String)

/** Backup progress and result; hides the bottom bar. */
@Serializable data object BackupFlowRoute

/** Restore review, safety copy, hold-to-confirm, write and result; hides the bottom bar. */
@Serializable data object RestoreRoute

@Serializable data object AdvancedRoute

@Serializable data object DebugLogRoute

@Serializable data object RawHeaderRoute

@Serializable data object GbaProbeRoute

@Serializable data object FirmwareRoute

@Serializable data object AboutRoute

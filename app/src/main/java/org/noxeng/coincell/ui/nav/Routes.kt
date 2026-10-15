package org.noxeng.coincell.ui.nav

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

// ---- Pokemon editor (docs/UI_DESIGN.md 4.8). The editor's ViewModel (the in-memory working copy) is scoped to the
// EditorRoute back stack entry, so the sub-screens below share it and a rotation keeps every edit.

/** The editor's save overview; the entry point. Hides the bottom bar like every screen that is not a tab. */
@Serializable data class EditorRoute(val gameKey: String, val backupId: String)

@Serializable data object EditorBoxesRoute

/** A Pokemon of the party ([box] = -1) or of a box, by place. */
@Serializable data class EditorPokemonRoute(val box: Int, val index: Int)

@Serializable data object EditorBagRoute

@Serializable data object EditorTrainerRoute

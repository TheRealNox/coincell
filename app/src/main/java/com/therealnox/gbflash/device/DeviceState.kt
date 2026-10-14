package com.therealnox.gbflash.device

import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.FirmwareInfo
import com.therealnox.gbflash.restore.RestoreRequest
import com.therealnox.gbflash.restore.RestoreResult
import com.therealnox.gbflash.restore.RestoreReview
import com.therealnox.gbflash.restore.SafetyCopy
import com.therealnox.gbflash.save.RestorePhase

enum class ConnectionStatus { NO_DEVICE, NEEDS_PERMISSION, CONNECTING, CONNECTED, FAILED }

/** [beforeStart]: asked before the backup starts; otherwise a running backup waits for the answer. */
data class RtcPrompt(val beforeStart: Boolean)

/** The backup flow screens (docs/UI_DESIGN.md 4.3). */
sealed interface BackupUi {
    data class Running(val title: String, val progress: Pair<Long, Long>) : BackupUi

    data class Result(val entry: BackupEntry, val title: String, val warnings: List<String>) : BackupUi

    data class Failed(val message: String) : BackupUi

    data object Cancelled : BackupUi
}

/** Where the restore flow is (docs/UI_DESIGN.md 3.4: review, safety copy, confirm, write, verify, result). */
sealed interface RestoreUi {
    /** The review is being prepared (reads `game.json`); nothing has touched the cartridge. */
    data object Loading : RestoreUi

    /** R1. Nothing has touched the cartridge. */
    data class Review(
        val review: RestoreReview,
        val typedTitle: String = "",
        /** "Write the RTC register values as well?" Yes (default whenever offered). */
        val restoreClock: Boolean = false,
        /** "Adjust RTC" checkbox, ticked by default (FlashGBX). */
        val adjustClock: Boolean = true,
        /** Set when this is an "Undo restore": the entry that was just written. */
        val undoOf: BackupEntry? = null,
    ) : RestoreUi

    /** R2. Reading the current save of the cartridge. Only reads. */
    data class SafetyCopyRunning(val request: RestoreRequest, val progress: Pair<Long, Long>) : RestoreUi

    /** The restore ended before any write (safety copy failed or was cancelled). */
    data class Stopped(val request: RestoreRequest, val message: String, val cancelled: Boolean) : RestoreUi

    /** R2b. Safety copy exists; waiting for the hold-to-confirm. */
    data class Confirm(val request: RestoreRequest, val safety: SafetyCopy.Ready) : RestoreUi

    /** R3 / R4. [phase] is WRITE or VERIFY. */
    data class Working(
        val request: RestoreRequest,
        val safety: SafetyCopy.Ready,
        val phase: RestorePhase,
        val progress: Pair<Long, Long>,
        val stopRequested: Boolean = false,
    ) : RestoreUi

    /** R5. */
    data class Done(val request: RestoreRequest, val result: RestoreResult) : RestoreUi
}

/** Everything the app knows about the reader and the inserted cartridge. One instance, observed by every screen. */
data class DeviceState(
    val status: ConnectionStatus = ConnectionStatus.NO_DEVICE,
    val statusDetail: String? = null,
    val firmware: FirmwareInfo? = null,
    /** Explicit user choice; null until picked, and forgotten at every power-off, disconnect and detach. Never inferred. */
    val platform: CartPlatform? = null,
    val busy: Boolean = false,
    /** What a busy state without its own progress is doing ("Reading header", ...). */
    val busyLabel: String? = null,
    val header: CartHeader? = null,
    val headerPlatform: CartPlatform? = null,
    /** Library identity of the GB / GBC cartridge whose header was read; null otherwise. */
    val cartGame: GameInfo? = null,
    val agbSaveHint: String? = null,
    val error: String? = null,
    /** Informational message (e.g. "Cartridge power is off"). */
    val info: String? = null,
    /** True when the header read allows a save backup for this cartridge. */
    val backupAvailable: Boolean = false,
    /** Why a backup is not possible (unsupported mapper, no RAM, ...). */
    val backupUnavailableReason: String? = null,
    val backup: BackupUi? = null,
    /** Set when the reader was unplugged while an operation ran; shown once as an alert. */
    val interrupted: String? = null,
    /** The restore flow, or null when none is open. */
    val restore: RestoreUi? = null,
    /** The RTC question of a backup, shown as a Yes/No dialog. */
    val rtcPrompt: RtcPrompt? = null,
    /** The cartridge is powered (after a header read, backup or restore) and should be powered off before swapping. */
    val cartPowered: Boolean = false,
) {
    /**
     * Restore needs a connected reader in GB / GBC mode and a header read in this session, because the
     * review compares the backup with that header.
     */
    val restoreAvailable: Boolean
        get() = status == ConnectionStatus.CONNECTED && platform == CartPlatform.GB_GBC &&
            headerPlatform == CartPlatform.GB_GBC && header is DmgHeader && backupAvailable && !busy && restore == null

    /** Why the restore buttons are disabled, or null when [restoreAvailable]. */
    val restoreUnavailableReason: String?
        get() = when {
            restoreAvailable -> null
            status != ConnectionStatus.CONNECTED -> "Connect the reader and read a cartridge to restore."
            busy || backup is BackupUi.Running || restore != null -> "Wait for the current operation to finish."
            platform == null || header == null -> "Choose the cartridge type on the Device tab to read the cartridge first."
            headerPlatform != CartPlatform.GB_GBC -> "Restore works with GB / GBC cartridges only."
            else -> backupUnavailableReason ?: "This cartridge cannot be restored to."
        }

    val backupRunning: Boolean get() = backup is BackupUi.Running
}

/** "5 V" / "3.3 V": the voltage the platform powers the cartridge with. */
val CartPlatform.voltsLabel: String get() = if (this == CartPlatform.GBA) "3.3 V" else "5 V"

/** Short name of a platform choice. */
val CartPlatform.shortName: String get() = if (this == CartPlatform.GBA) "GBA" else "GB / GBC"

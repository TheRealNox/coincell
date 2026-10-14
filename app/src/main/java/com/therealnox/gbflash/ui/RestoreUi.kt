package com.therealnox.gbflash.ui

import com.therealnox.gbflash.restore.RestoreRequest
import com.therealnox.gbflash.restore.RestoreResult
import com.therealnox.gbflash.restore.RestoreReview
import com.therealnox.gbflash.restore.SafetyCopy
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.save.RestorePhase

/** Where the restore flow is (docs/UI_DESIGN.md 3.4: review, safety copy, confirm, write, verify, result). */
sealed interface RestoreUi {
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

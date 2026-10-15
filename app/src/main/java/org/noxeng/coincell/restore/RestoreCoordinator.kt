package org.noxeng.coincell.restore

import org.noxeng.coincell.cart.CartHeader
import org.noxeng.coincell.cart.SaveTypeSource
import org.noxeng.coincell.library.BackupEntry
import org.noxeng.coincell.library.BackupKind
import org.noxeng.coincell.library.NewBackup
import org.noxeng.coincell.library.RestoreRecord
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.protocol.DeviceSession
import org.noxeng.coincell.protocol.HeaderChangedException
import org.noxeng.coincell.save.AgbBackupPlan
import org.noxeng.coincell.save.BackupCancelledException
import org.noxeng.coincell.save.BackupPlan
import org.noxeng.coincell.save.RestoreOutcome
import org.noxeng.coincell.save.RestorePhase
import org.noxeng.coincell.save.SaveCheck
import org.noxeng.coincell.save.VerifiedRamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A restore the user has reviewed. */
class RestoreRequest(
    val review: RestoreReview,
    /** What the user typed for the "different title" rule; ignored otherwise. */
    val typedTitle: String = "",
    /** The "Restore clock from backup" switch (default off). Ignored unless the review offers it. */
    val restoreClock: Boolean = false,
    /** "Adjust RTC": advance the clock by the time elapsed since the backup (only with [restoreClock]). */
    val adjustClock: Boolean = true,
    /**
     * Undo only: the entry that was just written and verified. If the safety-copy read shows the
     * cartridge still holds exactly these bytes, no further safety copy is stored.
     */
    val knownCartContent: BackupEntry? = null,
)

/** Result of phase 1. Only [Ready] lets [RestoreCoordinator.writeAndVerify] write. */
sealed interface SafetyCopy {
    /**
     * A verified copy of the current cartridge save exists in the library. [entry] is the stored
     * pre-restore copy, or (undo) the entry whose bytes the cartridge was just read to equal.
     */
    class Ready internal constructor(
        val entry: BackupEntry,
        /** True if nothing new was stored because the cartridge provably equals [entry]. */
        val reusedExisting: Boolean,
        internal val header: CartHeader,
        internal val sourceId: String,
    ) : SafetyCopy

    data object Cancelled : SafetyCopy

    data class Failed(val message: String) : SafetyCopy
}

enum class RestoreStatus { VERIFIED, VERIFY_MISMATCH, FAILED_UNCERTAIN, CANCELLED_PARTIAL, NOT_WRITTEN }

/** What the user is told after phase 2. */
class RestoreResult(
    val status: RestoreStatus,
    val message: String,
    /** Cartridge save memory was written at least partly. */
    val wroteData: Boolean,
    val source: BackupEntry,
    val safetyCopy: BackupEntry,
    val safetyCopyReused: Boolean,
    /** CRC32 of the bytes that were meant to end up on the cartridge. */
    val expectedCrc32: Long,
    /** CRC32 of the verified read-back (verified restores only: equals [expectedCrc32]). */
    val readBackCrc32: Long?,
    val bank: Int = -1,
    val offset: Int = -1,
    val clockLine: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val verified: Boolean get() = status == RestoreStatus.VERIFIED
}

/** A verified read of the cartridge's current save, whichever platform. */
private class CurrentSave(
    val data: ByteArray,
    val rtcFooter: ByteArray?,
    val rtcProbePresent: Boolean?,
    val rtcDeclared: Boolean,
    val saveType: Int? = null,
    val saveTypeSource: SaveTypeSource? = null,
    val flashChip: String? = null,
)

/**
 * Orchestrates a restore in two steps that the UI separates with the hold-to-confirm:
 * 1. [takeSafetyCopy]: a normal verified backup of whatever is on the cartridge, stored as `pre-restore`.
 *    Only reads. If it fails or is cancelled the restore ends here.
 * 2. [writeAndVerify]: needs the [SafetyCopy.Ready] of step 1, re-reads the header, writes, reads back.
 *
 * The matching rules are enforced here again ([RestorePolicy.mayStart]), whatever the UI showed. Works for GB / GBC
 * and for GBA cartridges; the platform follows the review's [RestoreTarget].
 */
class RestoreCoordinator internal constructor(
    private val session: DeviceSession,
    private val library: SaveLibrary,
    private val appVersion: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    suspend fun takeSafetyCopy(
        request: RestoreRequest,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
    ): SafetyCopy {
        val review = request.review
        require(RestorePolicy.mayStart(review, request.typedTitle)) { "This restore is not allowed: ${review.match}" }
        val target = review.target
        val source = review.source
        try {
            library.readSave(source) // refuse to even start from a corrupt library entry
        } catch (e: Exception) {
            return SafetyCopy.Failed("The backup to restore cannot be read from the library: ${e.message}")
        }
        val fw = session.firmware
        try {
            log("--- Safety copy before restoring ${source.id} ---")
            val result = readCurrentSave(target, isCancelled, onProgress)
            val known = request.knownCartContent
            if (known != null && sameContent(known, result.data, target)) {
                log("Cartridge still holds ${known.id}; no new safety copy needed")
                return SafetyCopy.Ready(known, true, review.header, source.id)
            }
            // Avoid piling up identical copies: if the library already holds this exact save (and the
            // clock values when the cartridge has one), that entry is the safety copy.
            val existing = library.list(review.cartGame.key).firstOrNull { entry ->
                (result.rtcFooter == null || entry.rtcIncluded) && sameContent(entry, result.data, target)
            }
            if (existing != null) {
                log("Cartridge save is identical to ${existing.id}; using it as the safety copy")
                return SafetyCopy.Ready(existing, true, review.header, source.id)
            }
            val entry = library.store(
                review.cartGame,
                NewBackup(
                    save = result.data,
                    rtcFooter = result.rtcFooter,
                    rtcProbePresent = result.rtcProbePresent,
                    rtcDeclared = result.rtcDeclared,
                    firmware = fw.versionString + (fw.deviceName?.let { " ($it)" } ?: ""),
                    appVersion = appVersion,
                    verified = true,
                    suspectReason = SaveCheck.suspectReason(result.data),
                    note = "Automatic copy before restoring the backup of ${formatDate(source.timestampMillis)} (${source.id})",
                    kind = BackupKind.PRE_RESTORE,
                    saveType = result.saveType,
                    saveTypeSource = result.saveTypeSource,
                    flashChip = result.flashChip,
                ),
            )
            log("Safety copy stored: ${entry.id} CRC32 %08X".format(entry.crc32))
            return SafetyCopy.Ready(entry, false, review.header, source.id)
        } catch (_: BackupCancelledException) {
            log("Safety copy cancelled; nothing was written")
            return SafetyCopy.Cancelled
        } catch (e: Exception) {
            log("Safety copy failed: $e")
            return SafetyCopy.Failed("The safety copy could not be made, so nothing was written: ${e.message}")
        }
    }

    private suspend fun readCurrentSave(target: RestoreTarget, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): CurrentSave =
        when (target) {
            is RestoreTarget.Dmg -> {
                val plan = target.plan as BackupPlan.Ready
                val r = session.backupDmgSave(plan, isCancelled, onProgress, log, nowSeconds = { nowMillis() / 1000 })
                CurrentSave(r.data, r.rtc?.toFooter(), r.rtcProbePresent, r.rtcDeclared)
            }
            is RestoreTarget.Agb -> {
                val plan = target.plan as AgbBackupPlan.Ready
                val r = session.backupAgbSave(plan, isCancelled, onProgress, log, nowSeconds = { nowMillis() / 1000 })
                CurrentSave(r.data, r.rtc?.toFooter(), r.rtcProbePresent, false, plan.saveType, plan.source, r.flashChipLabel)
            }
        }

    suspend fun writeAndVerify(
        request: RestoreRequest,
        safety: SafetyCopy.Ready,
        isCancelled: () -> Boolean,
        onProgress: (RestorePhase, done: Long, total: Long) -> Unit,
    ): RestoreResult {
        val review = request.review
        require(RestorePolicy.mayStart(review, request.typedTitle)) { "This restore is not allowed: ${review.match}" }
        require(safety.header == review.header && safety.sourceId == review.source.id) { "The safety copy belongs to a different restore" }
        val target = review.target
        val source = review.source
        val data: ByteArray
        val footer: ByteArray?
        try {
            data = library.readSave(source)
            footer = if (request.restoreClock && review.clockOptionOffered) library.readRtcFooter(source) else null
        } catch (e: Exception) {
            return finish(request, safety, RestoreStatus.NOT_WRITTEN, "The backup cannot be read from the library: ${e.message}", false, 0L, null)
        }
        val intended = when (target) {
            is RestoreTarget.Dmg -> VerifiedRamReader.normalise((target.plan as BackupPlan.Ready).mapper, data)
            is RestoreTarget.Agb -> data
        }
        val expectedCrc = SaveLibrary.crc32Of(intended)
        when (target) {
            is RestoreTarget.Dmg -> {
                val plan = target.plan as BackupPlan.Ready
                log("--- Restore ${source.id} (${plan.mapperName}, ${plan.saveSize} bytes, clock=${footer != null}) ---")
            }
            is RestoreTarget.Agb -> {
                val plan = target.plan as AgbBackupPlan.Ready
                log("--- Restore ${source.id} (${plan.saveLabel}, ${plan.saveSize} bytes, clock=${footer != null}) ---")
            }
        }
        val outcome = try {
            when (target) {
                is RestoreTarget.Dmg -> session.restoreDmgSave(
                    target.plan as BackupPlan.Ready, target.header, data, footer, request.adjustClock,
                    isCancelled, onProgress, log, nowSeconds = { nowMillis() / 1000 },
                )
                is RestoreTarget.Agb -> session.restoreAgbSave(
                    target.plan as AgbBackupPlan.Ready, target.header, data, footer, request.adjustClock,
                    isCancelled, onProgress, log, nowSeconds = { nowMillis() / 1000 },
                )
            }
        } catch (e: HeaderChangedException) {
            return finish(request, safety, RestoreStatus.NOT_WRITTEN, e.message ?: "The cartridge changed. Nothing was written.", false, expectedCrc, null)
        } catch (e: Exception) {
            log("Restore could not start: $e")
            return finish(request, safety, RestoreStatus.NOT_WRITTEN, "The restore could not start, nothing was written: ${e.message}", false, expectedCrc, null)
        }
        val copy = "The previous save is in your library as ${safety.entry.id}."
        val gba = target is RestoreTarget.Agb
        return when (outcome) {
            is RestoreOutcome.Verified -> finish(
                request, safety, RestoreStatus.VERIFIED,
                "Restored and verified. The cartridge holds exactly the backup (read back twice, CRC32 %08X).".format(outcome.crc32),
                true, expectedCrc, outcome.crc32,
                clockLine = clockLine(outcome),
                warnings = outcome.warnings + listOfNotNull(outcome.rtcError),
            )
            is RestoreOutcome.Mismatch -> finish(
                request, safety, RestoreStatus.VERIFY_MISMATCH,
                "Restore failed: cartridge contents uncertain. The cartridge does not hold what was written. " +
                    "First difference at ${where(gba, outcome.bank, outcome.offset)} (wrote 0x%02X, read 0x%02X); ${outcome.differingBytes} byte(s) differ. "
                        .format(outcome.expected, outcome.actual) + copy,
                true, expectedCrc, outcome.readBackCrc32, outcome.bank, outcome.offset, warnings = outcome.warnings,
            )
            is RestoreOutcome.Failed ->
                if (outcome.wroteData) {
                    finish(
                        request, safety, RestoreStatus.FAILED_UNCERTAIN,
                        "Restore failed: cartridge contents uncertain. ${outcome.message}" +
                            (if (outcome.bank >= 0) " (${where(gba, outcome.bank, outcome.offset)})" else "") + " " + copy,
                        true, expectedCrc, null, outcome.bank, outcome.offset, warnings = outcome.warnings,
                    )
                } else {
                    finish(request, safety, RestoreStatus.NOT_WRITTEN, "Nothing was written: ${outcome.message}", false, expectedCrc, null, warnings = outcome.warnings)
                }
            is RestoreOutcome.Cancelled ->
                if (outcome.wroteData) {
                    finish(
                        request, safety, RestoreStatus.CANCELLED_PARTIAL,
                        "Restore stopped: the cartridge is partly written and was not verified. Restore the safety copy ${safety.entry.id} or run this restore again.",
                        true, expectedCrc, null, warnings = outcome.warnings,
                    )
                } else {
                    finish(request, safety, RestoreStatus.NOT_WRITTEN, "Restore cancelled before anything was written.", false, expectedCrc, null, warnings = outcome.warnings)
                }
        }
    }

    /** DMG: "RAM bank 1 offset 0x0040"; GBA: "save offset 0x10040" (bank and offset in the 64 KiB window). */
    private fun where(gba: Boolean, bank: Int, offset: Int): String =
        if (gba) "save offset 0x%05X".format(bank * 0x10000 + offset) else "RAM bank $bank offset 0x%04X".format(offset)

    private fun clockLine(o: RestoreOutcome.Verified): String? = when {
        o.rtcError != null -> "Clock NOT restored: ${o.rtcError}"
        o.rtcWritten != null ->
            "Clock written: ${o.rtcWritten.describe()}" + (o.rtcReadBack?.let { "; reads back as ${it.describe()}" } ?: "")
        else -> null
    }

    private fun finish(
        request: RestoreRequest,
        safety: SafetyCopy.Ready,
        status: RestoreStatus,
        message: String,
        wrote: Boolean,
        expectedCrc: Long,
        readBackCrc: Long?,
        bank: Int = -1,
        offset: Int = -1,
        clockLine: String? = null,
        warnings: List<String> = emptyList(),
    ): RestoreResult {
        val review = request.review
        val outcomeName = when (status) {
            RestoreStatus.VERIFIED -> "verified"
            RestoreStatus.VERIFY_MISMATCH -> "verify-failed"
            RestoreStatus.FAILED_UNCERTAIN -> "failed-uncertain"
            RestoreStatus.CANCELLED_PARTIAL -> "cancelled-partial"
            RestoreStatus.NOT_WRITTEN -> "aborted-nothing-written"
        }
        log("Restore result: $outcomeName: $message")
        try {
            library.addRestoreRecord(
                review.cartGame.key,
                RestoreRecord(nowMillis(), review.source.id, safety.entry.id, outcomeName, readBackCrc ?: expectedCrc.takeIf { status == RestoreStatus.VERIFIED }, message),
            )
        } catch (e: Exception) {
            log("Could not write restores.json: $e")
        }
        return RestoreResult(
            status, message, wrote, review.source, safety.entry, safety.reusedExisting,
            expectedCrc, readBackCrc, bank, offset, clockLine, warnings,
        )
    }

    private fun sameContent(entry: BackupEntry, readData: ByteArray, target: RestoreTarget): Boolean = try {
        val stored = library.readSave(entry)
        when (target) {
            is RestoreTarget.Dmg -> {
                val mapper = (target.plan as BackupPlan.Ready).mapper
                VerifiedRamReader.normalise(mapper, stored).contentEquals(VerifiedRamReader.normalise(mapper, readData))
            }
            is RestoreTarget.Agb -> stored.contentEquals(readData)
        }
    } catch (_: Exception) {
        false
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
}

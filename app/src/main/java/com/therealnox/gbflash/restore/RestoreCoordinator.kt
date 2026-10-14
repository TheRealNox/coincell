package com.therealnox.gbflash.restore

import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.BackupKind
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.RestoreRecord
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.protocol.DeviceSession
import com.therealnox.gbflash.protocol.HeaderChangedException
import com.therealnox.gbflash.save.BackupCancelledException
import com.therealnox.gbflash.save.BackupPlan
import com.therealnox.gbflash.save.RestoreOutcome
import com.therealnox.gbflash.save.RestorePhase
import com.therealnox.gbflash.save.SaveCheck
import com.therealnox.gbflash.save.VerifiedRamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
        internal val header: DmgHeader,
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
    /** Cartridge SRAM was written at least partly. */
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

/**
 * Orchestrates a restore in two steps that the UI separates with the hold-to-confirm:
 * 1. [takeSafetyCopy]: a normal verified backup of whatever is on the cartridge, stored as `pre-restore`.
 *    Only reads. If it fails or is cancelled the restore ends here.
 * 2. [writeAndVerify]: needs the [SafetyCopy.Ready] of step 1, re-reads the header, writes, reads back.
 *
 * The matching rules are enforced here again ([RestorePolicy.mayStart]), whatever the UI showed.
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
        val plan = review.plan as BackupPlan.Ready
        val source = review.source
        try {
            library.readSave(source) // refuse to even start from a corrupt library entry
        } catch (e: Exception) {
            return SafetyCopy.Failed("The backup to restore cannot be read from the library: ${e.message}")
        }
        val fw = session.firmware
        try {
            log("--- Safety copy before restoring ${source.id} ---")
            val result = session.backupDmgSave(plan, isCancelled, onProgress, log, nowSeconds = { nowMillis() / 1000 })
            val known = request.knownCartContent
            if (known != null && sameContent(known, result.data, plan)) {
                log("Cartridge still holds ${known.id}; no new safety copy needed")
                return SafetyCopy.Ready(known, true, review.header, source.id)
            }
            val entry = library.store(
                review.cartGame,
                NewBackup(
                    save = result.data,
                    rtcFooter = result.rtc?.toFooter(),
                    rtcProbePresent = result.rtcProbePresent,
                    rtcDeclared = result.rtcDeclared,
                    firmware = fw.versionString + (fw.deviceName?.let { " ($it)" } ?: ""),
                    appVersion = appVersion,
                    verified = true,
                    suspectReason = SaveCheck.suspectReason(result.data),
                    note = "Automatic copy before restoring the backup of ${formatDate(source.timestampMillis)} (${source.id})",
                    kind = BackupKind.PRE_RESTORE,
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

    suspend fun writeAndVerify(
        request: RestoreRequest,
        safety: SafetyCopy.Ready,
        isCancelled: () -> Boolean,
        onProgress: (RestorePhase, done: Long, total: Long) -> Unit,
    ): RestoreResult {
        val review = request.review
        require(RestorePolicy.mayStart(review, request.typedTitle)) { "This restore is not allowed: ${review.match}" }
        require(safety.header == review.header && safety.sourceId == review.source.id) { "The safety copy belongs to a different restore" }
        val plan = review.plan as BackupPlan.Ready
        val source = review.source
        val data: ByteArray
        val footer: ByteArray?
        try {
            data = library.readSave(source)
            footer = if (request.restoreClock && review.clockOptionOffered) library.readRtcFooter(source) else null
        } catch (e: Exception) {
            return finish(request, safety, RestoreStatus.NOT_WRITTEN, "The backup cannot be read from the library: ${e.message}", false, 0L, null)
        }
        val intended = VerifiedRamReader.normalise(plan.mapper, data)
        val expectedCrc = SaveLibrary.crc32Of(intended)
        log("--- Restore ${source.id} (${plan.mapperName}, ${plan.saveSize} bytes, clock=${footer != null}) ---")
        val outcome = try {
            session.restoreDmgSave(plan, review.header, data, footer, request.adjustClock, isCancelled, onProgress, log, nowSeconds = { nowMillis() / 1000 })
        } catch (e: HeaderChangedException) {
            return finish(request, safety, RestoreStatus.NOT_WRITTEN, e.message ?: "The cartridge changed. Nothing was written.", false, expectedCrc, null)
        } catch (e: Exception) {
            log("Restore could not start: $e")
            return finish(request, safety, RestoreStatus.NOT_WRITTEN, "The restore could not start, nothing was written: ${e.message}", false, expectedCrc, null)
        }
        val copy = "The previous save is in your library as ${safety.entry.id}."
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
                    "First difference at RAM bank ${outcome.bank} offset 0x%04X (wrote 0x%02X, read 0x%02X); ${outcome.differingBytes} byte(s) differ. "
                        .format(outcome.offset, outcome.expected, outcome.actual) + copy,
                true, expectedCrc, outcome.readBackCrc32, outcome.bank, outcome.offset, warnings = outcome.warnings,
            )
            is RestoreOutcome.Failed ->
                if (outcome.wroteData) {
                    finish(
                        request, safety, RestoreStatus.FAILED_UNCERTAIN,
                        "Restore failed: cartridge contents uncertain. ${outcome.message}" +
                            (if (outcome.bank >= 0) " (RAM bank ${outcome.bank}, offset 0x%04X)".format(outcome.offset) else "") + " " + copy,
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

    private fun sameContent(entry: BackupEntry, readData: ByteArray, plan: BackupPlan.Ready): Boolean = try {
        val stored = VerifiedRamReader.normalise(plan.mapper, library.readSave(entry))
        stored.contentEquals(VerifiedRamReader.normalise(plan.mapper, readData))
    } catch (_: Exception) {
        false
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}

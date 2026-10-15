package org.noxeng.coincell.save

import org.noxeng.coincell.cart.AgbCartIo
import org.noxeng.coincell.cart.AgbRtcRead
import org.noxeng.coincell.cart.AgbRtcSnapshot
import org.noxeng.coincell.cart.AgbSaveTypes
import org.noxeng.coincell.protocol.ReadTimeoutException
import java.util.zip.CRC32

/** A completely read and double-checked GBA save, still only in memory. */
class AgbBackupResult internal constructor(
    /** The raw save, exactly the save type's size. Never contains the RTC footer. */
    val data: ByteArray,
    /** Clock values read from the cartridge, or null if no clock was stored. */
    val rtc: AgbRtcSnapshot?,
    /** Result of the clock probe; null when it was not run. */
    val rtcProbePresent: Boolean?,
    /** "Macronix MX29L010 (0xC209)" for Flash saves. */
    val flashChipLabel: String?,
    val warnings: List<String>,
) {
    val crc32: Long get() = CRC32().also { it.update(data) }.value
}

/**
 * GBA save backup (spec 6.1.2, 6.6, 6.8 "verify read"): everything is read into memory, each chunk twice, and
 * nothing is returned unless every chunk matched. The cartridge is never written except for the writes [AgbCartIo]
 * allows (Flash identify, bank select, GPIO clock registers). All access goes through [AgbCartIo].
 */
internal class AgbSaveBackup(
    private val io: AgbCartIo,
    private val prepare: () -> Unit,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val log: (String) -> Unit = {},
) {
    fun run(
        plan: AgbBackupPlan.Ready,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
        /** False: the user declined the clock; no probe and no clock read. */
        includeRtc: Boolean = true,
        /** Called (blocking) after the probe found a clock. Null: store it without asking. */
        askRtc: (() -> Boolean)? = null,
    ): AgbBackupResult {
        prepare()
        val warnings = mutableListOf<String>()
        val chip = if (AgbSaveTypes.isFlash(plan.saveType)) AgbFlashId.requireChip(io, plan.saveSize, log) else null
        val layout = AgbSaveLayout(plan.saveType, chip)

        var earlyPresent: Boolean? = null
        var wantRtc = includeRtc && plan.rtcCandidate
        if (wantRtc) {
            when (val p = probeRtc(warnings)) {
                is AgbRtcRead.Probe.Present -> {
                    earlyPresent = true
                    wantRtc = askRtc?.invoke() ?: true
                }
                is AgbRtcRead.Probe.Absent -> {
                    earlyPresent = false
                    wantRtc = false
                    log("No clock: ${p.reason}")
                }
                null -> wantRtc = false
            }
        }

        val data = AgbVerifiedReader(io, log, "Nothing was saved. Check the cartridge contacts and try again.")
            .readSave(layout, verify = true, isCancelled, onProgress)

        var rtc: AgbRtcSnapshot? = null
        if (wantRtc) {
            if (isCancelled()) throw BackupCancelledException()
            rtc = when (val p = probeRtc(warnings)) {
                is AgbRtcRead.Probe.Present -> try {
                    AgbRtcRead.snapshot(io, p, nowSeconds())
                } catch (e: ReadTimeoutException) {
                    warnings += "The clock could not be read (${e.message}); the backup has no clock."
                    null
                }
                is AgbRtcRead.Probe.Absent -> {
                    warnings += "The clock was found before the backup but not afterwards (${p.reason}); the backup has no clock."
                    null
                }
                null -> null
            }
        }
        return AgbBackupResult(data, rtc, earlyPresent, chip?.label, warnings)
    }

    private fun probeRtc(warnings: MutableList<String>): AgbRtcRead.Probe? = try {
        AgbRtcRead.probe(io, log)
    } catch (e: ReadTimeoutException) {
        warnings += "The clock probe got no answer (${e.message}); the backup has no clock."
        null
    }
}

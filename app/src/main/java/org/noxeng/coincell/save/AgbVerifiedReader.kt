package org.noxeng.coincell.save

import org.noxeng.coincell.cart.AgbCartIo
import org.noxeng.coincell.protocol.ReadTimeoutException

/**
 * Reads a GBA save with the double-read verification of spec 6.1.3: every chunk is read twice (full setup each
 * time), the two reads must be identical, and a few retries are allowed before the whole operation fails (spec
 * 8.5, fixed). Used by the backup, by the restore read-back and by the detection (with [verify] false, as
 * FlashGBX's detect run does). It only ever reads; the only writes it can cause are the bank selects of [AgbCartIo].
 */
internal class AgbVerifiedReader(
    private val io: AgbCartIo,
    private val log: (String) -> Unit,
    /** Appended to the failure message, e.g. "Nothing was saved." */
    private val failureHint: String,
) {
    /** Progress counts both reads when [verify], so [onProgress] ends at `saveSize * 2` (or `saveSize`). */
    fun readSave(
        layout: AgbSaveLayout,
        verify: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
    ): ByteArray {
        val reads = if (verify) 2 else 1
        val out = ByteArray(layout.saveSize)
        val total = layout.saveSize.toLong() * reads
        var done = 0L
        onProgress(0, total)
        val len = layout.bufferLen(restore = false)
        for (bank in 0 until layout.banks) {
            selectBank(layout, bank)
            val end = layout.bankLength(bank)
            for (pos in 0 until end step len) {
                val n = minOf(len, end - pos)
                val chunk = readChunk(layout, bank, pos, n, verify, done, total, isCancelled, onProgress)
                chunk.copyInto(out, bank * AgbSaveLayout.BANK_SIZE + pos)
                done += n.toLong() * reads
                onProgress(done, total)
            }
        }
        return out
    }

    /** Bank switch at the top of a bank iteration (LK:3611-3626); the 50 ms settle time is part of the call. */
    private fun selectBank(layout: AgbSaveLayout, bank: Int) {
        when (layout.bankSwitch) {
            AgbBankSwitch.NONE -> Unit
            AgbBankSwitch.BOOTLEG -> io.selectBootlegBank(bank)
            AgbBankSwitch.FLASH -> io.selectFlashBank(bank)
        }
    }

    private fun readOnce(layout: AgbSaveLayout, pos: Int, n: Int): ByteArray =
        if (layout.isEeprom) io.eepromRead(layout.eepromType, pos / 8, n) else io.saveRead(pos, n)

    private fun readChunk(
        layout: AgbSaveLayout,
        bank: Int,
        pos: Int,
        n: Int,
        verify: Boolean,
        doneBefore: Long,
        total: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): ByteArray {
        var problem = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            if (isCancelled()) throw BackupCancelledException()
            try {
                val first = readOnce(layout, pos, n)
                onProgress(doneBefore + n, total)
                if (!verify) return first
                val second = readOnce(layout, pos, n)
                onProgress(doneBefore + n * 2L, total)
                if (first.contentEquals(second)) return first
                problem = "two reads of the same data differed"
            } catch (e: ReadTimeoutException) {
                problem = "short or missing reply"
            }
            log("Chunk bank $bank +0x%X: $problem (attempt ${attempt + 1}/$MAX_ATTEMPTS)".format(pos))
        }
        throw VerificationFailedException(
            "Verification failed at save offset 0x%05X after $MAX_ATTEMPTS attempts: $problem. $failureHint"
                .format(bank * AgbSaveLayout.BANK_SIZE + pos),
            bank,
            pos,
        )
    }

    companion object {
        const val MAX_ATTEMPTS = 4
    }
}

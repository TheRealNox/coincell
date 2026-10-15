package org.noxeng.coincell.save

import org.noxeng.coincell.cart.DmgCartIo
import org.noxeng.coincell.cart.DmgMapper
import org.noxeng.coincell.protocol.ReadTimeoutException

/**
 * Reads cartridge RAM with the double-read verification of spec 6.1.3: every chunk is read twice
 * (full setup each time), the two reads must be identical, and a few retries are allowed before the
 * whole operation fails (spec 8.5, fixed). Used by the backup and by the restore read-back, so both
 * share exactly one definition of "verified read". It only ever reads.
 */
internal class VerifiedRamReader(
    private val io: DmgCartIo,
    private val log: (String) -> Unit,
    /** Appended to the failure message, e.g. "Nothing was saved." */
    private val failureHint: String,
) {
    /**
     * Reads [saveSize] bytes bank by bank. RAM must already be enabled. Progress counts both reads,
     * so [onProgress] ends at `saveSize * 2`.
     */
    fun readSave(
        mapper: DmgMapper,
        saveSize: Int,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
    ): ByteArray {
        val out = ByteArray(saveSize)
        val total = saveSize.toLong() * 2
        var done = 0L
        onProgress(0, total)
        val banks = (saveSize + BANK - 1) / BANK
        for (bank in 0 until banks) {
            mapper.selectRamBank(io, bank)
            val bankEnd = minOf(saveSize - bank * BANK, BANK)
            for (pos in 0 until bankEnd step CHUNK) {
                val len = minOf(CHUNK, bankEnd - pos)
                val chunk = readVerified(mapper, bank, pos, len, done, total, isCancelled, onProgress)
                chunk.copyInto(out, bank * BANK + pos)
                done += len * 2L
                onProgress(done, total)
            }
        }
        return out
    }

    private fun readVerified(
        mapper: DmgMapper,
        bank: Int,
        pos: Int,
        len: Int,
        doneBefore: Long,
        total: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): ByteArray {
        var problem = ""
        repeat(MAX_ATTEMPTS) { attempt ->
            if (isCancelled()) throw BackupCancelledException()
            try {
                val first = normalise(mapper, io.ramRead(pos, len))
                onProgress(doneBefore + len, total)
                val second = normalise(mapper, io.ramRead(pos, len))
                onProgress(doneBefore + len * 2L, total)
                if (first.contentEquals(second)) return first
                problem = "two reads of the same data differed"
            } catch (e: ReadTimeoutException) {
                problem = "short or missing reply"
            }
            log("Chunk bank $bank +0x%X: $problem (attempt ${attempt + 1}/$MAX_ATTEMPTS)".format(pos))
        }
        throw VerificationFailedException(
            "Verification failed at RAM bank $bank offset 0x%04X after $MAX_ATTEMPTS attempts: $problem. $failureHint"
                .format(pos),
            bank,
            pos,
        )
    }

    companion object {
        const val BANK = 0x2000
        const val CHUNK = 0x200
        const val MAX_ATTEMPTS = 4

        /** MBC2 RAM is 4 bit: the upper nibble is open bus and is dropped. */
        fun normalise(mapper: DmgMapper, data: ByteArray): ByteArray {
            if (!mapper.nibbleOnly) return data
            return ByteArray(data.size) { (data[it].toInt() and 0x0F).toByte() }
        }
    }
}

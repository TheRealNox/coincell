package com.therealnox.gbflash.protocol

import com.therealnox.gbflash.cart.AgbSaveHint
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.analyzeSaveWindow
import com.therealnox.gbflash.save.BackupPlan
import com.therealnox.gbflash.save.DmgBackupResult
import com.therealnox.gbflash.save.DmgSaveBackup
import com.therealnox.gbflash.save.DmgSaveRestore
import com.therealnox.gbflash.save.RestoreOutcome
import com.therealnox.gbflash.save.RestorePhase
import com.therealnox.gbflash.usb.Clock
import com.therealnox.gbflash.usb.SerialLink
import com.therealnox.gbflash.usb.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The device answered the handshake but this app must not use it; [info] has what it said. */
class IncompatibleDeviceException(val info: FirmwareInfo, message: String) : ProtocolException(message)

/** The cartridge header read right before a restore is not the one the user reviewed. Nothing was written. */
class HeaderChangedException(message: String) : ProtocolException(message)

/**
 * One connected GBFlash. Every operation takes [lock] (one operation at a time) and runs its
 * blocking serial I/O on [io].
 */
class DeviceSession private constructor(
    private val link: SerialLink,
    private val commands: Commands,
    private val io: CoroutineDispatcher,
    val firmware: FirmwareInfo,
) {
    private val lock = Mutex()

    /** Selects [platform] if it is not active yet (power-cycling the cart), then reads the header. */
    suspend fun readHeader(platform: CartPlatform): CartHeader = locked {
        if (commands.platform != platform) commands.selectMode(platform)
        commands.readHeader(platform)
    }

    /** Read-only look at the GBA save window; never writes to the cartridge. */
    suspend fun probeAgbSaveReadOnly(): AgbSaveHint = locked {
        if (commands.platform != CartPlatform.GBA) commands.selectMode(CartPlatform.GBA)
        analyzeSaveWindow(commands.readAgbSaveWindow())
    }

    /**
     * Reads and double-checks the whole save of the GB / GBC cartridge in [plan] into memory. Never
     * writes cartridge SRAM and never switches platform (GB / GBC mode must already be active).
     * Cartridge cleanup runs on success, error and cancel ([isCancelled] returning true).
     */
    internal suspend fun backupDmgSave(
        plan: BackupPlan.Ready,
        isCancelled: () -> Boolean,
        onProgress: (done: Long, total: Long) -> Unit,
        log: (String) -> Unit = {},
        nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
        includeRtc: Boolean = true,
        askRtc: (() -> Boolean)? = null,
    ): DmgBackupResult = locked {
        check(commands.platform == CartPlatform.GB_GBC) { "GB / GBC mode is not active" }
        val access = commands.dmgCartAccess()
        DmgSaveBackup(access, access::prepare, access::releaseBus, nowSeconds, log).run(plan, isCancelled, onProgress, includeRtc, askRtc)
    }

    /**
     * Writes [data] to the GB / GBC cartridge described by [expectedHeader] / [plan] and verifies it by
     * reading everything back (see [DmgSaveRestore]). This is the ONLY function that creates the SRAM
     * write path. Before touching anything it re-reads the cartridge header and throws
     * [HeaderChangedException] (nothing written) if it differs from [expectedHeader], so a swapped
     * cartridge is never written. [clockFooter] is the 48-byte RTC footer to restore, or null to leave
     * the cartridge clock alone. The caller is responsible for the safety copy and the matching rules.
     */
    internal suspend fun restoreDmgSave(
        plan: BackupPlan.Ready,
        expectedHeader: DmgHeader,
        data: ByteArray,
        clockFooter: ByteArray?,
        adjustClock: Boolean = true,
        isCancelled: () -> Boolean,
        onProgress: (RestorePhase, done: Long, total: Long) -> Unit,
        log: (String) -> Unit = {},
        nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): RestoreOutcome = locked {
        check(commands.platform == CartPlatform.GB_GBC) { "GB / GBC mode is not active" }
        val current = commands.readHeader(CartPlatform.GB_GBC) as? DmgHeader
            ?: throw HeaderChangedException("The cartridge header could not be read again")
        if (!sameHeader(current, expectedHeader)) {
            throw HeaderChangedException("The cartridge header changed since the review (was the cartridge swapped?). Nothing was written.")
        }
        val currentPlan = BackupPlan.forHeader(current) as? BackupPlan.Ready
        check(currentPlan != null && currentPlan.mapper == plan.mapper && currentPlan.saveSize == plan.saveSize) {
            "The restore plan does not describe the inserted cartridge"
        }
        val access = commands.dmgCartAccess()
        val writer = commands.dmgCartWriter()
        DmgSaveRestore(access, writer, access::prepare, access::releaseBus, nowSeconds, log)
            .run(plan, data, clockFooter, adjustClock, isCancelled, onProgress)
    }

    private fun sameHeader(a: DmgHeader, b: DmgHeader): Boolean =
        a.raw.copyOf(DmgHeader.HEADER_SIZE).contentEquals(b.raw.copyOf(DmgHeader.HEADER_SIZE))

    /** Powers the cartridge off (`F3`) and keeps the connection and the selected platform. Use before swapping cartridges. */
    suspend fun powerOffCart() = locked { commands.cartPowerOff() }

    /** Liveness check (PING on fw>=15). */
    suspend fun checkActive() = locked { commands.checkActive() }

    /** Powers the cartridge off (`AUTO_POWEROFF_TIME=0`, `F3`) and closes the port. Never throws. */
    suspend fun close() {
        try {
            locked { commands.powerDown() }
        } catch (_: Exception) {
            // Link may already be gone; closing the port below is all that is left to do.
        } finally {
            link.close()
        }
    }

    /** Closes the port immediately without talking to the device (detach / abort). */
    fun abandon() = link.close()

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }

    companion object {
        /**
         * Handshake (spec 2): purge, `A1`, parse the firmware reply, check compatibility, then disable
         * auto power-off and make sure the cart is unpowered. FlashGBX's open/query/close/reopen dance
         * only exists to probe several candidate ports; with one explicit device it is not repeated.
         */
        suspend fun open(
            link: SerialLink,
            log: (String) -> Unit = {},
            clock: Clock = SystemClock,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): DeviceSession = withContext(io) {
            val transport = Transport(link, clock, log)
            try {
                val info = queryFirmware(transport, log)
                info.incompatibility()?.let { throw IncompatibleDeviceException(info, it) }
                val commands = Commands(transport, info, log = log)
                if (info.cartPowerCtrl) {
                    commands.disableAutoPowerOff()
                    commands.cartPowerOff()
                }
                commands.checkActive()
                DeviceSession(link, commands, io, info)
            } catch (e: Throwable) {
                link.close()
                throw e
            }
        }

        private fun queryFirmware(transport: Transport, log: (String) -> Unit): FirmwareInfo {
            var last: ProtocolException? = null
            repeat(HANDSHAKE_ATTEMPTS) { attempt ->
                transport.drainInput()
                transport.write(Op.QUERY_FW_INFO)
                try {
                    // FlashGBX waits 75 ms; USB latency on a phone is worse, so be generous.
                    return FirmwareInfo.read(transport, FIRST_BYTE_TIMEOUT_MS)
                } catch (e: ProtocolException) {
                    last = e
                    log("Handshake attempt ${attempt + 1} failed: ${e.message}")
                }
            }
            throw ProtocolException("No valid reply to firmware query: ${last?.message}", last)
        }

        private const val HANDSHAKE_ATTEMPTS = 3
        private const val FIRST_BYTE_TIMEOUT_MS = 1000L

        /** For tests: build a session without the handshake. */
        internal fun forTesting(
            link: SerialLink,
            transport: Transport,
            info: FirmwareInfo,
            io: CoroutineDispatcher,
        ) = DeviceSession(link, Commands(transport, info), io, info)
    }
}

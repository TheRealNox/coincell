package com.therealnox.gbflash

import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.BackupKind
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.DeviceSession
import com.therealnox.gbflash.restore.RestoreCoordinator
import com.therealnox.gbflash.restore.RestorePolicy
import com.therealnox.gbflash.restore.RestoreRequest
import com.therealnox.gbflash.restore.RestoreReview
import com.therealnox.gbflash.save.BackupPlan
import com.therealnox.gbflash.save.RestoreOutcome
import com.therealnox.gbflash.save.RestorePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/** A deterministic save image that differs per [seed]. */
fun testSave(size: Int, seed: Int): ByteArray = ByteArray(size) { ((it * 31 + seed * 7) xor (it shr 8) xor seed).toByte() }

/** Fake firmware + fake cartridge + a connected session that has read the header (like the backup Rig, with a library). */
internal class RestoreRig(
    cartType: Int,
    ramCode: Int,
    val mapper: FakeMapper,
    val sramSize: Int,
    hasRtc: Boolean = false,
    val title: String = "TESTGAME",
    sram: ByteArray? = null,
    globalChecksum: Int = 0x1234,
) {
    val link = FakeSerialLink()
    val fw = FakeFirmware(link)
    private val rom = dmgHeader(title = title.toByteArray(), cartType = cartType, ramCode = ramCode).also {
        it[0x14E] = (globalChecksum shr 8).toByte()
        it[0x14F] = globalChecksum.toByte()
    }.copyOf(0x10000)
    val cart = FakeDmgCart(mapper, rom, sram ?: testSave(sramSize, 1), hasRtc)
    val sramBefore = cart.sram.copyOf()
    val session: DeviceSession
    val header: DmgHeader
    val plan: BackupPlan.Ready

    init {
        fw.cart = cart
        session = runBlocking { DeviceSession.open(link, clock = link, io = Dispatchers.Unconfined) }
        header = runBlocking { session.readHeader(CartPlatform.GB_GBC) } as DmgHeader
        plan = BackupPlan.forHeader(header) as BackupPlan.Ready
    }

    val game: GameInfo get() = GameInfo.fromDmg(header)

    fun restoreRaw(
        data: ByteArray,
        clockFooter: ByteArray? = null,
        adjust: Boolean = true,
        expected: DmgHeader = header,
        cancel: () -> Boolean = { false },
        now: Long = 1_700_000_000L,
        progress: (RestorePhase, Long, Long) -> Unit = { _, _, _ -> },
    ): RestoreOutcome = runBlocking {
        session.restoreDmgSave(plan, expected, data, clockFooter, adjust, cancel, progress, nowSeconds = { now })
    }

    /** Puts a different cartridge (new ROM header and SRAM) into the fake reader. */
    fun swapCart(other: FakeDmgCart) {
        fw.cart = other
    }
}

/** Library, review and coordinator helpers shared by the restore tests. */
internal class RestoreEnv(root: File, val rig: RestoreRig, var clockMillis: Long = 1_700_000_000_000L) {
    val library = SaveLibrary(root) { clockMillis }
    val log = mutableListOf<String>()
    val coordinator = RestoreCoordinator(rig.session, library, "test", { clockMillis }, log::add)

    /** Stores [data] as a normal backup of this cartridge's game. */
    fun store(
        data: ByteArray,
        rtcFooter: ByteArray? = null,
        kind: BackupKind = BackupKind.BACKUP,
        game: GameInfo = rig.game,
        note: String = "",
        verified: Boolean = true,
    ): BackupEntry {
        clockMillis += 60_000
        return library.store(
            game,
            NewBackup(data, rtcFooter, null, rtcFooter != null, "L15", "test", verified, null, note, kind, if (kind == BackupKind.IMPORTED) "edited.sav" else null),
        )
    }

    fun review(entry: BackupEntry, header: DmgHeader = rig.header): RestoreReview =
        RestorePolicy.review(entry, library.readGame(entry.gameKey), header)

    fun request(entry: BackupEntry, clock: Boolean = false, typed: String = "", known: BackupEntry? = null, adjust: Boolean = true) =
        RestoreRequest(review(entry), typed, clock, adjust, known)
}

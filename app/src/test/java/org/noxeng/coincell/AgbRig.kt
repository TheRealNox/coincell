package org.noxeng.coincell

import org.noxeng.coincell.cart.AgbDatabase
import org.noxeng.coincell.cart.AgbHeader
import org.noxeng.coincell.cart.SaveTypeSource
import org.noxeng.coincell.library.BackupEntry
import org.noxeng.coincell.library.BackupKind
import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.library.NewBackup
import org.noxeng.coincell.library.SaveLibrary
import org.noxeng.coincell.protocol.CartPlatform
import org.noxeng.coincell.protocol.DeviceSession
import org.noxeng.coincell.restore.RestoreCoordinator
import org.noxeng.coincell.restore.RestorePolicy
import org.noxeng.coincell.restore.RestoreRequest
import org.noxeng.coincell.restore.RestoreReview
import org.noxeng.coincell.save.AgbBackupPlan
import org.noxeng.coincell.save.RestoreOutcome
import org.noxeng.coincell.save.RestorePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/** Fake firmware + fake GBA cartridge + a connected session that has read the header. */
internal class AgbRig(
    val kind: AgbSaveKind,
    val saveSize: Int,
    val flashId: Int = 0x6213,
    val hasRtc: Boolean = false,
    title: String = "TESTGBA",
    code: String = "ABCD",
    save: ByteArray? = null,
    marker: Int? = null,
    version: Int = 0,
) {
    val link = FakeSerialLink()
    val fw = FakeFirmware(link)
    val cart = FakeAgbCart(fakeAgbRom(title, code, marker, version), kind, saveSize, flashId, hasRtc, save ?: agbTestSave(saveSize, 1))
    val session: DeviceSession
    val header: AgbHeader

    init {
        fw.agbCart = cart
        session = runBlocking { DeviceSession.open(link, clock = link, io = Dispatchers.Unconfined) }
        header = runBlocking { session.readHeader(CartPlatform.GBA) } as AgbHeader
    }

    val game: GameInfo get() = GameInfo.fromAgb(header)

    fun plan(type: Int, source: SaveTypeSource = SaveTypeSource.DATABASE): AgbBackupPlan.Ready =
        AgbBackupPlan.forHeader(header, type, source) as AgbBackupPlan.Ready

    fun backup(
        type: Int,
        includeRtc: Boolean = true,
        askRtc: (() -> Boolean)? = null,
        cancel: () -> Boolean = { false },
        now: Long = 1_700_000_000L,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = runBlocking { session.backupAgbSave(plan(type), cancel, progress, includeRtc = includeRtc, askRtc = askRtc, nowSeconds = { now }) }

    fun restoreRaw(
        type: Int,
        data: ByteArray,
        clockFooter: ByteArray? = null,
        adjust: Boolean = true,
        expected: AgbHeader = header,
        cancel: () -> Boolean = { false },
        now: Long = 1_700_000_000L,
        progress: (RestorePhase, Long, Long) -> Unit = { _, _, _ -> },
    ): RestoreOutcome = runBlocking {
        session.restoreAgbSave(plan(type), expected, data, clockFooter, adjust, cancel, progress, nowSeconds = { now })
    }
}

internal fun loadAgbDatabase(): AgbDatabase =
    AgbRig::class.java.getResourceAsStream("/db_agb.tsv")!!.use { AgbDatabase.parse(it) }

/** Library, review and coordinator helpers for GBA restores. */
internal class AgbEnv(root: File, val rig: AgbRig, val type: Int, var clockMillis: Long = 1_700_000_000_000L) {
    val library = SaveLibrary(root) { clockMillis }
    val log = mutableListOf<String>()
    val coordinator = RestoreCoordinator(rig.session, library, "test", { clockMillis }, log::add)

    fun store(
        data: ByteArray,
        rtcFooter: ByteArray? = null,
        kind: BackupKind = BackupKind.BACKUP,
        game: GameInfo = rig.game,
        saveType: Int? = type,
    ): BackupEntry {
        clockMillis += 60_000
        return library.store(
            game,
            NewBackup(
                data, rtcFooter, null, false, "L15", "test", true, null, "", kind,
                if (kind == BackupKind.IMPORTED) "edited.sav" else null,
                saveType, SaveTypeSource.DATABASE,
            ),
        )
    }

    fun review(entry: BackupEntry, clockPresent: Boolean? = rig.hasRtc): RestoreReview =
        RestorePolicy.reviewAgb(entry, library.readGame(entry.gameKey), rig.header, rig.plan(type), clockPresent)

    fun request(entry: BackupEntry, clock: Boolean = false, typed: String = "", known: BackupEntry? = null, adjust: Boolean = true) =
        RestoreRequest(review(entry), typed, clock, adjust, known)
}

/** [testSave] with a different second 64 KiB bank (the plain generator repeats every 64 KiB). */
fun agbTestSave(size: Int, seed: Int): ByteArray = testSave(size, seed).also { for (i in 0x10000 until size) it[i] = (it[i] + 0x37 + (i shr 12)).toByte() }

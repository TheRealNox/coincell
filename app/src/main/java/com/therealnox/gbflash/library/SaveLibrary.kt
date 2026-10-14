package com.therealnox.gbflash.library

import com.therealnox.gbflash.cart.RtcSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

/** Where a library entry came from. A missing `kind` in `backup.json` (milestone 2 entries) means [BACKUP]. */
enum class BackupKind(val json: String, val label: String) {
    BACKUP("backup", "Backup"),
    PRE_RESTORE("pre-restore", "Pre-restore copy"),
    IMPORTED("imported", "Imported"),
    ;

    companion object {
        fun fromJson(value: String?): BackupKind = entries.firstOrNull { it.json == value } ?: BACKUP
    }
}

/** What the app knows about a backup when it stores it. */
class NewBackup(
    val save: ByteArray,
    /** 48-byte RTC footer to keep next to the save, or null if the RTC is not included. */
    val rtcFooter: ByteArray?,
    val rtcProbePresent: Boolean?,
    val rtcDeclared: Boolean,
    val firmware: String,
    val appVersion: String,
    val verified: Boolean,
    /** All 0xFF / all 0x00 and similar: stored anyway, but flagged. */
    val suspectReason: String?,
    val note: String = "",
    val kind: BackupKind = BackupKind.BACKUP,
    /** Name of the imported file (kind [BackupKind.IMPORTED]). */
    val originalFileName: String? = null,
)

/** One line of `restores.json`: what a restore attempt did to a cartridge. */
class RestoreRecord(
    val timestampMillis: Long,
    val sourceBackupId: String,
    /** Id of the pre-restore copy (or the entry that provably equalled the cartridge), null if none was taken. */
    val safetyCopyId: String?,
    /** verified, verify-failed, failed, cancelled-partial, aborted-nothing-written */
    val outcome: String,
    val crc32: Long?,
    val detail: String,
)

/** One stored backup (read back from `backup.json`). */
class BackupEntry(
    val gameKey: String,
    val id: String,
    val timestampMillis: Long,
    val size: Int,
    val crc32: Long,
    val rtc: RtcSnapshot?,
    val rtcProbePresent: Boolean?,
    val rtcDeclared: Boolean,
    val firmware: String,
    val appVersion: String,
    val verified: Boolean,
    val suspectReason: String?,
    val note: String,
    val kind: BackupKind = BackupKind.BACKUP,
    val originalFileName: String? = null,
) {
    val rtcIncluded: Boolean get() = rtc != null
    val suspect: Boolean get() = suspectReason != null
}

/**
 * App-private save library: `<root>/<gameKey>/game.json` and, per backup,
 * `<root>/<gameKey>/<backupId>/{save.sav, rtc.bin?, backup.json}`. Backups are assembled in a hidden
 * temp directory and renamed into place, so a crash never leaves a half-written backup visible.
 */
class SaveLibrary(private val root: File, private val nowMillis: () -> Long = System::currentTimeMillis) {

    /** Stores [backup] for [game]; returns the stored entry. */
    @Synchronized
    fun store(game: GameInfo, backup: NewBackup): BackupEntry {
        require(backup.rtcFooter == null || backup.rtcFooter.size == RtcSnapshot.FOOTER_SIZE) { "RTC footer must be 48 bytes" }
        val now = nowMillis()
        val gameDir = File(root, game.key)
        if (!gameDir.isDirectory && !gameDir.mkdirs()) throw IOException("Cannot create $gameDir")
        removeStaleTemps(gameDir)
        writeGameJson(gameDir, game, now)

        val id = uniqueId(gameDir, now)
        val crc = crc32Of(backup.save)
        val rtc = backup.rtcFooter?.let(RtcSnapshot::fromFooter)
        val tmp = File(gameDir, "$TEMP_PREFIX$id")
        try {
            if (!tmp.mkdirs()) throw IOException("Cannot create $tmp")
            writeFile(File(tmp, SAVE_FILE), backup.save)
            backup.rtcFooter?.let { writeFile(File(tmp, RTC_FILE), it) }
            writeFile(File(tmp, BACKUP_JSON), backupJson(id, now, backup, crc, rtc).toString(2).toByteArray())
            moveAtomic(tmp, File(gameDir, id))
        } catch (e: Exception) {
            tmp.deleteRecursively()
            throw e
        }
        return readEntry(File(gameDir, id), game.key) ?: throw IOException("Stored backup cannot be read back")
    }

    /** Backups of [gameKey], newest first. Unreadable entries are skipped. */
    @Synchronized
    fun list(gameKey: String): List<BackupEntry> {
        val dir = File(root, gameKey)
        val dirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { readEntry(it, gameKey) }.sortedWith(compareByDescending<BackupEntry> { it.timestampMillis }.thenByDescending { it.id })
    }

    /**
     * Backups of other cartridges of the same game as [game]: same platform and cleaned title but a
     * different key (global checksum). These can be restored to [game]'s cartridge with a notice.
     */
    @Synchronized
    fun listOtherCartridges(game: GameInfo): List<BackupEntry> {
        val want = GameInfo.sanitizeTitle(game.title)
        val keys = (root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()).map { it.name }
        return keys.filter { it != game.key && SAFE.matches(it) }.flatMap { key ->
            val other = readGame(key)
            if (other != null && other.platform == game.platform && GameInfo.sanitizeTitle(other.title) == want) list(key) else emptyList()
        }.sortedByDescending { it.timestampMillis }
    }

    /** All games with their `game.json` (for the future library screen). */
    @Synchronized
    fun games(): List<JSONObject> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .mapNotNull { runCatching { JSONObject(File(it, GAME_JSON).readText()) }.getOrNull() }
            .sortedByDescending { it.optLong("lastSeen") }

    /** The stored `game.json` of [gameKey], or null if it is missing or unreadable. */
    @Synchronized
    fun readGame(gameKey: String): GameInfo? = try {
        require(SAFE.matches(gameKey))
        val j = JSONObject(File(File(root, gameKey), GAME_JSON).readText())
        GameInfo(
            platform = j.getString("platform"),
            title = j.getString("title"),
            key = j.getString("key"),
            cartType = j.optString("cartType"),
            romSize = if (j.isNull("romSize")) null else j.getLong("romSize"),
            globalChecksum = j.getString("globalChecksum").toInt(16),
            headerChecksum = j.getString("headerChecksum").toInt(16),
            gameCode = if (j.isNull("gameCode")) null else j.getString("gameCode"),
        )
    } catch (_: Exception) {
        null
    }

    /** Appends to `<gameKey>/restores.json` (temp file + rename). */
    @Synchronized
    fun addRestoreRecord(gameKey: String, record: RestoreRecord) {
        require(SAFE.matches(gameKey)) { "Unsafe game key" }
        val dir = File(root, gameKey)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val arr = JSONArray()
        restoreRecords(gameKey).forEach { arr.put(recordJson(it)) }
        arr.put(recordJson(record))
        val tmp = File(dir, "$TEMP_PREFIX$RESTORES_JSON")
        writeFile(tmp, JSONObject().put("version", 1).put("restores", arr).toString(2).toByteArray())
        moveAtomic(tmp, File(dir, RESTORES_JSON))
    }

    /** The restore records of [gameKey], oldest first. */
    @Synchronized
    fun restoreRecords(gameKey: String): List<RestoreRecord> = try {
        require(SAFE.matches(gameKey))
        val arr = JSONObject(File(File(root, gameKey), RESTORES_JSON).readText()).getJSONArray("restores")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RestoreRecord(
                timestampMillis = o.getLong("timestamp"),
                sourceBackupId = o.getString("sourceBackupId"),
                safetyCopyId = if (o.isNull("safetyCopyId")) null else o.getString("safetyCopyId"),
                outcome = o.getString("outcome"),
                crc32 = if (o.isNull("crc32")) null else o.getString("crc32").toLong(16),
                detail = o.optString("detail"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun recordJson(r: RestoreRecord): JSONObject = JSONObject()
        .put("timestamp", r.timestampMillis)
        .put("sourceBackupId", r.sourceBackupId)
        .put("safetyCopyId", r.safetyCopyId ?: JSONObject.NULL)
        .put("outcome", r.outcome)
        .put("crc32", r.crc32?.let { "%08X".format(it) } ?: JSONObject.NULL)
        .put("detail", r.detail)

    /** `save.sav`, checked against the stored CRC32. */
    @Synchronized
    fun readSave(entry: BackupEntry): ByteArray {
        val bytes = File(dirOf(entry), SAVE_FILE).readBytes()
        if (bytes.size != entry.size || crc32Of(bytes) != entry.crc32) throw IOException("Stored save ${entry.id} is corrupt (CRC mismatch)")
        return bytes
    }

    /** `rtc.bin` or null. */
    @Synchronized
    fun readRtcFooter(entry: BackupEntry): ByteArray? {
        val f = File(dirOf(entry), RTC_FILE)
        return if (f.isFile) f.readBytes().also { if (it.size != RtcSnapshot.FOOTER_SIZE) throw IOException("rtc.bin has wrong size") } else null
    }

    /** `.sav` content for export (spec 7): save.sav followed by rtc.bin when [includeRtc] and present. */
    fun exportBytes(entry: BackupEntry, includeRtc: Boolean): ByteArray {
        val save = readSave(entry)
        val rtc = if (includeRtc) readRtcFooter(entry) else null
        return if (rtc == null) save else save + rtc
    }

    /**
     * Replaces the free-text note of [entry] and returns the re-read entry. Only `backup.json` is touched
     * (temp file + atomic rename, like every other write); the save, its CRC and the id are untouched.
     */
    @Synchronized
    fun updateNote(entry: BackupEntry, note: String): BackupEntry {
        val dir = dirOf(entry)
        val file = File(dir, BACKUP_JSON)
        val json = JSONObject(file.readText()).put("note", note)
        val tmp = File(dir, "$TEMP_PREFIX$BACKUP_JSON")
        try {
            writeFile(tmp, json.toString(2).toByteArray())
            moveAtomic(tmp, file)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        return readEntry(dir, entry.gameKey) ?: throw IOException("Updated backup cannot be read back")
    }

    @Synchronized
    fun delete(entry: BackupEntry) {
        val dir = dirOf(entry)
        if (!dir.deleteRecursively()) throw IOException("Cannot delete $dir")
    }

    // ---- internals ---------------------------------------------------------------------------

    private fun dirOf(entry: BackupEntry): File {
        // Ids and keys are generated by this class; refuse anything that could leave the library.
        require(SAFE.matches(entry.gameKey) && SAFE.matches(entry.id)) { "Unsafe backup path" }
        return File(File(root, entry.gameKey), entry.id)
    }

    private fun uniqueId(gameDir: File, now: Long): String {
        // Local time, so ids match the times shown in the app; the timestamp field stays authoritative.
        val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val base = fmt.format(Date(now))
        var id = base
        var n = 2
        while (File(gameDir, id).exists()) id = "$base-${n++}"
        return id
    }

    private fun removeStaleTemps(gameDir: File) {
        gameDir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }?.forEach { it.deleteRecursively() }
    }

    private fun writeGameJson(gameDir: File, game: GameInfo, now: Long) {
        val file = File(gameDir, GAME_JSON)
        val firstSeen = runCatching { JSONObject(file.readText()).getLong("firstSeen") }.getOrDefault(now)
        val json = JSONObject()
            .put("version", 1)
            .put("key", game.key)
            .put("platform", game.platform)
            .put("title", game.title)
            .put("cartType", game.cartType)
            .put("romSize", game.romSize ?: JSONObject.NULL)
            .put("globalChecksum", "%04X".format(game.globalChecksum))
            .put("headerChecksum", "%02X".format(game.headerChecksum))
            .put("gameCode", game.gameCode ?: JSONObject.NULL)
            .put("firstSeen", firstSeen)
            .put("lastSeen", now)
        val tmp = File(gameDir, "$TEMP_PREFIX$GAME_JSON")
        writeFile(tmp, json.toString(2).toByteArray())
        moveAtomic(tmp, file)
    }

    private fun backupJson(id: String, now: Long, b: NewBackup, crc: Long, rtc: RtcSnapshot?): JSONObject {
        val o = JSONObject()
            .put("version", 1)
            .put("id", id)
            .put("timestamp", now)
            .put("size", b.save.size)
            .put("crc32", "%08X".format(crc))
            .put("verified", b.verified)
            .put("suspect", b.suspectReason != null)
            .put("suspectReason", b.suspectReason ?: JSONObject.NULL)
            .put("rtcIncluded", rtc != null)
            .put("rtcProbePresent", b.rtcProbePresent ?: JSONObject.NULL)
            .put("rtcDeclared", b.rtcDeclared)
            .put("firmware", b.firmware)
            .put("appVersion", b.appVersion)
            .put("note", b.note)
            .put("kind", b.kind.json)
        b.originalFileName?.let { o.put("originalFileName", it) }
        if (rtc != null) {
            o.put(
                "rtc",
                JSONObject()
                    .put("days", rtc.days).put("hours", rtc.hours).put("minutes", rtc.minutes).put("seconds", rtc.seconds)
                    .put("halted", rtc.halted).put("dayCarry", rtc.dayCarry).put("readAt", rtc.timestamp),
            )
        }
        return o
    }

    private fun readEntry(dir: File, gameKey: String): BackupEntry? = try {
        val save = File(dir, SAVE_FILE)
        val json = JSONObject(File(dir, BACKUP_JSON).readText())
        val rtcFile = File(dir, RTC_FILE)
        val rtc = if (rtcFile.isFile) RtcSnapshot.fromFooter(rtcFile.readBytes()) else null
        val size = json.getInt("size")
        if (!save.isFile || save.length() != size.toLong()) {
            null
        } else {
            BackupEntry(
                gameKey = gameKey,
                id = json.getString("id"),
                timestampMillis = json.getLong("timestamp"),
                size = size,
                crc32 = json.getString("crc32").toLong(16),
                rtc = rtc,
                rtcProbePresent = if (json.isNull("rtcProbePresent")) null else json.getBoolean("rtcProbePresent"),
                rtcDeclared = json.optBoolean("rtcDeclared"),
                firmware = json.optString("firmware"),
                appVersion = json.optString("appVersion"),
                verified = json.optBoolean("verified"),
                suspectReason = if (json.optBoolean("suspect")) json.optString("suspectReason", "suspect") else null,
                note = json.optString("note"),
                kind = BackupKind.fromJson(json.optString("kind", "").ifEmpty { null }),
                originalFileName = if (json.has("originalFileName")) json.optString("originalFileName") else null,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun writeFile(file: File, data: ByteArray) {
        java.io.FileOutputStream(file).use {
            it.write(data)
            it.fd.sync()
        }
    }

    private fun moveAtomic(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val SAVE_FILE = "save.sav"
        const val RTC_FILE = "rtc.bin"
        const val BACKUP_JSON = "backup.json"
        const val GAME_JSON = "game.json"
        const val RESTORES_JSON = "restores.json"
        private const val TEMP_PREFIX = ".tmp-"
        private val SAFE = Regex("[A-Za-z0-9_-]+")

        fun crc32Of(data: ByteArray): Long = CRC32().also { it.update(data) }.value
    }
}

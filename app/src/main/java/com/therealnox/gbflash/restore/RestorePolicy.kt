package com.therealnox.gbflash.restore

import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.save.BackupPlan

/** How a library backup relates to the cartridge in the reader (docs/UI_DESIGN.md 3.4, 5.6). */
sealed interface RestoreMatch {
    /** Same game key (platform + cleaned title + global checksum). */
    data object SameGame : RestoreMatch

    /**
     * Same platform, same cleaned title and same save size but another global checksum: a second copy,
     * a repro or another revision. Allowed with a notice, no typing.
     */
    data class DifferentCartridge(val sourceChecksum: Int, val cartChecksum: Int) : RestoreMatch

    /** Another title. Allowed only after the user types [requiredTitle]. */
    data class DifferentTitle(val sourceTitle: String, val requiredTitle: String) : RestoreMatch

    /** Not allowed, no override. */
    data class Blocked(val reason: String) : RestoreMatch
}

/** Everything the review screen shows, and everything [RestorePolicy] decided from it. */
class RestoreReview internal constructor(
    val source: BackupEntry,
    val sourceGame: GameInfo?,
    /** The header read from the cartridge for this review (what the user is looking at). */
    val header: DmgHeader,
    val cartGame: GameInfo,
    internal val plan: BackupPlan,
    val match: RestoreMatch,
    /** The backup has a usable (plausible) `rtc.bin` and the cartridge header declares a timer. */
    val clockOptionOffered: Boolean,
    /** Why the clock option is not offered, shown as a plain line. */
    val clockNote: String,
) {
    val canProceed: Boolean get() = match !is RestoreMatch.Blocked && plan is BackupPlan.Ready
}

object RestorePolicy {
    /**
     * Applies the matching rules of the milestone 3 brief to [source] (with its `game.json`, [sourceGame])
     * and the cartridge [header] just read. The order is: platform, supported cartridge, save size, then
     * the identity comparison.
     */
    fun review(source: BackupEntry, sourceGame: GameInfo?, header: DmgHeader): RestoreReview {
        val plan = BackupPlan.forHeader(header)
        val cartGame = GameInfo.fromDmg(header)
        val match = evaluate(source, sourceGame, header, cartGame, plan)
        val (offered, note) = clockOption(source, plan, match)
        return RestoreReview(source, sourceGame, header, cartGame, plan, match, offered, note)
    }

    private fun evaluate(
        source: BackupEntry,
        sourceGame: GameInfo?,
        header: DmgHeader,
        cartGame: GameInfo,
        plan: BackupPlan,
    ): RestoreMatch {
        if (sourceGame == null) return RestoreMatch.Blocked("The game information of this backup (game.json) is missing, so it cannot be matched to the cartridge.")
        if (sourceGame.platform != cartGame.platform) {
            return RestoreMatch.Blocked("A ${platformName(sourceGame.platform)} backup cannot be written to a ${platformName(cartGame.platform)} cartridge.")
        }
        val ready = when (plan) {
            is BackupPlan.Unavailable -> return RestoreMatch.Blocked("This cartridge cannot be restored to: ${plan.reason}")
            is BackupPlan.Ready -> plan
        }
        if (source.size != ready.saveSize) {
            return RestoreMatch.Blocked(
                "The backup is ${formatSize(source.size)} but this cartridge has ${formatSize(ready.saveSize)} of save space.",
            )
        }
        return when {
            sourceGame.key == cartGame.key -> RestoreMatch.SameGame
            GameInfo.sanitizeTitle(sourceGame.title) == GameInfo.sanitizeTitle(cartGame.title) ->
                RestoreMatch.DifferentCartridge(sourceGame.globalChecksum, cartGame.globalChecksum)
            else -> RestoreMatch.DifferentTitle(sourceGame.title, requiredTitle(header))
        }
    }

    private fun clockOption(source: BackupEntry, plan: BackupPlan, match: RestoreMatch): Pair<Boolean, String> {
        val ready = plan as? BackupPlan.Ready
        val rtc = source.rtc
        return when {
            ready == null || match is RestoreMatch.Blocked -> false to ""
            ready.rtcDeclared && rtc != null && rtc.plausible -> true to ""
            ready.rtcDeclared && rtc != null -> false to "The backup's clock values are out of range, so the clock cannot be restored."
            ready.rtcDeclared -> false to "This backup has no clock data. The cartridge clock will not be changed."
            rtc != null -> false to "The cartridge header declares no clock, so the backup's clock data is ignored. The cartridge clock will not be changed."
            else -> false to ""
        }
    }

    /** The cartridge title the user has to type, exactly as the header shows it. */
    fun requiredTitle(header: DmgHeader): String = header.title.trim().ifEmpty { "UNTITLED" }

    /** True when [typed] is what the review asks for (case-insensitive, surrounding blanks ignored). */
    fun titleConfirmed(match: RestoreMatch, typed: String): Boolean = when (match) {
        is RestoreMatch.DifferentTitle -> typed.trim().equals(match.requiredTitle, ignoreCase = true)
        is RestoreMatch.Blocked -> false
        else -> true
    }

    /** Whether the restore may start: allowed by the rules and, for another title, the title was typed. */
    fun mayStart(review: RestoreReview, typedTitle: String): Boolean =
        review.canProceed && titleConfirmed(review.match, typedTitle)

    fun formatSize(bytes: Int): String = if (bytes >= 1024 && bytes % 1024 == 0) "${bytes / 1024} KiB" else "$bytes bytes"

    private fun platformName(platform: String): String = when (platform) {
        "DMG" -> "GB / GBC"
        "AGB" -> "GBA"
        else -> platform
    }
}

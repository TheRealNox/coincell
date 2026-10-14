package com.therealnox.gbflash.restore

import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.AgbSaveTypes
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.library.BackupEntry
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.save.AgbBackupPlan
import com.therealnox.gbflash.save.BackupPlan

/** How a library backup relates to the cartridge in the reader (docs/UI_DESIGN.md 3.4, 5.6). */
sealed interface RestoreMatch {
    /** Same game key (platform + cleaned title + checksum; for GBA also the game code). */
    data object SameGame : RestoreMatch

    /**
     * Same platform, same cleaned title (and for GBA the same game code) and same save size but another checksum:
     * a second copy, a repro or another revision. Allowed with a notice, no typing.
     */
    data class DifferentCartridge(val sourceChecksum: Int, val cartChecksum: Int) : RestoreMatch

    /** Another title. Allowed only after the user types [requiredTitle]. */
    data class DifferentTitle(val sourceTitle: String, val requiredTitle: String) : RestoreMatch

    /** Not allowed, no override. */
    data class Blocked(val reason: String) : RestoreMatch
}

/** The cartridge a restore would write to: its parsed header and what a save operation on it involves. */
internal sealed interface RestoreTarget {
    val header: CartHeader

    class Dmg(override val header: DmgHeader, val plan: BackupPlan) : RestoreTarget

    class Agb(
        override val header: AgbHeader,
        val plan: AgbBackupPlan,
        /** Result of the read-only clock probe made for this review; null if it was not possible. */
        val clockPresent: Boolean?,
    ) : RestoreTarget
}

/** Everything the review screen shows, and everything [RestorePolicy] decided from it. */
class RestoreReview internal constructor(
    val source: BackupEntry,
    val sourceGame: GameInfo?,
    internal val target: RestoreTarget,
    val cartGame: GameInfo,
    val match: RestoreMatch,
    /** The backup has a usable (plausible) clock and the cartridge has one. */
    val clockOptionOffered: Boolean,
    /** Why the clock option is not offered, shown as a plain line. */
    val clockNote: String,
) {
    /** The header read from the cartridge for this review (what the user is looking at). */
    val header: CartHeader get() = target.header

    /** GBA: the FlashGBX save type this restore writes with; null for GB / GBC. */
    val agbSaveType: Int? get() = ((target as? RestoreTarget.Agb)?.plan as? AgbBackupPlan.Ready)?.saveType

    val canProceed: Boolean
        get() = match !is RestoreMatch.Blocked && when (val t = target) {
            is RestoreTarget.Dmg -> t.plan is BackupPlan.Ready
            is RestoreTarget.Agb -> t.plan is AgbBackupPlan.Ready
        }
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
        val ready = plan as? BackupPlan.Ready
        val match = evaluate(
            source, sourceGame, cartGame,
            planReason = (plan as? BackupPlan.Unavailable)?.reason,
            saveSize = ready?.saveSize ?: 0,
            saveType = null,
            sameGameOtherCart = { true },
            requiredTitle = requiredTitle(header),
            sourceTitleLabel = { it.title },
            checksumOf = { it.globalChecksum },
        )
        val (offered, note) = clockOption(source, ready?.rtcDeclared == true, ready != null, match)
        return RestoreReview(source, sourceGame, RestoreTarget.Dmg(header, plan), cartGame, match, offered, note)
    }

    /**
     * The same for a GBA cartridge. [plan] is the save type the user confirmed; [clockPresent] is the result of the
     * read-only clock probe (null when it could not be made). A backup is only offered to a cartridge with the same
     * save type (the sizes of 512K Flash / 512K SRAM and of 1M Flash / 1M SRAM are equal but the chips are not).
     */
    internal fun reviewAgb(source: BackupEntry, sourceGame: GameInfo?, header: AgbHeader, plan: AgbBackupPlan, clockPresent: Boolean?): RestoreReview {
        val cartGame = GameInfo.fromAgb(header)
        val ready = plan as? AgbBackupPlan.Ready
        val match = evaluate(
            source, sourceGame, cartGame,
            planReason = (plan as? AgbBackupPlan.Unavailable)?.reason,
            saveSize = ready?.saveSize ?: 0,
            saveType = ready?.saveType,
            sameGameOtherCart = { it.gameCode.equals(cartGame.gameCode, ignoreCase = true) },
            requiredTitle = requiredTitle(header, sourceGame),
            sourceTitleLabel = { g -> g.title.trim().ifEmpty { "UNTITLED" } + (g.gameCode?.let { " ($it)" } ?: "") },
            checksumOf = { it.headerChecksum },
        )
        val (offered, note) = clockOption(source, clockPresent == true, ready != null, match, unknownClock = clockPresent == null)
        return RestoreReview(source, sourceGame, RestoreTarget.Agb(header, plan, clockPresent), cartGame, match, offered, note)
    }

    @Suppress("LongParameterList")
    private fun evaluate(
        source: BackupEntry,
        sourceGame: GameInfo?,
        cartGame: GameInfo,
        planReason: String?,
        saveSize: Int,
        saveType: Int?,
        sameGameOtherCart: (GameInfo) -> Boolean,
        requiredTitle: String,
        sourceTitleLabel: (GameInfo) -> String,
        checksumOf: (GameInfo) -> Int,
    ): RestoreMatch {
        if (sourceGame == null) return RestoreMatch.Blocked("The game information of this backup (game.json) is missing, so it cannot be matched to the cartridge.")
        if (sourceGame.platform != cartGame.platform) {
            return RestoreMatch.Blocked("A ${platformName(sourceGame.platform)} backup cannot be written to a ${platformName(cartGame.platform)} cartridge.")
        }
        if (planReason != null) return RestoreMatch.Blocked("This cartridge cannot be restored to: $planReason")
        if (source.size != saveSize) {
            return RestoreMatch.Blocked(
                "The backup is ${formatSize(source.size)} but this cartridge has ${formatSize(saveSize)} of save space.",
            )
        }
        val entryType = source.saveType
        if (saveType != null && entryType != null && entryType != saveType) {
            return RestoreMatch.Blocked(
                "The backup is a ${AgbSaveTypes.label(entryType)} save but the cartridge is set to ${AgbSaveTypes.label(saveType)}. " +
                    "Different save chips are not interchangeable.",
            )
        }
        return when {
            sourceGame.key == cartGame.key -> RestoreMatch.SameGame
            GameInfo.sanitizeTitle(sourceGame.title) == GameInfo.sanitizeTitle(cartGame.title) && sameGameOtherCart(sourceGame) ->
                RestoreMatch.DifferentCartridge(checksumOf(sourceGame), checksumOf(cartGame))
            else -> RestoreMatch.DifferentTitle(sourceTitleLabel(sourceGame), requiredTitle)
        }
    }

    private fun clockOption(
        source: BackupEntry,
        cartHasClock: Boolean,
        planReady: Boolean,
        match: RestoreMatch,
        unknownClock: Boolean = false,
    ): Pair<Boolean, String> {
        val clock = source.clock
        return when {
            !planReady || match is RestoreMatch.Blocked -> false to ""
            cartHasClock && clock != null && clock.plausible -> true to ""
            cartHasClock && clock != null -> false to "The backup's clock values are out of range, so the clock cannot be restored."
            cartHasClock -> false to "This backup has no clock data. The cartridge clock will not be changed."
            clock != null && unknownClock -> false to "The cartridge could not be checked for a clock, so the backup's clock data is ignored. The cartridge clock will not be changed."
            clock != null -> false to "The cartridge shows no clock, so the backup's clock data is ignored. The cartridge clock will not be changed."
            else -> false to ""
        }
    }

    /** The cartridge title the user has to type, exactly as the header shows it. */
    fun requiredTitle(header: DmgHeader): String = header.title.trim().ifEmpty { "UNTITLED" }

    /**
     * For a GBA cartridge: the title, or the game code when the backup has the same title (many regions share one:
     * "POKEMON SAPP" for AXPE, AXPF, AXPD ...), so that typing is never a no-op.
     */
    fun requiredTitle(header: AgbHeader, sourceGame: GameInfo?): String {
        val title = header.title.trim().ifEmpty { "UNTITLED" }
        val sameTitle = sourceGame != null && GameInfo.sanitizeTitle(sourceGame.title) == GameInfo.sanitizeTitle(header.title)
        return if (sameTitle && header.gameCode.isNotEmpty()) header.gameCode else title
    }

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

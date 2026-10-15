// Offsets ported from PKHeX.Core/Saves/Blocks/Gen3/SaveBlock3{Small,Large}{RS,E,FRLG}.cs, Items/Bags/PlayerBag3{RS,E,FRLG}.cs
// and Saves/SAV3{RS,E,FRLG}.cs (PKHeX, GPL-3.0, commit 641c26b).
package org.noxeng.coincell.pkcore.gba

import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.PocketType

/** One bag pocket: offset inside the inventory block, slot count, highest stack. */
internal class Pouch3(val type: PocketType, val offset: Int, val size: Int, val maxCount: Int)

/** Where one game family keeps things in its "small" (trainer) and "large" (party, bag, flags) save objects. */
internal class Gen3Layout(
    val game: Game,
    // ---- large block
    val partyCount: Int,
    val party: Int,
    val money: Int,
    val coins: Int,
    val inventory: Int,
    val eventFlags: Int,
    val eventFlagCount: Int,
    val badgeFlagStart: Int,
    val seenOffset2: Int,
    val seenOffset3: Int,
    /** FireRed/LeafGreen only (8 bytes); -1 elsewhere. */
    val rivalName: Int,
    // ---- small block
    /** Security key that masks money, coins and bag quantities; -1 for Ruby/Sapphire, which have none. */
    val securityKey: Int,
    val pouches: List<Pouch3>,
) {
    companion object {
        val RS = Gen3Layout(
            Game.RUBY_SAPPHIRE,
            partyCount = 0x234, party = 0x238, money = 0x490, coins = 0x494, inventory = 0x498,
            eventFlags = 0x1220, eventFlagCount = 8 * 288, badgeFlagStart = 0x807,
            seenOffset2 = 0x938, seenOffset3 = 0x3A8C, rivalName = -1, securityKey = -1,
            pouches = listOf(
                Pouch3(PocketType.ITEMS, 0x0C8, 20, 99),
                Pouch3(PocketType.KEY_ITEMS, 0x118, 20, 1),
                Pouch3(PocketType.BALLS, 0x168, 16, 99),
                Pouch3(PocketType.TM_HM, 0x1A8, 64, 99),
                Pouch3(PocketType.BERRIES, 0x2A8, 46, 999),
                Pouch3(PocketType.PC_ITEMS, 0x000, 50, 999),
            ),
        )

        val E = Gen3Layout(
            Game.EMERALD,
            partyCount = 0x234, party = 0x238, money = 0x490, coins = 0x494, inventory = 0x498,
            eventFlags = 0x1270, eventFlagCount = 8 * 300, badgeFlagStart = 0x867,
            seenOffset2 = 0x988, seenOffset3 = 0x3B24, rivalName = -1, securityKey = 0x0AC,
            pouches = listOf(
                Pouch3(PocketType.ITEMS, 0x0C8, 30, 99),
                Pouch3(PocketType.KEY_ITEMS, 0x140, 30, 1),
                Pouch3(PocketType.BALLS, 0x1B8, 16, 99),
                Pouch3(PocketType.TM_HM, 0x1F8, 64, 99),
                Pouch3(PocketType.BERRIES, 0x2F8, 46, 999),
                Pouch3(PocketType.PC_ITEMS, 0x000, 50, 999),
            ),
        )

        val FRLG = Gen3Layout(
            Game.FIRERED_LEAFGREEN,
            partyCount = 0x034, party = 0x038, money = 0x290, coins = 0x294, inventory = 0x298,
            eventFlags = 0xEE0, eventFlagCount = 8 * 288, badgeFlagStart = 0x820,
            seenOffset2 = 0x5F8, seenOffset3 = 0x3A18, rivalName = 0x3A4C, securityKey = 0xF20,
            pouches = listOf(
                Pouch3(PocketType.ITEMS, 0x078, 42, 999),
                Pouch3(PocketType.KEY_ITEMS, 0x120, 30, 1),
                Pouch3(PocketType.BALLS, 0x198, 13, 999),
                Pouch3(PocketType.TM_HM, 0x1CC, 58, 999),
                Pouch3(PocketType.BERRIES, 0x2B4, 43, 999),
                Pouch3(PocketType.PC_ITEMS, 0x000, 30, 999),
            ),
        )
    }
}

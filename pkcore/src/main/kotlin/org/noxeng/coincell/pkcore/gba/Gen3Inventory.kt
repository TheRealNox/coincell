// Ported from PKHeX.Core/Saves/Substructures/Inventory/Pouch/InventoryPouch3.cs, Items/Bags/PlayerBag3{RS,E,FRLG}.cs
// and Items/ItemStorage3{RS,E,FRLG}.cs (PKHeX, GPL-3.0, commit 641c26b).
package org.noxeng.coincell.pkcore.gba

import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.ItemPocket
import org.noxeng.coincell.pkcore.ItemStack
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.data.Gen3Tables
import org.noxeng.coincell.pkcore.data.ItemInfo
import org.noxeng.coincell.pkcore.gb.setU16le
import org.noxeng.coincell.pkcore.gb.u16le

/**
 * The bag of a Gen 3 save: every pocket is an array of (item id, quantity) u16 pairs. Emerald and FireRed/LeafGreen
 * XOR the quantities of the bag pockets (not the PC) with the low 16 bits of the security key.
 */
internal class Gen3Inventory(private val layout: Gen3Layout) {
    /** Item ids that may be stored in the pocket (PKHeX `ItemStorage3*.GetItems`). */
    fun validItems(type: PocketType): List<Int> {
        val t = Gen3Tables
        return when (type) {
            PocketType.ITEMS -> t.general.toList()
            PocketType.KEY_ITEMS -> when (layout.game) {
                Game.EMERALD -> t.keyE.toList()
                Game.FIRERED_LEAFGREEN -> t.keyFRLG.toList()
                else -> t.keyRS.toList()
            }
            PocketType.BALLS -> t.balls.toList()
            PocketType.TM_HM -> t.machine.toList()
            PocketType.BERRIES -> t.berries.toList()
            // PC items: R/S [General, Key, Berry, Balls, Machine]; E [General, Berry, Balls, Machine]; FR/LG [General].
            PocketType.PC_ITEMS -> when (layout.game) {
                Game.EMERALD -> (t.general + t.berries + t.balls + t.machine).toList()
                Game.FIRERED_LEAFGREEN -> t.general.toList()
                else -> (t.general + t.keyRS + t.berries + t.balls + t.machine).toList()
            }
        }
    }

    private fun pouch(type: PocketType): Pouch3 =
        layout.pouches.firstOrNull { it.type == type } ?: throw InvalidEditException("This game has no $type pocket")

    private fun keyFor(type: PocketType, small: ByteArray): Int =
        if (type == PocketType.PC_ITEMS || layout.securityKey < 0) 0 else small.u16le(layout.securityKey)

    /** PKHeX `PlayerBag3*.GetMaxCount`: HMs are limited to 1. */
    fun maxCount(type: PocketType, itemId: Int): Int =
        if (type == PocketType.TM_HM && ItemInfo.isHm3(itemId)) 1 else pouch(type).maxCount

    fun read(large: ByteArray, small: ByteArray): List<ItemPocket> = layout.pouches.map { p ->
        val valid = validItems(p.type)
        ItemPocket(
            p.type, p.size, p.maxCount, readItems(large, small, p), valid,
            singleStackItems = valid.filter { maxCount(p.type, it) == 1 }.toSet(),
        )
    }

    /** `InventoryPouch3.GetPouch`, minus the empty slots (item id 0). */
    private fun readItems(large: ByteArray, small: ByteArray, p: Pouch3): List<ItemStack> {
        val key = keyFor(p.type, small)
        val base = layout.inventory + p.offset
        val items = ArrayList<ItemStack>()
        for (i in 0 until p.size) {
            val id = large.u16le(base + i * 4)
            if (id == 0) continue
            items += ItemStack(id, large.u16le(base + i * 4 + 2) xor key)
        }
        return items
    }

    /**
     * `InventoryPouch3.SetPouch` after `ClearCount0`: the stacks in order, then empty slots (id 0, quantity 0 masked
     * with the key, as the games leave them).
     */
    fun write(large: ByteArray, small: ByteArray, type: PocketType, requested: List<ItemStack>) {
        val p = pouch(type)
        val items = requested.filter { it.count != 0 }
        if (items.size > p.size) throw InvalidEditException("$type holds at most ${p.size} different items")
        // Like the Gen 1/2 bag, only the id range is checked: a save may already hold items outside PKHeX's lists, and
        // editing another stack must not fail because of them. The editor only offers the pocket's valid items.
        for (item in items) {
            if (item.id !in 1..MAX_ITEM_ID) throw InvalidEditException("Invalid item id ${item.id}")
            if (item.count !in 0..maxCount(type, item.id)) throw InvalidEditException("Invalid item count ${item.count}")
        }
        val key = keyFor(type, small)
        val base = layout.inventory + p.offset
        for (i in 0 until p.size) {
            val item = items.getOrNull(i)
            large.setU16le(base + i * 4, item?.id ?: 0)
            large.setU16le(base + i * 4 + 2, (item?.count ?: 0) xor key)
        }
    }

    private companion object {
        /** Highest Generation 3 item id (PKHeX `Legal.MaxItemID_3_E`). */
        const val MAX_ITEM_ID = 376
    }
}

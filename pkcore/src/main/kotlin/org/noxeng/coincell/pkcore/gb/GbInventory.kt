// Ported from PKHeX.Core/Saves/Substructures/Inventory/Pouch/InventoryPouchGB.cs, Items/Bags/PlayerBag1.cs,
// PlayerBag2.cs, Items/ItemStorage1.cs, ItemStorage2.cs (PKHeX, GPL-3.0, commit 641c26b).
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.ItemPocket
import org.noxeng.coincell.pkcore.ItemStack
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.data.ItemInfo
import org.noxeng.coincell.pkcore.data.ItemTables

/** Location and size of one pocket inside the save (`new InventoryPouchGB(offset, size, maxCount, ...)`). */
internal class PouchDef(val type: PocketType, val offset: Int, val size: Int, val maxCount: Int = 99)

/** Reads and writes the bag pockets of a Gen 1 / Gen 2 save. */
internal class GbInventory(private val generation: Int, private val pouches: List<PouchDef>, private val crystal: Boolean) {
    val types: List<PocketType> get() = pouches.map { it.type }

    /** Item ids that can exist in the pocket (PKHeX `ItemStorage1/2.GetItems`). */
    fun validItems(type: PocketType): List<Int> {
        if (generation == 1) return ItemTables.gen1General.toList()
        return when (type) {
            PocketType.ITEMS -> ItemTables.gen2General.toList()
            PocketType.KEY_ITEMS -> (if (crystal) ItemTables.gen2KeyCrystal else ItemTables.gen2KeyGS).toList()
            PocketType.TM_HM -> ItemTables.gen2Machine.toList()
            PocketType.BALLS -> ItemTables.gen2Balls.toList()
            PocketType.PC_ITEMS -> pcItems()
            PocketType.BERRIES -> emptyList() // Generation 3 only
        }
    }

    // PCItemsC = [..General, ..Balls, ..Machine, ..KeyCrystal]; GS drops the 4 Crystal-only key items.
    private fun pcItems(): List<Int> {
        val all = ItemTables.gen2General.toList() + ItemTables.gen2Balls.toList() +
            ItemTables.gen2Machine.toList() + ItemTables.gen2KeyCrystal.toList()
        return if (crystal) all else all.dropLast(EXTRA_KEY_CRYSTAL)
    }

    private fun def(type: PocketType): PouchDef =
        pouches.firstOrNull { it.type == type } ?: throw InvalidEditException("This game has no $type pocket")

    fun read(data: ByteArray): List<ItemPocket> = pouches.map { read(data, it) }

    private fun read(data: ByteArray, def: PouchDef): ItemPocket {
        val valid = validItems(def.type)
        return ItemPocket(
            def.type, def.size, def.maxCount, readItems(data, def), valid,
            singleStackItems = valid.filter { maxCount(def.type, it) == 1 }.toSet(),
        )
    }

    /** `InventoryPouchGB.GetPouch`, minus the empty filler entries. */
    private fun readItems(data: ByteArray, def: PouchDef): List<ItemStack> {
        val items = ArrayList<ItemStack>()
        val ofs = def.offset
        if (def.type == PocketType.TM_HM) {
            val legal = validItems(def.type)
            for (i in 0 until def.size) {
                val count = data[ofs + i].toInt() and 0xFF
                if (count == 0) continue
                items.add(ItemStack(legal[i], count))
            }
            return items
        }
        var numStored = data[ofs].toInt() and 0xFF
        if (numStored > def.size) numStored = 0 // uninitialized yellow (0xFF), sanity check for out-of-bounds values
        for (i in 0 until numStored) {
            if (def.type == PocketType.KEY_ITEMS) {
                items.add(ItemStack(data[ofs + i + 1].toInt() and 0xFF, 1))
            } else {
                items.add(ItemStack(data[ofs + i * 2 + 1].toInt() and 0xFF, data[ofs + i * 2 + 2].toInt() and 0xFF))
            }
        }
        return items
    }

    /** `InventoryPouchGB.SetPouch` after `ClearCount0`. */
    fun write(data: ByteArray, type: PocketType, requested: List<ItemStack>) {
        val def = def(type)
        val items = requested.filter { it.count != 0 }
        if (items.size > def.size) throw InvalidEditException("$type holds at most ${def.size} different items")
        for (item in items) {
            if (item.id !in 1..255) throw InvalidEditException("Invalid item id ${item.id}")
            if (item.count !in 0..255) throw InvalidEditException("Invalid item count ${item.count}")
        }
        val ofs = def.offset
        when (type) {
            PocketType.TM_HM -> {
                val legal = validItems(type)
                // Deviation: PKHeX never zeroes the slot of a removed TM/HM, we clear the whole pocket first.
                data.fill(0, ofs, ofs + def.size)
                for (item in items) {
                    val index = legal.indexOf(item.id)
                    if (index < 0) throw InvalidEditException("Item ${item.id} does not belong in the TM/HM pocket")
                    data[ofs + index] = item.count.toByte()
                }
            }
            PocketType.KEY_ITEMS -> {
                data[ofs] = items.size.toByte()
                for (i in 0 until def.size) data[ofs + i + 1] = (items.getOrNull(i)?.id ?: 0).toByte()
                data[ofs + 1 + items.size] = 0xFF.toByte()
            }
            else -> {
                data[ofs] = items.size.toByte()
                for (i in 0 until def.size) {
                    val item = items.getOrNull(i)
                    data[ofs + i * 2 + 1] = (item?.id ?: 0).toByte()
                    data[ofs + i * 2 + 2] = (item?.count ?: 0).toByte()
                }
                data[ofs + 1 + 2 * items.size] = 0xFF.toByte()
            }
        }
    }

    /** Max quantity of one stack: HMs are limited to 1 (`PlayerBag1/2.GetMaxCount`). */
    fun maxCount(type: PocketType, itemId: Int): Int {
        val isHm = type == PocketType.TM_HM && (if (generation == 1) ItemInfo.isHm1(itemId) else ItemInfo.isHm2(itemId))
        return if (isHm) 1 else def(type).maxCount
    }

    private companion object {
        const val EXTRA_KEY_CRYSTAL = 4
    }
}

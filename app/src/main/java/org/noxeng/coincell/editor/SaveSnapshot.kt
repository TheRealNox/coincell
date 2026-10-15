package org.noxeng.coincell.editor

import org.noxeng.coincell.pkcore.BoxSummary
import org.noxeng.coincell.pkcore.Inventory
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.Trainer

/** Pokedex progress: species marked seen and caught, out of [total] (151, 251 or 386 by generation). */
data class DexCounts(val seen: Int, val caught: Int, val total: Int) {
    companion object {
        fun of(save: SaveFile): DexCounts {
            val total = PokemonEdits.maxSpecies(save.generation)
            return DexCounts(
                seen = (1..total).count { save.isSeen(it) },
                caught = (1..total).count { save.isCaught(it) },
                total = total,
            )
        }
    }
}

/** Immutable read-out of a [SaveFile] (everything the editor screens show), cheap to compare for the change summary. */
data class SaveSnapshot(
    val trainer: Trainer,
    val party: List<PokemonSummary>,
    val boxes: List<BoxSummary>,
    val inventory: Inventory,
    val currentBox: Int,
    val dex: DexCounts,
) {
    fun pokemonAt(slot: SlotRef): PokemonSummary? = when (slot) {
        is SlotRef.Party -> party.getOrNull(slot.index)
        is SlotRef.Box -> boxes.getOrNull(slot.box)?.slots?.getOrNull(slot.index)
    }

    val boxedCount: Int get() = boxes.sumOf { it.pokemon.size }

    /** Every Pokemon with its place, party first. */
    fun all(): List<Pair<SlotRef, PokemonSummary>> =
        party.mapIndexed { i, p -> SlotRef.Party(i) to p } +
            boxes.flatMap { b -> b.slots.mapIndexedNotNull { i, p -> p?.let { SlotRef.Box(b.index, i) to it } } }

    companion object {
        fun of(save: SaveFile): SaveSnapshot =
            SaveSnapshot(save.trainer, save.party, save.boxes, save.inventory, save.currentBox, DexCounts.of(save))
    }
}

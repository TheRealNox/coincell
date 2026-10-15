// Shared plumbing of the Gen 1 / Gen 2 saves. Behaviour follows PKHeX.Core/Saves/SaveFile.cs (party/box slot
// operations: SetPartySlotAtIndex, DeletePartySlot, SetPartyValues, UpdatePKM/SetDex) and SAV1.cs / SAV2.cs
// (PKHeX, GPL-3.0, commit 641c26b).
//
// Deliberate design difference: PKHeX keeps every list unpacked in one big buffer and re-packs *all* of them on
// export. Here lists are kept as compact slot lists and only the lists that were edited are re-packed, so an
// untouched save exports byte-identically (including any leftover bytes PKHeX would have zeroed) and an edited
// save only changes what was edited, plus the checksums. Re-packing a list is exactly PKHeX's `MergeSingles`.
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.BoxSummary
import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.Inventory
import org.noxeng.coincell.pkcore.ItemStack
import org.noxeng.coincell.pkcore.Pokemon
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.Region
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.Trainer
import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.data.SpeciesName
import org.noxeng.coincell.pkcore.text.LanguageId
import org.noxeng.coincell.pkcore.text.StringConverter1

internal abstract class GbSave(
    original: ByteArray,
    info: SaveTypeInfo,
    final override val boxCount: Int,
    final override val boxCapacity: Int,
) : SaveFile {
    final override val game: Game = info.game
    final override val region: Region = info.region
    final override val generation: Int = info.generation
    final override val partyCapacity: Int = PARTY_CAPACITY
    final override val compactBoxes: Boolean = true

    protected val pristine: ByteArray = original.copyOf()

    /** Working copy of the save (PKHeX `SaveFile.Data`). */
    protected val data: ByteArray = original.copyOf()

    val japanese: Boolean = info.region == Region.JAPANESE
    protected val format = PokeListFormat(generation, japanese)

    protected val partyList = ArrayList<Slot>()
    protected val boxLists: List<ArrayList<Slot>> = List(boxCount) { ArrayList() }

    protected var dirtyParty = false
    protected val dirtyBoxes = BooleanArray(boxCount)

    /** Trainer, inventory, pokedex... anything not in a Pokemon list. */
    protected var dirtyOther = false

    final override var language: Int = LanguageId.ENGLISH
        protected set

    private val strings: GameStrings get() = GameStrings.forLanguage(language)

    final override val isModified: Boolean get() = dirtyParty || dirtyOther || dirtyBoxes.any { it }

    protected abstract val gbInventory: GbInventory

    // ---- hooks -----------------------------------------------------------------------------------------------------
    protected abstract fun readTrainer(): Trainer
    protected abstract fun writeTrainer(trainer: Trainer)

    /** Writes the (dirty) lists, mirrors/backups and checksums into [data]. */
    protected abstract fun finalizeData()
    protected abstract fun dexOffsets(): Pair<Int, Int>
    protected abstract fun canSetDex(pk: GbPokemon): Boolean
    protected open fun afterCaught(species: Int) {}

    // ---- language --------------------------------------------------------------------------------------------------
    /**
     * Decides the text language: Japanese saves are Japanese, otherwise [requested] or a vote over the default
     * nicknames of all Pokemon (deviation: PKHeX uses language 0 = English tables for the save-level strings).
     */
    protected fun resolveLanguage(requested: Int?) {
        language = when {
            japanese -> LanguageId.JAPANESE
            requested != null -> requested
            else -> guessLanguage()
        }
        for (slot in allSlots()) slot.pk.fallbackLanguage = language
    }

    private fun allSlots(): Sequence<Slot> = partyList.asSequence() + boxLists.asSequence().flatten()

    private fun guessLanguage(): Int {
        val candidates = listOf(LanguageId.ENGLISH, LanguageId.FRENCH, LanguageId.ITALIAN, LanguageId.GERMAN, LanguageId.SPANISH)
        val votes = HashMap<Int, Int>()
        for (slot in allSlots()) {
            val pk = slot.pk
            if (pk.isEgg) continue
            if (StringConverter1.isG12German(pk.otTrash)) {
                votes.merge(LanguageId.GERMAN, 1, Int::plus)
                continue
            }
            val nick = StringConverter1.getString(pk.nickTrash, 0, pk.nickTrash.size, false)
            val matches = candidates.filter { nick == SpeciesName.getGeneration12(pk.species, it) }
            if (matches.size == 1) votes.merge(matches[0], 1, Int::plus)
        }
        return candidates.maxByOrNull { votes[it] ?: 0 }?.takeIf { (votes[it] ?: 0) > 0 } ?: LanguageId.ENGLISH
    }

    // ---- trainer ---------------------------------------------------------------------------------------------------
    final override val trainer: Trainer get() = readTrainer()

    final override fun updateTrainer(trainer: Trainer) {
        val before = readTrainer()
        if (before == trainer) return
        writeTrainer(trainer)
        dirtyOther = true
    }

    // ---- slots -----------------------------------------------------------------------------------------------------
    final override val party: List<PokemonSummary> get() = partyList.map { it.pk.summary(strings) }

    final override val boxes: List<BoxSummary>
        get() = List(boxCount) { i ->
            val list = boxLists[i].map { it.pk.summary(strings) }
            BoxSummary(i, boxName(i), i == currentBox, boxCapacity, List(boxCapacity) { slot -> list.getOrNull(slot) })
        }

    private fun containerOf(slot: SlotRef): MutableList<Slot> = when (slot) {
        is SlotRef.Party -> {
            if (slot.index !in 0 until PARTY_CAPACITY) throw InvalidEditException("Party slot ${slot.index} out of range")
            partyList
        }
        is SlotRef.Box -> {
            if (slot.box !in 0 until boxCount) throw InvalidEditException("Box ${slot.box} out of range (0..${boxCount - 1})")
            if (slot.index !in 0 until boxCapacity) throw InvalidEditException("Box slot ${slot.index} out of range")
            boxLists[slot.box]
        }
    }

    private fun indexOf(slot: SlotRef): Int = when (slot) {
        is SlotRef.Party -> slot.index
        is SlotRef.Box -> slot.index
    }

    private fun markDirty(slot: SlotRef) {
        when (slot) {
            is SlotRef.Party -> dirtyParty = true
            is SlotRef.Box -> dirtyBoxes[slot.box] = true
        }
    }

    private fun capacityOf(slot: SlotRef): Int = if (slot is SlotRef.Party) PARTY_CAPACITY else boxCapacity

    final override fun getPokemon(slot: SlotRef): Pokemon? = containerOf(slot).getOrNull(indexOf(slot))?.pk?.duplicate()

    private fun requireCompatible(pokemon: Pokemon): GbPokemon {
        val pk = pokemon as? GbPokemon
        if (pk == null || pk.generation != generation) {
            throw InvalidEditException("A Generation $generation Pokemon is required for this save")
        }
        if (pk.isJapanese != japanese) {
            throw InvalidEditException("Japanese and international Pokemon can't be mixed (text buffers differ)")
        }
        if (pk.species == 0) throw InvalidEditException("Can't store an empty Pokemon (species 0)")
        return pk
    }

    /** Conditions a Pokemon for the party or box format (`SetPartyValues` / `WriteSlotStored`). */
    private fun adapt(pk: GbPokemon, toParty: Boolean) {
        if (toParty) {
            if (!pk.partyStatsPresent) pk.resetPartyStats() // data from a box has no party stats
        } else {
            // Box slots only keep the stored part; the rest is rebuilt when the Pokemon enters the party again.
            // Gen 1 additionally mirrors the level into the box-level byte (SAV1.WriteSlotStored).
            if (pk is Pk1) pk.levelBox = pk.level
            pk.data.fill(0, pk.layout.storedSize, pk.layout.partySize)
        }
    }

    final override fun setPokemon(slot: SlotRef, pokemon: Pokemon, updatePokedex: Boolean) {
        val pk = requireCompatible(pokemon).duplicate() as GbPokemon
        pk.fallbackLanguage = language
        val list = containerOf(slot)
        val index = indexOf(slot)
        if (index > list.size) {
            throw InvalidEditException("Slots are contiguous: slot $index is beyond the first free slot (${list.size})")
        }
        if (index == list.size && list.size >= capacityOf(slot)) throw InvalidEditException("Container is full")
        if (updatePokedex) setDex(pk)
        adapt(pk, slot is SlotRef.Party)
        val entry = Slot(pk, format.headerMark(pk))
        if (index == list.size) list.add(entry) else list[index] = entry
        markDirty(slot)
    }

    final override fun addPokemon(pokemon: Pokemon, updatePokedex: Boolean): SlotRef? {
        for (step in 0 until boxCount) {
            val box = (currentBox.coerceIn(0, boxCount - 1) + step) % boxCount
            if (boxLists[box].size < boxCapacity) {
                val ref = SlotRef.Box(box, boxLists[box].size)
                setPokemon(ref, pokemon, updatePokedex)
                return ref
            }
        }
        return null
    }

    final override fun removePokemon(slot: SlotRef): Pokemon? {
        val list = containerOf(slot)
        val index = indexOf(slot)
        if (index >= list.size) return null
        val removed = list.removeAt(index)
        markDirty(slot)
        return removed.pk
    }

    final override fun movePokemon(from: SlotRef, to: SlotRef) {
        val src = containerOf(from)
        val dst = containerOf(to)
        val si = indexOf(from)
        val di = indexOf(to)
        if (si >= src.size) throw InvalidEditException("Nothing to move at $from")
        if (di > dst.size) throw InvalidEditException("Slots are contiguous: $to is beyond the first free slot (${dst.size})")
        if (src === dst && si == di) return
        val toParty = to is SlotRef.Party
        val fromParty = from is SlotRef.Party

        val moving = src[si].pk
        if (di < dst.size) { // swap
            val other = dst[di].pk
            adapt(moving, toParty)
            adapt(other, fromParty)
            src[si] = Slot(other, format.headerMark(other))
            dst[di] = Slot(moving, format.headerMark(moving))
        } else {
            if (dst.size >= capacityOf(to)) throw InvalidEditException("Destination is full")
            adapt(moving, toParty)
            src.removeAt(si)
            dst.add(Slot(moving, format.headerMark(moving)))
        }
        markDirty(from)
        markDirty(to)
    }

    // ---- inventory -------------------------------------------------------------------------------------------------
    final override val inventory: Inventory get() = Inventory(gbInventory.read(data))

    final override fun setPocket(type: PocketType, items: List<ItemStack>) {
        gbInventory.write(data, type, items)
        dirtyOther = true
    }

    // ---- pokedex ---------------------------------------------------------------------------------------------------
    final override fun isSeen(species: Int): Boolean = getDexFlag(dexOffsets().first, species)
    final override fun isCaught(species: Int): Boolean = getDexFlag(dexOffsets().second, species)

    private fun getDexFlag(region: Int, species: Int): Boolean {
        if (species < 1) return false
        val bit = species - 1
        return (data[region + (bit shr 3)].toInt() shr (bit and 7)) and 1 != 0
    }

    private fun setDexFlag(region: Int, species: Int) {
        val bit = species - 1
        data[region + (bit shr 3)] = (data[region + (bit shr 3)].toInt() or (1 shl (bit and 7))).toByte()
    }

    /** PKHeX `SetDex`: marks the species as caught and seen (not for eggs / invalid species). */
    private fun setDex(pk: GbPokemon) {
        if (!canSetDex(pk)) return
        val (seen, caught) = dexOffsets()
        val wasCaught = getDexFlag(caught, pk.species)
        val before = wasCaught && getDexFlag(seen, pk.species)
        setDexFlag(caught, pk.species)
        // Deviation: PKHeX re-applies the Unown form flags on every Unown it sets; we only do so the first time,
        // so an already complete Pokedex is left byte-for-byte alone.
        if (!wasCaught) afterCaught(pk.species)
        setDexFlag(seen, pk.species)
        if (!before) dirtyOther = true
    }

    // ---- export ----------------------------------------------------------------------------------------------------
    final override fun export(): ByteArray {
        if (!isModified) return pristine.copyOf()
        finalizeData()
        return data.copyOf()
    }

    /**
     * What PKHeX would export for the loaded state: every list re-packed and all checksums/backups rewritten,
     * whether or not anything was edited. Used by tests to compare against the byte-exact fast path.
     */
    internal fun exportLikePkhex(): ByteArray {
        dirtyParty = true
        dirtyBoxes.fill(true)
        dirtyOther = true
        finalizeData()
        return data.copyOf()
    }

    protected companion object {
        const val PARTY_CAPACITY = 6
    }
}

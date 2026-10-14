package com.therealnox.gbflash.editor

import com.therealnox.gbflash.pkcore.InvalidEditException
import com.therealnox.gbflash.pkcore.ItemStack
import com.therealnox.gbflash.pkcore.PocketType
import com.therealnox.gbflash.pkcore.SaveFile
import com.therealnox.gbflash.pkcore.SlotRef
import com.therealnox.gbflash.pkcore.Trainer
import com.therealnox.gbflash.pkcore.data.GameStrings
import com.therealnox.gbflash.pkcore.gb.GbPokemon
import com.therealnox.gbflash.pkcore.gb.Pk1
import com.therealnox.gbflash.pkcore.gb.Pk2
import com.therealnox.gbflash.pkcore.text.LanguageId

/**
 * The editing model behind the editor screens: an in-memory working copy of a save (never the library entry), the
 * state it started from, and every operation of milestone 6. No Android in here, so it is unit tested on the JVM.
 *
 * Operations apply at once and throw [InvalidEditException] (nothing is changed then) with a message for the user.
 */
class SaveEditor private constructor(
    private var baselineBytes: ByteArray,
    private var save: SaveFile,
    /** The language the save was detected with; kept so that reloading never re-guesses it differently. */
    val language: Int,
) {
    private var baseline: SaveSnapshot = SaveSnapshot.of(save)

    val game get() = save.game
    val generation: Int get() = save.generation
    val isJapanese: Boolean get() = save.region == com.therealnox.gbflash.pkcore.Region.JAPANESE
    val boxCount: Int get() = save.boxCount
    val boxCapacity: Int get() = save.boxCapacity
    val partyCapacity: Int get() = save.partyCapacity
    val info: PokemonSaveInfo get() = PokemonSaveInfo(save.game, save.region, language)

    /** Names in the save's language, and English as the second line when they differ. */
    val strings: GameStrings get() = GameStrings.forLanguage(language)
    val englishStrings: GameStrings get() = GameStrings.forLanguage(LanguageId.ENGLISH)

    fun snapshot(): SaveSnapshot = SaveSnapshot.of(save)

    /** What differs from the state the editor started from (or was last saved). Empty means nothing to save. */
    fun changes(current: SaveSnapshot = snapshot()): List<Change> = SaveDiff.compute(baseline, current, strings, generation)

    // ---- trainer ----------------------------------------------------------------------------------------------------
    fun updateTrainer(trainer: Trainer) = save.updateTrainer(trainer)

    // ---- Pokemon ----------------------------------------------------------------------------------------------------
    /** A copy of the Pokemon at [slot]; edit it and hand it back with [replace], or use [edit]. */
    fun pokemon(slot: SlotRef): GbPokemon? = save.getPokemon(slot) as? GbPokemon

    /**
     * Applies [block] to a copy of the Pokemon at [slot] and writes it back only if the block (and the final
     * consistency pass) succeeded. The Pokedex is only touched when the species changes.
     */
    fun edit(slot: SlotRef, block: (GbPokemon) -> Unit) {
        val pk = pokemon(slot) ?: throw InvalidEditException("There is no Pokemon in this slot any more.")
        val before = pk.species
        block(pk)
        PokemonEdits.finalize(pk, slot is SlotRef.Party)
        save.setPokemon(slot, pk, updatePokedex = pk.species != before)
    }

    /** Index of the first free slot of [slot]'s container (where a new Pokemon goes). */
    private fun firstFree(slot: SlotRef): Int = when (slot) {
        is SlotRef.Party -> save.party.size
        is SlotRef.Box -> save.boxes.getOrNull(slot.box)?.pokemon?.size ?: throw InvalidEditException("Box ${slot.box + 1} does not exist.")
    }

    private fun withIndex(slot: SlotRef, index: Int): SlotRef = when (slot) {
        is SlotRef.Party -> SlotRef.Party(index)
        is SlotRef.Box -> SlotRef.Box(slot.box, index)
    }

    /**
     * A new Pokemon of [species] at [level], owned by the trainer, in the first free slot of [slot]'s container
     * (slots are contiguous, so tapping any empty slot adds at the end). Returns where it went.
     */
    fun addPokemon(slot: SlotRef, species: Int, level: Int = DEFAULT_LEVEL): SlotRef {
        val pk = newPokemon(species, level)
        val target = withIndex(slot, firstFree(slot))
        val capacity = if (slot is SlotRef.Party) save.partyCapacity else save.boxCapacity
        val index = if (target is SlotRef.Party) target.index else (target as SlotRef.Box).index
        if (index >= capacity) throw InvalidEditException(if (slot is SlotRef.Party) "The party is full (6 Pokemon)." else "This box is full.")
        save.setPokemon(target, pk, updatePokedex = true)
        return target
    }

    /** A fresh level [level] Pokemon: perfect DVs, no stat experience, Tackle, the trainer as OT. */
    fun newPokemon(species: Int, level: Int): GbPokemon {
        val pk: GbPokemon = if (generation == 1) Pk1(isJapanese) else Pk2(isJapanese)
        pk.fallbackLanguage = language
        PokemonEdits.setSpecies(pk, species)
        pk.setNotNicknamed(language)
        val trainer = save.trainer
        pk.otName = trainer.name
        pk.trainerId = trainer.id
        pk.ivAttack = 15
        pk.ivDefense = 15
        pk.ivSpeed = 15
        pk.ivSpecial = 15
        PokemonEdits.setLevel(pk, level)
        pk.setMove(0, TACKLE)
        pk.setMovePp(0, pk.getMaxPp(TACKLE, 0))
        if (pk is Pk2) pk.friendship = BASE_FRIENDSHIP
        pk.storedLevel = pk.level
        pk.resetPartyStats()
        return pk
    }

    /** Removes the Pokemon at [slot] (the party must keep at least one). Returns it, or null if the slot was empty. */
    fun removePokemon(slot: SlotRef) {
        if (slot is SlotRef.Party && save.party.size <= 1 && save.party.isNotEmpty()) {
            throw InvalidEditException("The party needs at least one Pokemon. Move another one into it first.")
        }
        save.removePokemon(slot)
    }

    /**
     * Moves the Pokemon at [from] to [to]; an occupied target swaps, an empty one (anywhere in the empty tail of
     * the container, since slots are contiguous) takes it to the end.
     */
    fun movePokemon(from: SlotRef, to: SlotRef) {
        if (from == to) return
        if (save.getPokemon(from) == null) throw InvalidEditException("There is nothing to move here.")
        if (from is SlotRef.Party && to is SlotRef.Box && save.getPokemon(to) == null && save.party.size <= 1) {
            throw InvalidEditException("The party needs at least one Pokemon. Move another one into it first.")
        }
        val free = firstFree(to)
        val index = if (to is SlotRef.Party) to.index else (to as SlotRef.Box).index
        val normalized = if (index > free) withIndex(to, free) else to
        if (normalized == from) return
        save.movePokemon(from, normalized)
    }

    // ---- bag --------------------------------------------------------------------------------------------------------
    fun setPocket(type: PocketType, items: List<ItemStack>) = save.setPocket(type, items)

    // ---- lifecycle --------------------------------------------------------------------------------------------------
    /** Throws away every edit: the working copy is rebuilt from the bytes the editor started from. */
    fun revertAll() {
        save = SaveFile.load(baselineBytes, language)
    }

    /**
     * The edited save as bytes, with checksums fixed by `:pkcore`. Checked before it can reach the library: it must
     * have the size of the original and parse again with valid checksums.
     */
    fun exportChecked(): ByteArray {
        val out = save.export()
        check(out.size == baselineBytes.size) { "The edited save has a different size (${out.size} instead of ${baselineBytes.size} bytes)." }
        val again = SaveFile.load(out, language)
        check(again.checksumsValid) { "The edited save does not pass its own checksums." }
        check(again.game == save.game) { "The edited save is not read as the same game any more." }
        return out
    }

    /** After the edited save was stored: further edits are measured against it. */
    fun rebase(storedBytes: ByteArray) {
        baselineBytes = storedBytes.copyOf()
        save = SaveFile.load(baselineBytes, language)
        baseline = SaveSnapshot.of(save)
    }

    companion object {
        const val DEFAULT_LEVEL = 5
        private const val TACKLE = 33
        private const val BASE_FRIENDSHIP = 70

        /** @throws com.therealnox.gbflash.pkcore.UnsupportedSaveException if [bytes] is not a Gen 1/2 save */
        fun open(bytes: ByteArray): SaveEditor {
            val loaded = SaveFile.load(bytes)
            // Reload with the detected language forced, so every later reload decodes the same text.
            return SaveEditor(bytes.copyOf(), SaveFile.load(bytes, loaded.language), loaded.language)
        }
    }
}

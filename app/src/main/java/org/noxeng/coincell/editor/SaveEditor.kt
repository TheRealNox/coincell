package org.noxeng.coincell.editor

import org.noxeng.coincell.library.GameInfo
import org.noxeng.coincell.pkcore.EditablePokemon
import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.ItemStack
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.Trainer
import org.noxeng.coincell.pkcore.TrainerGender
import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.pkcore.gb.GbPokemon
import org.noxeng.coincell.pkcore.gb.Pk1
import org.noxeng.coincell.pkcore.gb.Pk2
import org.noxeng.coincell.pkcore.gba.Pk3
import org.noxeng.coincell.pkcore.text.LanguageId
import kotlin.random.Random

/**
 * The editing model behind the editor screens: an in-memory working copy of a save (never the library entry), the
 * state it started from, and every editing operation. No Android in here, so it is unit tested on the JVM.
 *
 * Operations apply at once and throw [InvalidEditException] (nothing is changed then) with a message for the user.
 */
class SaveEditor private constructor(
    private var baselineBytes: ByteArray,
    private var save: SaveFile,
    /** The language the save was detected with; kept so that reloading never re-guesses it differently. */
    val language: Int,
    /** Generation 3 game of origin given to new Pokemon (1 Sapphire, 2 Ruby, 3 Emerald, 4 FireRed, 5 LeafGreen). */
    private val originGame: Int,
    private val random: Random,
) {
    private var baseline: SaveSnapshot = SaveSnapshot.of(save)

    val game: Game get() = save.game
    val generation: Int get() = save.generation
    val isJapanese: Boolean get() = save.region == org.noxeng.coincell.pkcore.Region.JAPANESE
    val boxCount: Int get() = save.boxCount
    val boxCapacity: Int get() = save.boxCapacity
    val partyCapacity: Int get() = save.partyCapacity

    /** True when box slots are a packed list (Gen 1/2); Gen 3 boxes keep gaps. */
    val compactBoxes: Boolean get() = save.compactBoxes
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
    /** A copy of the Pokemon at [slot]; edit it and hand it back, or use [edit]. */
    fun pokemon(slot: SlotRef): EditablePokemon? = save.getPokemon(slot) as? EditablePokemon

    /**
     * Applies [block] to a copy of the Pokemon at [slot] and writes it back only if the block (and the final
     * consistency pass) succeeded. The Pokedex is only touched when the species changes.
     */
    fun edit(slot: SlotRef, block: (EditablePokemon) -> Unit) {
        val pk = pokemon(slot) ?: throw InvalidEditException("There is no Pokemon in this slot any more.")
        val before = pk.species
        block(pk)
        PokemonEdits.finalize(pk, slot is SlotRef.Party)
        save.setPokemon(slot, pk, updatePokedex = pk.species != before)
    }

    /** Index of the first free slot of [slot]'s container (where a new Pokemon goes), or the capacity when full. */
    private fun firstFree(slot: SlotRef): Int = when (slot) {
        is SlotRef.Party -> save.party.size
        is SlotRef.Box -> {
            val box = save.boxes.getOrNull(slot.box) ?: throw InvalidEditException("Box ${slot.box + 1} does not exist.")
            box.slots.indexOfFirst { it == null }.let { if (it < 0) box.capacity else it }
        }
    }

    private fun withIndex(slot: SlotRef, index: Int): SlotRef = when (slot) {
        is SlotRef.Party -> SlotRef.Party(index)
        is SlotRef.Box -> SlotRef.Box(slot.box, index)
    }

    private fun isEmpty(slot: SlotRef): Boolean = save.getPokemon(slot) == null

    /**
     * A new Pokemon of [species] at [level], owned by the trainer. It goes into [slot] when that is an empty slot of a
     * Gen 3 box; otherwise (the party, Gen 1/2 boxes, whose slots are contiguous) into the first free slot. Returns
     * where it went.
     */
    fun addPokemon(slot: SlotRef, species: Int, level: Int = DEFAULT_LEVEL): SlotRef {
        val pk = newPokemon(species, level)
        val target = if (slot is SlotRef.Box && !compactBoxes && isEmpty(slot)) slot else withIndex(slot, firstFree(slot))
        val capacity = if (slot is SlotRef.Party) save.partyCapacity else save.boxCapacity
        val index = if (target is SlotRef.Party) target.index else (target as SlotRef.Box).index
        if (index >= capacity) throw InvalidEditException(if (slot is SlotRef.Party) "The party is full (6 Pokemon)." else "This box is full.")
        save.setPokemon(target, pk, updatePokedex = true)
        return target
    }

    /**
     * A fresh level [level] Pokemon owned by the trainer, knowing Tackle: perfect DVs and no stat experience in
     * Generations 1/2; in Generation 3 perfect IVs, no EVs, a random non-shiny PID, a Poke Ball, the save's language.
     */
    fun newPokemon(species: Int, level: Int): EditablePokemon {
        val trainer = save.trainer
        val pk: EditablePokemon = when (generation) {
            1 -> Pk1(isJapanese)
            2 -> Pk2(isJapanese)
            else -> Pk3()
        }
        when (pk) {
            is GbPokemon -> {
                pk.fallbackLanguage = language
                PokemonEdits.setSpecies(pk, species)
                pk.setNotNicknamed(language)
                pk.otName = trainer.name
                pk.ivAttack = 15
                pk.ivDefense = 15
                pk.ivSpeed = 15
                pk.ivSpecial = 15
                if (pk is Pk2) pk.friendship = BASE_FRIENDSHIP
            }
            is Pk3 -> {
                pk.fallbackLanguage = language
                pk.language = if (isJapanese) LanguageId.JAPANESE else language
                pk.secretId = trainer.secretId ?: 0
                pk.trainerId = trainer.id
                PokemonEdits.setSpecies(pk, species)
                pk.resetNickname()
                pk.otName = trainer.name
                pk.originalTrainerGender = if (trainer.gender == TrainerGender.GIRL) 1 else 0
                for (i in 0 until 6) pk.setIv(i, PokemonEdits.MAX_IV)
                pk.friendship = PersonalTable.RS[species].baseFriendship
                pk.ball = POKE_BALL
                pk.originGame = originGame
                pk.metLocation = MET_FATEFUL
                pk.metLevel = level
                PokemonEdits.rerollPid(pk, nature = random.nextInt(25), shiny = false, form = 0, random = random)
            }
        }
        pk.trainerId = trainer.id
        PokemonEdits.setLevel(pk, level)
        pk.setMove(0, TACKLE)
        pk.setMovePp(0, pk.getMaxPp(TACKLE, 0))
        pk.storedLevel = pk.level
        pk.resetPartyStats()
        return pk
    }

    /** Removes the Pokemon at [slot] (the party must keep at least one). */
    fun removePokemon(slot: SlotRef) {
        if (slot is SlotRef.Party && save.party.size <= 1 && save.party.isNotEmpty()) {
            throw InvalidEditException("The party needs at least one Pokemon. Move another one into it first.")
        }
        save.removePokemon(slot)
    }

    /**
     * Moves the Pokemon at [from] to [to]; an occupied target swaps. An empty target in the party or in a Gen 1/2 box
     * (anywhere in the empty tail, since those slots are contiguous) takes it to the end; an empty Gen 3 box slot takes
     * it exactly there.
     */
    fun movePokemon(from: SlotRef, to: SlotRef) {
        if (from == to) return
        if (save.getPokemon(from) == null) throw InvalidEditException("There is nothing to move here.")
        if (from is SlotRef.Party && to is SlotRef.Box && save.getPokemon(to) == null && save.party.size <= 1) {
            throw InvalidEditException("The party needs at least one Pokemon. Move another one into it first.")
        }
        val contiguous = to is SlotRef.Party || compactBoxes
        val normalized = if (contiguous) {
            val free = firstFree(to)
            val index = if (to is SlotRef.Party) to.index else (to as SlotRef.Box).index
            if (index > free) withIndex(to, free) else to
        } else {
            to
        }
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
        private const val POKE_BALL = 4

        /** Gen 3 met location "(Fateful Encounter)": marks a Pokemon that did not come from the game itself. */
        private const val MET_FATEFUL = 255

        /**
         * Opens [bytes]; [game] (the library game) gives a Gen 3 save its language and version from the cartridge's
         * game code. @throws org.noxeng.coincell.pkcore.UnsupportedSaveException if [bytes] is not a Gen 1-3 save
         */
        fun open(bytes: ByteArray, game: GameInfo? = null, random: Random = Random.Default): SaveEditor {
            val loaded = SaveFile.load(bytes, PokemonSaveProbe.languageHint(game))
            // Reload with the detected language forced, so every later reload decodes the same text.
            return SaveEditor(bytes.copyOf(), SaveFile.load(bytes, loaded.language), loaded.language, originGame(loaded.game, game), random)
        }

        private fun originGame(save: Game, game: GameInfo?): Int {
            val code = game?.gameCode?.uppercase().orEmpty()
            return when {
                code.startsWith("AXV") -> 2
                code.startsWith("AXP") -> 1
                code.startsWith("BPE") -> 3
                code.startsWith("BPR") -> 4
                code.startsWith("BPG") -> 5
                save == Game.EMERALD -> 3
                save == Game.FIRERED_LEAFGREEN -> 4
                else -> 1
            }
        }
    }
}

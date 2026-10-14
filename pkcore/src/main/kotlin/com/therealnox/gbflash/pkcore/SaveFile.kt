// Original API types for :pkcore (not a PKHeX port): the generation-independent save file interface.
package com.therealnox.gbflash.pkcore

import com.therealnox.gbflash.pkcore.gb.SaveLoader

/** Which game family a save belongs to. Gen 3 (Ruby/Sapphire/Emerald/FireRed/LeafGreen) slots in here later. */
public enum class Game(public val generation: Int) {
    /** Red, Blue (and the Japanese Red/Green/Blue); PKHeX can't tell these apart either. */
    RED_BLUE(1),
    YELLOW(1),

    /** Gold or Silver (their saves are identical in structure). */
    GOLD_SILVER(2),
    CRYSTAL(2),
}

/** Region family of the save layout. */
public enum class Region {
    INTERNATIONAL,
    JAPANESE,

    /** Detected but not supported yet. */
    KOREAN,
}

/** Base type of the errors thrown by this module. */
public open class PkCoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The data is not a (supported) Gen 1/2 save, or it is a valid save of an unsupported kind. */
public class UnsupportedSaveException(message: String) : PkCoreException(message)

/** An edit operation was refused (bad slot, wrong generation, full container...). */
public class InvalidEditException(message: String) : PkCoreException(message)

/** Play time as shown by the game. */
public data class PlayTime(val hours: Int, val minutes: Int, val seconds: Int)

public enum class TrainerGender { BOY, GIRL }

/**
 * Immutable snapshot of the trainer card. Pass a modified copy to [SaveFile.updateTrainer]; only the
 * fields that differ from the current values are written.
 */
public data class Trainer(
    val name: String,
    /** Trainer ID (16 bit). */
    val id: Int,
    val money: Int,
    /** Raw badge bit mask (Gen 1: 8 Kanto badges; Gen 2: Johto in the low byte, Kanto in the high byte). */
    val badges: Int,
    val playTime: PlayTime,
    val rivalName: String,
    /** Crystal only, null where the game has no trainer gender. */
    val gender: TrainerGender?,
    /** Game Corner coins. */
    val coins: Int,
) {
    val badgeCount: Int get() = Integer.bitCount(badges)
}

/** Location of a Pokemon in a save. Both [Party] and [Box] indexes are 0-based and contiguous. */
public sealed interface SlotRef {
    public data class Party(val index: Int) : SlotRef
    public data class Box(val box: Int, val index: Int) : SlotRef
}

/** A PC box. */
public data class BoxSummary(
    val index: Int,
    val name: String,
    val isCurrent: Boolean,
    val capacity: Int,
    val pokemon: List<PokemonSummary>,
)

public enum class PocketType { ITEMS, KEY_ITEMS, TM_HM, BALLS, PC_ITEMS }

public data class ItemStack(val id: Int, val count: Int)

/**
 * One bag pocket (or the PC item box). Only occupied slots are listed in [items], in game order.
 * Item names: `GameStrings.forLanguage(lang).itemName(generation, id)`.
 */
public data class ItemPocket(
    val type: PocketType,
    /** Number of slots of the pocket. */
    val capacity: Int,
    /** Highest quantity of one stack (99). */
    val maxCount: Int,
    val items: List<ItemStack>,
    /** Item ids that may be stored in this pocket (PKHeX `IItemStorage.GetItems`). */
    val validItems: List<Int>,
    /** Items limited to a single copy (the HMs of Gen 2's TM/HM pocket). */
    val singleStackItems: Set<Int> = emptySet(),
) {
    /** Highest quantity of [itemId] in this pocket (PKHeX `PlayerBag.GetMaxCount`). */
    fun maxCountOf(itemId: Int): Int = if (itemId in singleStackItems) 1 else maxCount
}

public data class Inventory(val pockets: List<ItemPocket>) {
    public fun pocket(type: PocketType): ItemPocket? = pockets.firstOrNull { it.type == type }
}

/**
 * A loaded save file. Reads return immutable snapshots; edits go through the explicit operations below and
 * only reach the exported bytes with [export]. Generation-specific implementations hide behind this interface.
 *
 * Unchanged saves export byte-identically; edited saves are rewritten the way PKHeX writes them
 * (lists re-packed, checksums refreshed).
 */
public interface SaveFile {
    public val generation: Int
    public val game: Game
    public val region: Region

    /** Language id used to decode save-level text (trainer, rival and box names). */
    public val language: Int

    public val partyCapacity: Int
    public val boxCount: Int
    public val boxCapacity: Int
    public val currentBox: Int

    /** True once any edit was made after loading. */
    public val isModified: Boolean

    /** True if every checksum of the *current* in-memory state is valid (before [export] refreshes them). */
    public val checksumsValid: Boolean

    public val trainer: Trainer
    public fun updateTrainer(trainer: Trainer)

    public val party: List<PokemonSummary>
    public val boxes: List<BoxSummary>
    public fun boxName(box: Int): String

    /** A mutable copy of the Pokemon at [slot], or null if the slot is empty. */
    public fun getPokemon(slot: SlotRef): Pokemon?

    /**
     * Writes [pokemon] (a copy of it) at [slot]: replacing an occupied slot or appending at the first free index.
     * Like PKHeX this also marks the species as seen/caught when [updatePokedex] is set, and computes party stats
     * for a Pokemon that has none. The Pokemon must be of this save's generation and text format (Japanese or not).
     */
    public fun setPokemon(slot: SlotRef, pokemon: Pokemon, updatePokedex: Boolean = true)

    /** Appends to the first box with room, starting at the current box; returns where it went, or null if all boxes are full. */
    public fun addPokemon(pokemon: Pokemon, updatePokedex: Boolean = true): SlotRef?

    /** Removes the Pokemon at [slot]; the following slots move up. Returns it, or null if the slot was empty. */
    public fun removePokemon(slot: SlotRef): Pokemon?

    /** Moves the Pokemon at [from] to [to]; if [to] is occupied the two are swapped. */
    public fun movePokemon(from: SlotRef, to: SlotRef)

    public val inventory: Inventory

    /** Replaces the contents of one pocket (stacks with count 0 are dropped, like PKHeX). */
    public fun setPocket(type: PocketType, items: List<ItemStack>)

    public fun isSeen(species: Int): Boolean
    public fun isCaught(species: Int): Boolean

    /** The save as bytes, with checksums fixed exactly as PKHeX does. Same length as the loaded data. */
    public fun export(): ByteArray

    public companion object {
        /**
         * Loads raw SRAM (no RTC footer). [language] forces the text language of international saves; by default
         * it is guessed from the Pokemon nicknames (English if undecidable).
         * @throws UnsupportedSaveException if the data isn't a supported Gen 1/2 save.
         */
        public fun load(bytes: ByteArray, language: Int? = null): SaveFile = SaveLoader.load(bytes, language)
    }
}

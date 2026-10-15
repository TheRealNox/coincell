// Ported from PKHeX.Core/Saves/SAV1.cs and Saves/Substructures/Gen12/SAV1Offsets.cs (PKHeX, GPL-3.0, commit 641c26b).
// Not ported: Hall of Fame, event flags/work, daycare editing, Pikachu/Yellow extras, options, Stadium formats.
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.PlayTime
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.Region
import org.noxeng.coincell.pkcore.Trainer
import org.noxeng.coincell.pkcore.text.StringConverter1
import org.noxeng.coincell.pkcore.text.StringOption

/** Offsets of the Generation 1 save layout (international and Japanese). */
internal class Sav1Offsets(
    val dexCaught: Int, val dexSeen: Int, val items: Int, val money: Int, val rival: Int, val options: Int,
    val badges: Int, val tid16: Int, val pikaFriendship: Int, val pcItems: Int, val currentBoxIndex: Int,
    val hallOfFameCount: Int, val coin: Int, val playTime: Int, val daycare: Int, val party: Int,
    val currentBox: Int, val checksumOfs: Int,
) {
    val ot: Int get() = 0x2598

    companion object {
        val INT = Sav1Offsets(
            dexCaught = 0x25A3, dexSeen = 0x25B6, items = 0x25C9, money = 0x25F3, rival = 0x25F6, options = 0x2601,
            badges = 0x2602, tid16 = 0x2605, pikaFriendship = 0x271C, pcItems = 0x27E6, currentBoxIndex = 0x284C,
            hallOfFameCount = 0x284E, coin = 0x2850, playTime = 0x2CED, daycare = 0x2CF4, party = 0x2F2C,
            currentBox = 0x30C0, checksumOfs = 0x3523,
        )
        val JPN = Sav1Offsets(
            dexCaught = 0x259E, dexSeen = 0x25B1, items = 0x25C4, money = 0x25EE, rival = 0x25F1, options = 0x25F7,
            badges = 0x25F8, tid16 = 0x25FB, pikaFriendship = 0x2712, pcItems = 0x27DC, currentBoxIndex = 0x2842,
            hallOfFameCount = 0x2844, coin = 0x2846, playTime = 0x2CA0, daycare = 0x2CA7, party = 0x2ED5,
            currentBox = 0x302D, checksumOfs = 0x3594,
        )
    }
}

internal class Sav1(bytes: ByteArray, info: SaveTypeInfo, requestedLanguage: Int?) :
    GbSave(
        bytes, info,
        boxCount = if (info.region == Region.JAPANESE) 8 else 12,
        boxCapacity = if (info.region == Region.JAPANESE) 30 else 20,
    ) {
    private val off: Sav1Offsets = if (japanese) Sav1Offsets.JPN else Sav1Offsets.INT
    private val trainerNameLength: Int = if (japanese) 5 else 7

    /** `SIZE_BOX_LIST`: the length of one box list. */
    private val boxListLength: Int = format.listLength(boxCapacity, party = false)

    override val gbInventory: GbInventory = GbInventory(
        1,
        listOf(PouchDef(PocketType.ITEMS, off.items, 20), PouchDef(PocketType.PC_ITEMS, off.pcItems, 50)),
        crystal = false,
    )

    init {
        initialize()
        resolveLanguage(requestedLanguage)
    }

    /** Port of `SAV1.Initialize` (list loading part). */
    private fun initialize() {
        val current = currentBox
        if (boxesInitialized) { // Current box has flushed to box storage at least once, box contents are trustworthy.
            for (i in 0 until boxCount) {
                if (i == current) continue // Use the current box data instead, loaded a little later.
                boxLists[i].addAll(format.unpack(data, getBoxRawDataOffset(i), boxCapacity, false))
            }
        }
        if (current < boxCount) { // Load Current Box
            boxLists[current].addAll(format.unpack(data, off.currentBox, boxCapacity, false))
        }
        partyList.addAll(format.unpack(data, off.party, PARTY_CAPACITY, true))
    }

    private fun getBoxRawDataOffset(box: Int): Int =
        if (box < boxCount / 2) 0x4000 + box * boxListLength else 0x6000 + (box - boxCount / 2) * boxListLength

    // ---- boxes -----------------------------------------------------------------------------------------------------
    override val currentBox: Int get() = data.u8(off.currentBoxIndex) and 0x7F

    /** Bit 7 of the box index: the box banks have been written at least once. */
    private var boxesInitialized: Boolean
        get() = (data.u8(off.currentBoxIndex) and 0x80) != 0
        set(value) = data.setU8(off.currentBoxIndex, (data.u8(off.currentBoxIndex) and 0x7F) or (if (value) 0x80 else 0))

    override fun boxName(box: Int): String = if (japanese) "ボックス${box + 1}" else "Box ${box + 1}"

    // ---- checksums -------------------------------------------------------------------------------------------------
    private fun mainChecksum(): Int = GbChecksum.gen1(data, off.ot, off.checksumOfs)

    override val checksumsValid: Boolean get() = data.u8(off.checksumOfs) == mainChecksum()

    /**
     * Whether the checksums of the two box banks match (all-boxes byte followed by one byte per box).
     * PKHeX does not check or write these; see [updateBankChecksums].
     */
    internal fun bankChecksumsValid(): Boolean = (0..1).all { bank ->
        val start = bankStart(bank)
        val ofs = start + (boxCount / 2) * boxListLength
        data.u8(ofs) == GbChecksum.gen1(data, start, ofs) &&
            (0 until boxCount / 2).all { data.u8(ofs + 1 + it) == GbChecksum.gen1(data, start + it * boxListLength, start + (it + 1) * boxListLength) }
    }

    private fun bankStart(bank: Int): Int = if (bank == 0) 0x4000 else 0x6000

    /**
     * Deviation (addition): PKHeX only writes the main checksum. The cartridge also keeps, after each box bank,
     * a checksum over all its boxes followed by one per box, so they are refreshed for banks whose boxes changed.
     */
    private fun updateBankChecksums(banks: Set<Int>) {
        for (bank in banks) {
            val start = bankStart(bank)
            val ofs = start + (boxCount / 2) * boxListLength
            data.setU8(ofs, GbChecksum.gen1(data, start, ofs))
            for (i in 0 until boxCount / 2) {
                data.setU8(ofs + 1 + i, GbChecksum.gen1(data, start + i * boxListLength, start + (i + 1) * boxListLength))
            }
        }
    }

    // ---- export ----------------------------------------------------------------------------------------------------
    /** Port of `SAV1.GetFinalData`, restricted to the lists that were edited. */
    override fun finalizeData() {
        if (dirtyBoxes.any { it }) {
            var boxInitialized = boxesInitialized
            var current = currentBox
            var flipped = false
            if (!boxInitialized) {
                // Check if any box has content in it.
                val newContent = (0 until boxCount).any { it != current && boxLists[it].any { s -> PokeListFormat.isPresent(s.marker) } }
                if (newContent) {
                    boxesInitialized = true
                    boxInitialized = true
                    flipped = true
                } else {
                    current = 0 // No content, reset to box 1.
                    data.setU8(off.currentBoxIndex, (data.u8(off.currentBoxIndex) and 0x80) or 0)
                }
            }

            val touchedBanks = HashSet<Int>()
            for (i in 0 until boxCount) {
                // Once the banks become "initialised" they are trusted, so every box must be valid, not just the edited ones.
                if (!dirtyBoxes[i] && !flipped) continue
                val ofs = getBoxRawDataOffset(i)
                val written = format.mergeSingles(boxLists[i], data, ofs, boxCapacity, false, boxInitialized)
                if (written) touchedBanks.add(if (i < boxCount / 2) 0 else 1)
                if (i != current) continue

                // Ensure the current box is mirrored in the box buffer; easier than having dest be CurrentBox.
                // On the rare chance that the box is empty and was de-synchronized from the current box as empty,
                // we need to write separately.
                if (written) data.copyInto(data, off.currentBox, ofs, ofs + boxListLength)
                else format.mergeSingles(boxLists[i], data, off.currentBox, boxCapacity, false, boxInitialized)
            }
            updateBankChecksums(touchedBanks)
        }

        if (dirtyParty) format.mergeSingles(partyList, data, off.party, PARTY_CAPACITY, true)

        // SetChecksums
        data.setU8(off.checksumOfs, mainChecksum())
    }

    // ---- trainer ---------------------------------------------------------------------------------------------------
    private fun readName(offset: Int): String =
        StringConverter1.getString(data, offset, trainerNameLength, japanese)

    override fun readTrainer(): Trainer = Trainer(
        name = readName(off.ot),
        id = data.u16be(off.tid16),
        money = Bcd.readBigEndian(data, off.money, 3).toInt(),
        badges = data.u8(off.badges),
        playTime = PlayTime(data.u8(off.playTime), data.u8(off.playTime + 2), data.u8(off.playTime + 3)),
        rivalName = readName(off.rival),
        gender = null,
        coins = Bcd.readBigEndian(data, off.coin, 2).toInt(),
    )

    override fun writeTrainer(trainer: Trainer) {
        val old = readTrainer()
        if (trainer.name != old.name) {
            StringConverter1.setString(
                data, off.ot, trainerNameLength + 1, trainer.name, trainerNameLength, japanese, StringOption.CLEAR_ZERO,
            )
        }
        if (trainer.id != old.id) data.setU16be(off.tid16, trainer.id and 0xFFFF)
        if (trainer.money != old.money) Bcd.writeBigEndian(data, off.money, 3, trainer.money.coerceIn(0, MAX_MONEY).toLong())
        if (trainer.badges != old.badges) data.setU8(off.badges, trainer.badges)
        if (trainer.playTime != old.playTime) writePlayTime(trainer.playTime)
        if (trainer.rivalName != old.rivalName) {
            StringConverter1.setString(
                data, off.rival, trainerNameLength, trainer.rivalName.orEmpty(), trainerNameLength, japanese, StringOption.CLEAR_50,
            )
        }
        if (trainer.coins != old.coins) Bcd.writeBigEndian(data, off.coin, 2, trainer.coins.coerceIn(0, MAX_COINS).toLong())
    }

    /** Port of the `PlayedHours/Minutes/Seconds` setters: 255+ hours sets the "maxed" flag and zeroes the rest. */
    private fun writePlayTime(time: PlayTime) {
        var hours = time.hours
        if (hours >= 255) { // Set 255:00:00.00 and flag
            data.setU8(off.playTime + 1, 1) // PlayedMaximum
            hours = 255
            data.setU8(off.playTime + 2, 0)
            data.setU8(off.playTime + 3, 0)
            data.setU8(off.playTime + 4, 0)
            data.setU8(off.playTime, hours)
            return
        }
        data.setU8(off.playTime, hours)
        data.setU8(off.playTime + 2, time.minutes)
        data.setU8(off.playTime + 3, time.seconds)
    }

    // ---- pokedex ---------------------------------------------------------------------------------------------------
    override fun dexOffsets(): Pair<Int, Int> = off.dexSeen to off.dexCaught
    override fun canSetDex(pk: GbPokemon): Boolean = pk.species in 1..MAX_SPECIES

    private companion object {
        const val MAX_SPECIES = 151
        const val MAX_MONEY = 999999
        const val MAX_COINS = 9999
    }
}

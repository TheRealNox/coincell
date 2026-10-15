// Ported from PKHeX.Core/Saves/SAV2.cs and Saves/Substructures/Gen12/SAV2Offsets.cs (PKHeX, GPL-3.0, commit 641c26b).
// Not ported: Korean layout (detected, rejected), RTC/Mobile Adapter/GS Ball helpers, event flags/work,
// daycare editing, Unown unlock flags, mystery gift, options, box renaming.
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.PlayTime
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.Region
import org.noxeng.coincell.pkcore.Trainer
import org.noxeng.coincell.pkcore.TrainerGender
import org.noxeng.coincell.pkcore.text.StringConverter2
import org.noxeng.coincell.pkcore.text.StringOption

/** Offsets of the Generation 2 save layouts (Gold/Silver and Crystal, international and Japanese). */
internal class Sav2Offsets(game: Game, region: Region) {
    val options = 0x2000
    val trainer1 = 0x2009

    var rival = -1; private set
    var timePlayed = -1; private set
    var palette = -1; private set
    var money = -1; private set
    var johtoBadges = -1; private set
    var currentBoxIndex = -1; private set
    var boxNames = -1; private set
    var party = -1; private set
    var pokedexCaught = -1; private set
    var pokedexSeen = -1; private set
    var currentBox = -1; private set
    var gender = -1; private set
    var accumulatedChecksumEnd = -1; private set
    var overallChecksumPosition = -1; private set
    var overallChecksumPosition2 = -1; private set
    var pouchTmHm = -1; private set
    var pouchItem = -1; private set
    var pouchKey = -1; private set
    var pouchBall = -1; private set
    var pouchPc = -1; private set

    init {
        val crystal = game == Game.CRYSTAL
        if (region == Region.JAPANESE) loadJapanese(crystal) else loadInternational(crystal)
    }

    private fun loadInternational(crystal: Boolean) {
        rival = 0x2021
        if (!crystal) {
            timePlayed = 0x2053; palette = 0x206B; money = 0x23DB; johtoBadges = 0x23E4
            currentBoxIndex = 0x2724; boxNames = 0x2727; party = 0x288A
            pokedexCaught = 0x2A4C; pokedexSeen = 0x2A6C; currentBox = 0x2D6C
            gender = -1 // No gender in GS
            accumulatedChecksumEnd = 0x2D68; overallChecksumPosition = 0x2D69; overallChecksumPosition2 = 0x7E6D
            pouchTmHm = 0x23E6; pouchItem = 0x241F; pouchKey = 0x2449; pouchBall = 0x2464; pouchPc = 0x247E
        } else {
            timePlayed = 0x2052; palette = 0x206A; money = 0x23DC; johtoBadges = 0x23E5
            currentBoxIndex = 0x2700; boxNames = 0x2703; party = 0x2865
            pokedexCaught = 0x2A27; pokedexSeen = 0x2A47; currentBox = 0x2D10; gender = 0x3E3D
            accumulatedChecksumEnd = 0x2B82; overallChecksumPosition = 0x2D0D; overallChecksumPosition2 = 0x1F0D
            pouchTmHm = 0x23E7; pouchItem = 0x2420; pouchKey = 0x244A; pouchBall = 0x2465; pouchPc = 0x247F
        }
    }

    private fun loadJapanese(crystal: Boolean) {
        rival = 0x2017; timePlayed = 0x2034; palette = 0x204C; currentBox = 0x2D10
        if (!crystal) {
            money = 0x23BC; johtoBadges = 0x23C5; currentBoxIndex = 0x2705; boxNames = 0x2708; party = 0x283E
            pokedexCaught = 0x29CE; pokedexSeen = 0x29EE
            gender = -1 // No gender in GS
            accumulatedChecksumEnd = 0x2C8B; overallChecksumPosition = 0x2D0D; overallChecksumPosition2 = 0x7F0D
            pouchTmHm = 0x23C7; pouchItem = 0x2400; pouchKey = 0x242A; pouchBall = 0x2445; pouchPc = 0x245F
        } else {
            money = 0x23BE; johtoBadges = 0x23C7; currentBoxIndex = 0x26E2; boxNames = 0x26E5; party = 0x281A
            pokedexCaught = 0x29AA; pokedexSeen = 0x29CA; gender = 0x8000
            accumulatedChecksumEnd = 0x2AE2; overallChecksumPosition = 0x2D0D; overallChecksumPosition2 = 0x7F0D
            pouchTmHm = 0x23C9; pouchItem = 0x2402; pouchKey = 0x242C; pouchBall = 0x2447; pouchPc = 0x2461
        }
    }

    /** Right after the first Unown seen (`PokedexSeen + 0x1F + 28 + 1`). */
    val daycare: Int get() = pokedexSeen + 0x1F + 28 + 1
}

internal class Sav2(bytes: ByteArray, info: SaveTypeInfo, requestedLanguage: Int?) :
    GbSave(
        bytes, info,
        boxCount = if (info.region == Region.JAPANESE) 9 else 14,
        boxCapacity = if (info.region == Region.JAPANESE) 30 else 20,
    ) {
    private val crystal: Boolean = game == Game.CRYSTAL
    private val off = Sav2Offsets(game, region)
    private val trainerNameLength: Int = if (japanese) 5 else 7

    /** `SIZE_BOX_LIST`: the length of one box list (boxes are stored with 2 padding bytes after it). */
    private val boxListLength: Int = format.listLength(boxCapacity, party = false)

    override val gbInventory: GbInventory = GbInventory(
        2,
        listOf(
            PouchDef(PocketType.TM_HM, off.pouchTmHm, 57),
            PouchDef(PocketType.ITEMS, off.pouchItem, 20),
            PouchDef(PocketType.KEY_ITEMS, off.pouchKey, 26),
            PouchDef(PocketType.BALLS, off.pouchBall, 12),
            PouchDef(PocketType.PC_ITEMS, off.pouchPc, 50),
        ),
        crystal,
    )

    init {
        initialize()
        resolveLanguage(requestedLanguage)
    }

    private val splitAtIndex: Int get() = if (japanese) 6 else 7

    /** Port of `SAV2.Initialize` (list loading part). */
    private fun initialize() {
        for (i in 0 until boxCount) {
            boxLists[i].addAll(format.unpack(data, getBoxRawDataOffset(i), boxCapacity, false))
        }
        // Don't treat the CurrentBox segment as valid; Stadium ignores it and will de-synchronize it.
        // The main box data segment is the truth, the CurrentBox copy is not always up-to-date.
        // When exporting an updated save file in this program, be nice and re-synchronize.
        partyList.addAll(format.unpack(data, off.party, PARTY_CAPACITY, true))
    }

    private fun getBoxRawDataOffset(i: Int): Int =
        if (i < splitAtIndex) 0x4000 + i * (boxListLength + 2) else 0x6000 + (i - splitAtIndex) * (boxListLength + 2)

    // ---- boxes -----------------------------------------------------------------------------------------------------
    override val currentBox: Int get() = data.u8(off.currentBoxIndex)

    override fun boxName(box: Int): String {
        val raw = StringConverter2.getString(data, off.boxNames + box * BOX_NAME_LENGTH, BOX_NAME_LENGTH, language)
        return StringConverter2.inflateLigatures(raw, language)
    }

    // ---- checksums -------------------------------------------------------------------------------------------------
    private fun checksum(): Int = GbChecksum.gen2(data, off.trainer1, off.accumulatedChecksumEnd)

    override val checksumsValid: Boolean
        get() {
            val sum = checksum()
            return data.u16le(off.overallChecksumPosition) == sum && data.u16le(off.overallChecksumPosition2) == sum
        }

    // ---- export ----------------------------------------------------------------------------------------------------
    /** Port of `SAV2.GetFinalData`, restricted to the lists that were edited. */
    override fun finalizeData() {
        val current = currentBox
        for (i in 0 until boxCount) {
            if (!dirtyBoxes[i]) continue
            val ofs = getBoxRawDataOffset(i)
            val written = format.mergeSingles(boxLists[i], data, ofs, boxCapacity, false)
            if (written && i == current) data.copyInto(data, off.currentBox, ofs, ofs + boxListLength)
        }
        if (dirtyParty) format.mergeSingles(partyList, data, off.party, PARTY_CAPACITY, true)

        // SetChecksums
        val sum = checksum()
        data.setU16le(off.overallChecksumPosition, sum)
        data.setU16le(off.overallChecksumPosition2, sum)

        // The game keeps backup copies of the main block; refresh them from the primary.
        if (japanese) {
            val length = if (crystal) 0xADA else 0xC83
            data.copyInto(data, 0x7209, off.trainer1, off.trainer1 + length)
        } else if (crystal) {
            data.copyInto(data, 0x1209, 0x2009, 0x2009 + 0xB7A)
        } else {
            data.copyInto(data, 0x15C7, 0x2009, 0x222F)
            data.copyInto(data, 0x3D69, 0x222F, 0x23D9)
            data.copyInto(data, 0x0C6B, 0x23D9, 0x2856)
            data.copyInto(data, 0x7E39, 0x2856, 0x288A)
            data.copyInto(data, 0x10E8, 0x288A, 0x2D69)
        }
    }

    // ---- trainer ---------------------------------------------------------------------------------------------------
    private fun readName(offset: Int): String = StringConverter2.getString(data, offset, trainerNameLength, language)

    private fun hasGender(): Boolean = crystal && off.gender in 0 until data.size

    override fun readTrainer(): Trainer = Trainer(
        name = readName(off.trainer1 + 2),
        id = data.u16be(off.trainer1),
        money = data.u24be(off.money),
        badges = data.u16le(off.johtoBadges),
        playTime = PlayTime(data.u16be(off.timePlayed), data.u8(off.timePlayed + 2), data.u8(off.timePlayed + 3)),
        rivalName = readName(off.rival),
        gender = if (hasGender()) (if (data.u8(off.gender) == 0) TrainerGender.BOY else TrainerGender.GIRL) else null,
        coins = data.u16be(off.money + 7),
    )

    override fun writeTrainer(trainer: Trainer) {
        val old = readTrainer()
        if (trainer.name != old.name) {
            StringConverter2.setString(
                data, off.trainer1 + 2, trainerNameLength, trainer.name, trainerNameLength, language, StringOption.CLEAR_50,
            )
        }
        if (trainer.id != old.id) data.setU16be(off.trainer1, trainer.id and 0xFFFF)
        if (trainer.money != old.money) {
            val clamp = trainer.money.coerceIn(0, MAX_MONEY)
            val toWrite = (clamp shl 8) or data.u8(off.money + 3)
            for (i in 0 until 4) data[off.money + i] = (toWrite shr (24 - 8 * i)).toByte()
        }
        if (trainer.badges != old.badges) data.setU16le(off.johtoBadges, trainer.badges and 0xFFFF)
        if (trainer.playTime != old.playTime) {
            data.setU16be(off.timePlayed, trainer.playTime.hours and 0xFFFF)
            data.setU8(off.timePlayed + 2, trainer.playTime.minutes)
            data.setU8(off.timePlayed + 3, trainer.playTime.seconds)
        }
        if (trainer.rivalName != old.rivalName) {
            StringConverter2.setString(
                data, off.rival, trainerNameLength, trainer.rivalName, trainerNameLength, language, StringOption.CLEAR_50,
            )
        }
        if (trainer.coins != old.coins) data.setU16be(off.money + 7, trainer.coins.coerceIn(0, MAX_COINS))
        if (trainer.gender != old.gender && trainer.gender != null && hasGender()) {
            val value = if (trainer.gender == TrainerGender.BOY) 0 else 1
            data.setU8(off.gender, value)
            data.setU8(off.palette, value)
        }
    }

    // ---- pokedex ---------------------------------------------------------------------------------------------------
    override fun dexOffsets(): Pair<Int, Int> = off.pokedexSeen to off.pokedexCaught
    override fun canSetDex(pk: GbPokemon): Boolean = pk.species in 1..MAX_SPECIES && !pk.isEgg

    /** `SAV2.SetUnownFormFlags`: give all Unown letters to prevent a crash on Pokedex view. */
    override fun afterCaught(species: Int) {
        if (species != UNOWN) return
        for (i in 1..26) data.setU8(off.pokedexSeen + 0x1F + i, i)
        if (data.u8(off.pokedexSeen + 0x1F + 28) == 0) data.setU8(off.pokedexSeen + 0x1F + 28, 1) // Unown first seen: A
    }

    private companion object {
        const val MAX_SPECIES = 251
        const val UNOWN = 201
        const val MAX_MONEY = 999999
        const val MAX_COINS = 9999
        const val BOX_NAME_LENGTH = 9
    }
}

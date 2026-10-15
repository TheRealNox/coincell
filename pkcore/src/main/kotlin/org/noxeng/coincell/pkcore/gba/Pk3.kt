// Ported from PKHeX.Core/PKM/PK3.cs, PKM/Shared/G3PKM.cs, PKM/Util/PokeCrypto.cs (Decrypt3/Encrypt3/Shuffle3,
// IsEncrypted3), PKM/Util/EntityDetection.cs (IsPresentGBA), Saves/Util/Checksums.cs (Add16) and the stat formula of
// PKM/PKM.cs + Editing/NatureAmp.cs (PKHeX, GPL-3.0, commit 641c26b).
// Not ported: conversion to PK4/CK3/XK3, ribbons as individual properties (only the raw words and the count).
package org.noxeng.coincell.pkcore.gba

import org.noxeng.coincell.pkcore.ContestStats
import org.noxeng.coincell.pkcore.EditablePokemon
import org.noxeng.coincell.pkcore.Gen3Details
import org.noxeng.coincell.pkcore.Gender
import org.noxeng.coincell.pkcore.MoveSlot
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.pkcore.StatBlock
import org.noxeng.coincell.pkcore.data.Experience
import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.data.Gen3Util
import org.noxeng.coincell.pkcore.data.MoveInfo
import org.noxeng.coincell.pkcore.data.PersonalInfo
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.pkcore.data.SpeciesConverter
import org.noxeng.coincell.pkcore.data.SpeciesName
import org.noxeng.coincell.pkcore.gb.setU16le
import org.noxeng.coincell.pkcore.gb.u16le
import org.noxeng.coincell.pkcore.text.LanguageId
import org.noxeng.coincell.pkcore.text.StringConverter3
import org.noxeng.coincell.pkcore.text.StringOption

/**
 * Generation 3 Pokemon, kept decrypted and unshuffled in party format (100 bytes: the 80 stored bytes followed by the
 * battle stats). Save files encrypt it again when it is written back.
 */
public class Pk3 internal constructor(internal val data: ByteArray) : EditablePokemon {
    /** A blank Pokemon (species 0). */
    public constructor() : this(ByteArray(PARTY_SIZE))

    init {
        require(data.size == PARTY_SIZE) { "A PK3 is $PARTY_SIZE bytes" }
    }

    /** False when the stored checksum did not match the data as it was read (the game shows a Bad Egg). */
    public var checksumValidWhenRead: Boolean = true
        internal set

    /**
     * Language used to decode the OT name of an egg. Gen 3 eggs carry the Japanese language byte until they hatch,
     * while their OT name is written in the game's own alphabet (deviation: PKHeX decodes it with the Japanese table).
     */
    public var fallbackLanguage: Int = LanguageId.ENGLISH

    override val generation: Int get() = 3

    private fun u8(ofs: Int): Int = data[ofs].toInt() and 0xFF
    private fun setU8(ofs: Int, value: Int) {
        data[ofs] = value.toByte()
    }

    private fun u32(ofs: Int): Long = data.u16le(ofs).toLong() or (data.u16le(ofs + 2).toLong() shl 16)
    private fun setU32(ofs: Int, value: Long) {
        data.setU16le(ofs, (value and 0xFFFF).toInt())
        data.setU16le(ofs + 2, ((value ushr 16) and 0xFFFF).toInt())
    }

    override fun duplicate(): Pk3 = Pk3(data.copyOf()).also {
        it.checksumValidWhenRead = checksumValidWhenRead
        it.fallbackLanguage = fallbackLanguage
    }

    internal val personalInfo: PersonalInfo get() = PersonalTable.RS[species]

    // ---- 0x20 header ---------------------------------------------------------------------------------------------------
    /** Personality value (unsigned 32 bit). */
    public var pid: Long
        get() = u32(0x00)
        set(value) = setU32(0x00, value and 0xFFFFFFFFL)

    override var trainerId: Int
        get() = data.u16le(0x04)
        set(value) = data.setU16le(0x04, value)

    public var secretId: Int
        get() = data.u16le(0x06)
        set(value) = data.setU16le(0x06, value)

    /** The language byte (PKHeX `LanguageID`). */
    public var language: Int
        get() = u8(0x12)
        set(value) = setU8(0x12, value)

    private var flags: Int
        get() = u8(0x13)
        set(value) = setU8(0x13, value)

    public var flagIsBadEgg: Boolean
        get() = flags and 1 != 0
        set(value) {
            flags = (flags and 1.inv()) or (if (value) 1 else 0)
        }
    public var flagHasSpecies: Boolean
        get() = flags and 2 != 0
        set(value) {
            flags = (flags and 2.inv()) or (if (value) 2 else 0)
        }
    public var flagIsEgg: Boolean
        get() = flags and 4 != 0
        set(value) {
            flags = (flags and 4.inv()) or (if (value) 4 else 0)
        }

    /** Markings (circle, triangle, square, heart), bits 1 and 2 swapped as PKHeX does. */
    public var markings: Int
        get() = swapBits(u8(0x1B), 1, 2)
        set(value) = setU8(0x1B, swapBits(value, 1, 2))

    public var checksum: Int
        get() = data.u16le(0x1C)
        set(value) = data.setU16le(0x1C, value)

    // ---- block A -------------------------------------------------------------------------------------------------------
    /** Raw Generation 3 species index. */
    public var speciesInternal: Int
        get() = data.u16le(0x20)
        set(value) = data.setU16le(0x20, value)

    /** National Dex number. */
    override var species: Int
        get() = SpeciesConverter.getNational3(speciesInternal)
        set(value) {
            val s3 = SpeciesConverter.getInternal3(value)
            speciesInternal = s3
            flagHasSpecies = s3 != 0
        }

    public var heldItem: Int
        get() = data.u16le(0x22)
        set(value) = data.setU16le(0x22, value)

    override var exp: Int
        get() = u32(0x24).toInt()
        set(value) = setU32(0x24, value.toLong())

    private var ppUps: Int
        get() = u8(0x28)
        set(value) = setU8(0x28, value)

    public var friendship: Int
        get() = u8(0x29)
        set(value) = setU8(0x29, value)

    // ---- block B: moves ------------------------------------------------------------------------------------------------
    override fun getMove(index: Int): Int = data.u16le(0x2C + index * 2)
    override fun setMove(index: Int, move: Int): Unit = data.setU16le(0x2C + index * 2, move)
    override fun getMovePp(index: Int): Int = u8(0x34 + index)
    override fun setMovePp(index: Int, pp: Int): Unit = setU8(0x34 + index, pp)
    override fun getMovePpUps(index: Int): Int = (ppUps shr (index * 2)) and 3
    override fun setMovePpUps(index: Int, ups: Int) {
        val shift = index * 2
        ppUps = (ppUps and (3 shl shift).inv()) or ((ups and 3) shl shift)
    }

    /** PKHeX `PKM.GetMovePP`: base PP * (5 + PP Ups) / 5. */
    override fun getMaxPp(move: Int, ppUps: Int): Int = MoveInfo.getBasePp(3, move) * (5 + ppUps) / 5

    override fun healPp() {
        for (i in 0 until 4) setMovePp(i, getMaxPp(getMove(i), getMovePpUps(i)))
    }

    // ---- block C: EVs and contest --------------------------------------------------------------------------------------
    /** EV by stat index 0 HP, 1 Atk, 2 Def, 3 Spe, 4 SpA, 5 SpD (stored in that order). */
    public fun getEv(stat: Int): Int = u8(0x38 + stat)
    public fun setEv(stat: Int, value: Int): Unit = setU8(0x38 + stat, value)

    public val contest: ContestStats
        get() = ContestStats(u8(0x3E), u8(0x3F), u8(0x40), u8(0x41), u8(0x42), u8(0x43))

    // ---- block D -------------------------------------------------------------------------------------------------------
    public var pokerusState: Int
        get() = u8(0x44)
        set(value) = setU8(0x44, value)
    public var pokerusDays: Int
        get() = pokerusState and 0xF
        set(value) {
            pokerusState = (pokerusState and 0xF.inv()) or (value and 0xF)
        }
    public var pokerusStrain: Int
        get() = pokerusState shr 4
        set(value) {
            pokerusState = (pokerusState and 0xF) or ((value and 0xF) shl 4)
        }

    public var metLocation: Int
        get() = u8(0x45)
        set(value) = setU8(0x45, value)

    private var origins: Int
        get() = data.u16le(0x46)
        set(value) = data.setU16le(0x46, value)

    public var metLevel: Int
        get() = origins and 0x7F
        set(value) {
            origins = (origins and 0x7F.inv()) or (value and 0x7F)
        }

    /** Game of origin (PKHeX `GameVersion`: 1 S, 2 R, 3 E, 4 FR, 5 LG, 15 CXD). */
    public var originGame: Int
        get() = (origins shr 7) and 0xF
        set(value) {
            origins = (origins and 0x780.inv()) or ((value and 0xF) shl 7)
        }
    public var ball: Int
        get() = (origins shr 11) and 0xF
        set(value) {
            origins = (origins and 0x7800.inv()) or ((value and 0xF) shl 11)
        }
    public var originalTrainerGender: Int
        get() = (origins shr 15) and 1
        set(value) {
            origins = (origins and (1 shl 15).inv()) or ((value and 1) shl 15)
        }

    private var iv32: Long
        get() = u32(0x48)
        set(value) = setU32(0x48, value)

    /** IV by stat index 0 HP, 1 Atk, 2 Def, 3 Spe, 4 SpA, 5 SpD (5 bits each, in that order). */
    public fun getIv(stat: Int): Int = ((iv32 shr (stat * 5)) and 0x1F).toInt()
    public fun setIv(stat: Int, value: Int) {
        val shift = stat * 5
        val v = if (value > 31) 31L else value.toLong()
        iv32 = (iv32 and (0x1FL shl shift).inv()) or (v shl shift)
    }

    override var isEgg: Boolean
        get() = (iv32 shr 30) and 1 == 1L
        set(value) {
            iv32 = (iv32 and 0x40000000L.inv()) or (if (value) 0x40000000L else 0)
            flagIsEgg = value
            if (value) {
                language = LanguageId.JAPANESE
                writeNickname(SpeciesName.EGG_NAME_GEN3)
            }
        }

    /** Second ability slot (bit 31 of the IV word). */
    public var abilityBit: Boolean
        get() = (iv32 shr 31) == 1L
        set(value) {
            iv32 = (iv32 and 0x7FFFFFFFL) or (if (value) 1L shl 31 else 0)
        }

    private val ribbons: Long get() = u32(0x4C)
    public val fatefulEncounter: Boolean get() = (ribbons ushr 31) == 1L
    public val ribbonCount: Int get() = java.lang.Long.bitCount(ribbons and 0x07FFFFFFL)

    // ---- party data ----------------------------------------------------------------------------------------------------
    override var status: Int
        get() = u32(0x50).toInt()
        set(value) = setU32(0x50, value.toLong() and 0xFFFFFFFFL)

    override var storedLevel: Int
        get() = u8(0x54)
        set(value) = setU8(0x54, value)

    /** Mail slot of the held mail, 0xFF (-1) for none in the party. */
    public var heldMailId: Int
        get() = data[0x55].toInt()
        set(value) {
            data[0x55] = value.toByte()
        }

    override var currentHp: Int
        get() = data.u16le(0x56)
        set(value) = data.setU16le(0x56, value)

    override var maxHp: Int
        get() = data.u16le(0x58)
        set(value) = data.setU16le(0x58, value)

    /** Stored party stat: 0 HP (max), 1 Atk, 2 Def, 3 Spe, 4 SpA, 5 SpD. */
    public fun getStoredStat(index: Int): Int = data.u16le(0x58 + index * 2)
    private fun setStoredStat(index: Int, value: Int) = data.setU16le(0x58 + index * 2, value)

    override val partyStatsPresent: Boolean get() = maxHp != 0

    // ---- derived -------------------------------------------------------------------------------------------------------
    override val expGrowth: Int get() = personalInfo.expGrowth

    override var level: Int
        get() = Experience.getLevel(u32(0x24), expGrowth)
        set(value) {
            storedLevel = value
            exp = Experience.getExp(value, expGrowth).toInt()
        }

    public val nature: Int get() = Gen3Util.nature(pid)
    public val ability: Int get() = personalInfo.getAbility(abilityBit)
    public val hasSecondAbility: Boolean get() = personalInfo.hasSecondAbility
    public val gender: Gender get() = Gen3Util.gender(pid, personalInfo.gender)
    public val isShiny: Boolean get() = Gen3Util.isShiny(pid, trainerId, secretId)

    /** Unown's letter (0..27) from the PID, 0 for other species. */
    public val form: Int get() = if (species == UNOWN) Gen3Util.unownForm(pid) else 0

    public val hiddenPowerType: Int get() = Gen3Util.hiddenPowerType(IntArray(6) { getIv(it) })

    /** Battle stats from base stats, IVs, EVs, level and nature (PKHeX `PKM.LoadStats` + `ModifyStatsForAlignment`). */
    public fun calculateStats(): StatBlock {
        val p = personalInfo
        val lv = level
        val stats = IntArray(6)
        stats[0] = if (p.hp == 1) 1 else ((getIv(0) + (2 * p.hp) + (getEv(0) / 4) + 100) * lv / 100) + 10
        stats[1] = ((getIv(1) + (2 * p.atk) + (getEv(1) / 4)) * lv / 100) + 5
        stats[2] = ((getIv(2) + (2 * p.def) + (getEv(2) / 4)) * lv / 100) + 5
        stats[4] = ((getIv(4) + (2 * p.spa) + (getEv(4) / 4)) * lv / 100) + 5
        stats[5] = ((getIv(5) + (2 * p.spd) + (getEv(5) / 4)) * lv / 100) + 5
        stats[3] = ((getIv(3) + (2 * p.spe) + (getEv(3) / 4)) * lv / 100) + 5
        Gen3Util.natureModification(nature)?.let { (up, down) ->
            stats[up] = stats[up] * 11 / 10
            stats[down] = stats[down] * 9 / 10
        }
        return StatBlock(stats[0], stats[1], stats[2], stats[3], stats[4], stats[5])
    }

    override fun resetPartyStats() {
        val s = calculateStats()
        maxHp = s.hp
        currentHp = s.hp
        setStoredStat(1, s.attack)
        setStoredStat(2, s.defense)
        setStoredStat(3, s.speed)
        setStoredStat(4, s.specialAttack)
        setStoredStat(5, s.specialDefense)
        storedLevel = level
        status = 0
    }

    override fun heal() {
        resetPartyStats()
        healPp()
    }

    // ---- text ----------------------------------------------------------------------------------------------------------
    override val isJapanese: Boolean get() = language == LanguageId.JAPANESE
    override val maxNicknameLength: Int get() = if (isJapanese) 5 else 10
    override val maxTrainerNameLength: Int get() = if (otLanguage == LanguageId.JAPANESE) 5 else 7

    /** Language the OT name is written in: the Pokemon's, or for an egg the save's (see [fallbackLanguage]). */
    public val otLanguage: Int get() = if (isEgg) fallbackLanguage else language

    override var nickname: String
        get() = StringConverter3.getString(data, NICK, NICK_LENGTH, language)
        set(value) = writeNickname(if (isEgg) SpeciesName.EGG_NAME_GEN3 else value)

    private fun writeNickname(value: String) {
        StringConverter3.setString(data, NICK, NICK_LENGTH, value, NICK_LENGTH, language, StringOption.NONE)
    }

    override var otName: String
        get() = StringConverter3.getString(data, OT, OT_LENGTH, otLanguage)
        set(value) {
            StringConverter3.setString(data, OT, OT_LENGTH, value, OT_LENGTH, otLanguage, StringOption.NONE)
        }

    /**
     * True if the nickname differs from the species name in the Pokemon's language (PKHeX `SpeciesName.IsNicknamed`
     * for generation 3). Deviation: eggs report false (PKHeX compares "タマゴ" with the species name).
     */
    override val isNicknamed: Boolean
        get() = !isEgg && nickname != SpeciesName.getGeneration3(species, language)

    /** The species name in the Pokemon's language, written over a terminator-filled buffer (PKHeX `PrepareNickname`). */
    override fun resetNickname() {
        val prefill = if (isJapanese) 6 else NICK_LENGTH
        data.fill(StringConverter3.TERMINATOR_BYTE.toByte(), NICK, NICK + prefill)
        writeNickname(if (isEgg) SpeciesName.EGG_NAME_GEN3 else SpeciesName.getGeneration3(species, language))
    }

    // ---- checksum and encryption ---------------------------------------------------------------------------------------
    internal fun calculateChecksum(): Int = add16(data, HEADER_SIZE, STORED_SIZE)

    /** PKHeX `PK3.RefreshChecksum`: clears the Bad Egg flag and stores the checksum. */
    public fun refreshChecksum() {
        flagIsBadEgg = false
        checksum = calculateChecksum()
    }

    /** The 80 stored bytes, checksummed and encrypted as a box slot holds them. */
    internal fun encryptedStored(): ByteArray {
        refreshChecksum()
        val out = data.copyOf(STORED_SIZE)
        encrypt(out)
        return out
    }

    /** The 100 party bytes: the encrypted 80 stored bytes followed by the plain battle stats. */
    internal fun encryptedParty(): ByteArray {
        val out = data.copyOf()
        encryptedStored().copyInto(out)
        return out
    }

    override fun summary(strings: GameStrings): PokemonSummary {
        val moves = List(4) {
            val id = getMove(it)
            MoveSlot(id, strings.moveName(id), getMovePp(it), getMovePpUps(it), getMaxPp(id, getMovePpUps(it)))
        }
        val held = heldItem
        val ivs = StatBlock.of(List(6) { getIv(it) })
        val evs = StatBlock.of(List(6) { getEv(it) })
        val sp = species
        return PokemonSummary(
            generation = 3,
            species = sp,
            speciesName = strings.speciesName(sp),
            nickname = nickname,
            isNicknamed = isNicknamed,
            isEgg = isEgg,
            level = level,
            exp = exp,
            storedLevel = storedLevel,
            otName = otName,
            trainerId = trainerId,
            moves = moves,
            dvs = null,
            statExp = null,
            stats = calculateStats(),
            storedStats = StatBlock.of(List(6) { getStoredStat(it) }),
            currentHp = currentHp,
            status = status,
            heldItem = held,
            heldItemName = if (held == 0) "" else strings.itemName(3, held),
            friendship = friendship,
            pokerusStrain = pokerusStrain,
            pokerusDays = pokerusDays,
            caughtData = null,
            isShiny = isShiny,
            gender = gender,
            hiddenPowerType = hiddenPowerType,
            form = form,
            gen3 = Gen3Details(
                pid = pid,
                secretId = secretId,
                ivs = ivs,
                evs = evs,
                nature = nature,
                natureName = strings.natureName(nature),
                abilitySlot = if (abilityBit && hasSecondAbility) 1 else 0,
                ability = ability,
                abilityName = strings.abilityName(ability),
                hasSecondAbility = hasSecondAbility,
                ball = ball,
                metLocation = metLocation,
                metLocationName = strings.locationName3(metLocation),
                metLevel = metLevel,
                originGame = originGame,
                otGender = originalTrainerGender,
                language = language,
                markings = markings,
                ribbonCount = ribbonCount,
                fatefulEncounter = fatefulEncounter,
                contest = contest,
                isBadEgg = flagIsBadEgg || !checksumValidWhenRead,
            ),
        )
    }

    /** Byte-for-byte equality of the decrypted data. */
    public fun contentEquals(other: Pk3): Boolean = data.contentEquals(other.data)

    public companion object {
        public const val PARTY_SIZE: Int = 100
        public const val STORED_SIZE: Int = 80
        private const val HEADER_SIZE = 32
        private const val BLOCK_SIZE = 12
        private const val NICK = 0x08
        private const val NICK_LENGTH = 10
        private const val OT = 0x14
        private const val OT_LENGTH = 7
        private const val UNOWN = 201

        /** PKHeX `EntityDetection.IsPresentGBA`: the has-species flag is set (the egg flag ignored), on raw slot bytes. */
        internal fun isPresent(raw: ByteArray, offset: Int): Boolean = (raw[offset + 0x13].toInt() and 0xFB) == 2

        /**
         * Reads an encrypted slot: 80 bytes (box) or 100 bytes (party) at [offset]. Box data gets empty party fields.
         */
        internal fun fromEncrypted(raw: ByteArray, offset: Int, length: Int): Pk3 {
            val data = ByteArray(PARTY_SIZE)
            raw.copyInto(data, 0, offset, offset + length)
            decrypt(data)
            val pk = Pk3(data)
            pk.checksumValidWhenRead = pk.calculateChecksum() == pk.checksum
            return pk
        }

        /** Generation 3 block order by `PID % 24` (PKHeX `PokeCrypto.BlockPosition`, first 24 rows). */
        private val BLOCK_POSITION = intArrayOf(
            0, 1, 2, 3, 0, 1, 3, 2, 0, 2, 1, 3, 0, 3, 1, 2,
            0, 2, 3, 1, 0, 3, 2, 1, 1, 0, 2, 3, 1, 0, 3, 2,
            2, 0, 1, 3, 3, 0, 1, 2, 2, 0, 3, 1, 3, 0, 2, 1,
            1, 2, 0, 3, 1, 3, 0, 2, 2, 1, 0, 3, 3, 1, 0, 2,
            2, 3, 0, 1, 3, 2, 0, 1, 1, 2, 3, 0, 1, 3, 2, 0,
            2, 1, 3, 0, 3, 1, 2, 0, 2, 3, 1, 0, 3, 2, 1, 0,
        )

        private fun seedAndShuffle(data: ByteArray): Pair<Long, Int> {
            val pid = data.u16le(0).toLong() or (data.u16le(2).toLong() shl 16)
            val oid = data.u16le(4).toLong() or (data.u16le(6).toLong() shl 16)
            return (pid xor oid) to (pid % 24).toInt()
        }

        /** PKHeX `PokeCrypto.Decrypt3`: XOR the four blocks with PID ^ OID, then put them in A, B, C, D order. */
        internal fun decrypt(data: ByteArray) {
            val (seed, sv) = seedAndShuffle(data)
            crypt(data, seed)
            // After the shuffle, block i holds the block that was at BLOCK_POSITION[sv * 4 + i].
            val src = data.copyOfRange(HEADER_SIZE, STORED_SIZE)
            for (i in 0 until 4) {
                val from = BLOCK_POSITION[sv * 4 + i]
                src.copyInto(data, HEADER_SIZE + i * BLOCK_SIZE, from * BLOCK_SIZE, (from + 1) * BLOCK_SIZE)
            }
        }

        /** PKHeX `PokeCrypto.Encrypt3`: the inverse shuffle, then the XOR. */
        internal fun encrypt(data: ByteArray) {
            val (seed, sv) = seedAndShuffle(data)
            val src = data.copyOfRange(HEADER_SIZE, STORED_SIZE)
            for (i in 0 until 4) {
                val to = BLOCK_POSITION[sv * 4 + i]
                src.copyInto(data, HEADER_SIZE + to * BLOCK_SIZE, i * BLOCK_SIZE, (i + 1) * BLOCK_SIZE)
            }
            crypt(data, seed)
        }

        private fun crypt(data: ByteArray, seed: Long) {
            var i = HEADER_SIZE
            while (i < STORED_SIZE) {
                data[i] = (data[i].toInt() xor (seed and 0xFF).toInt()).toByte()
                data[i + 1] = (data[i + 1].toInt() xor ((seed shr 8) and 0xFF).toInt()).toByte()
                data[i + 2] = (data[i + 2].toInt() xor ((seed shr 16) and 0xFF).toInt()).toByte()
                data[i + 3] = (data[i + 3].toInt() xor ((seed shr 24) and 0xFF).toInt()).toByte()
                i += 4
            }
        }

        /** PKHeX `Checksums.Add16`: 16-bit sum of little-endian words. */
        internal fun add16(data: ByteArray, start: Int, end: Int): Int {
            var sum = 0
            var i = start
            while (i < end) {
                sum += data.u16le(i)
                i += 2
            }
            return sum and 0xFFFF
        }

        /** Swaps bits [p1] and [p2] of [value] (PKHeX `G3PKM.SwapBits`). */
        private fun swapBits(value: Int, p1: Int, p2: Int): Int {
            val bit1 = (value shr p1) and 1
            val bit2 = (value shr p2) and 1
            var x = bit1 xor bit2
            x = (x shl p1) or (x shl p2)
            return value xor x
        }
    }
}

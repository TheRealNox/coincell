// Ported from PKHeX.Core/PKM/Shared/GBPKM.cs and GBPKML.cs (PKHeX, GPL-3.0, commit 641c26b).
// Shared logic of the Generation 1 / 2 formats. The format-specific field offsets live in Pk1.kt / Pk2.kt.
package org.noxeng.coincell.pkcore.gb

import org.noxeng.coincell.pkcore.CaughtData
import org.noxeng.coincell.pkcore.Dvs
import org.noxeng.coincell.pkcore.EditablePokemon
import org.noxeng.coincell.pkcore.Gender
import org.noxeng.coincell.pkcore.MoveSlot
import org.noxeng.coincell.pkcore.Pokemon
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.pkcore.StatBlock
import org.noxeng.coincell.pkcore.StatExp
import org.noxeng.coincell.pkcore.data.DvUtil
import org.noxeng.coincell.pkcore.data.Experience
import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.data.MoveInfo
import org.noxeng.coincell.pkcore.data.PersonalInfo
import org.noxeng.coincell.pkcore.data.SpeciesName
import org.noxeng.coincell.pkcore.text.LanguageId
import org.noxeng.coincell.pkcore.text.StringConverter1
import org.noxeng.coincell.pkcore.text.StringOption
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Byte offsets of the stored fields, which differ between the Gen 1 and Gen 2 formats. */
internal class GbLayout(
    val species: Int,
    val tid: Int,
    val exp: Int,
    /** First of five big-endian u16: HP, Attack, Defense, Speed, Special. */
    val statExp: Int,
    val dv: Int,
    val moves: Int,
    val pp: Int,
    val storedLevel: Int,
    val status: Int,
    val hpCurrent: Int,
    /** First of the u16 battle stats: HP, Attack, Defense, Speed, then Special (Gen 2: SpA, SpD). */
    val battleStats: Int,
    val partySize: Int,
    val storedSize: Int,
)

/**
 * Mutable Generation 1/2 Pokemon: a party-sized body plus separate OT / nickname buffers
 * (6 bytes Japanese, 11 bytes otherwise). Trash bytes after the terminators are kept as-is.
 */
public abstract class GbPokemon internal constructor(
    internal val data: ByteArray,
    internal val otTrash: ByteArray,
    internal val nickTrash: ByteArray,
    internal val layout: GbLayout,
) : EditablePokemon {
    /** Language assumed for text decoding when it can't be guessed from the nickname (the save's language). */
    public var fallbackLanguage: Int = LanguageId.ENGLISH

    override val isJapanese: Boolean get() = otTrash.size == STRING_LENGTH_JAPANESE
    override val maxTrainerNameLength: Int get() = if (isJapanese) 5 else 7
    override val maxNicknameLength: Int get() = if (isJapanese) 5 else 10

    internal abstract val personalInfo: PersonalInfo
    internal abstract fun decodeText(buffer: ByteArray): String
    internal abstract fun encodeText(dest: ByteArray, value: String, maxLength: Int, option: StringOption): Int

    protected fun u8(ofs: Int): Int = data[ofs].toInt() and 0xFF
    protected fun setU8(ofs: Int, value: Int) {
        data[ofs] = value.toByte()
    }

    protected fun u16(ofs: Int): Int = (u8(ofs) shl 8) or u8(ofs + 1)
    protected fun setU16(ofs: Int, value: Int) {
        data[ofs] = (value shr 8).toByte()
        data[ofs + 1] = value.toByte()
    }

    // ---- species / experience / level -----------------------------------------------------------------------------
    abstract override var species: Int

    /** Experience points (24 bit, big endian). */
    override var exp: Int
        get() = (u8(layout.exp) shl 16) or (u16(layout.exp + 1))
        set(value) {
            // PKHeX writes `(value << 8) | Data[next]` as a big-endian u32, which preserves the following byte.
            data[layout.exp] = (value shr 16).toByte()
            data[layout.exp + 1] = (value shr 8).toByte()
            data[layout.exp + 2] = value.toByte()
        }

    /** Level stored in the data (party level in Gen 1/2). */
    abstract override var storedLevel: Int

    override val expGrowth: Int get() = personalInfo.expGrowth

    /** Level derived from [exp] (PKHeX `CurrentLevel`). Setting it also sets [exp] and the stored level. */
    override var level: Int
        get() = Experience.getLevel(exp.toLong(), personalInfo.expGrowth)
        set(value) {
            storedLevel = value
            exp = Experience.getExp(value, personalInfo.expGrowth).toInt()
        }

    override var trainerId: Int
        get() = u16(layout.tid)
        set(value) = setU16(layout.tid, value)

    // ---- moves -----------------------------------------------------------------------------------------------------
    override fun getMove(index: Int): Int = u8(layout.moves + index)
    override fun setMove(index: Int, move: Int): Unit = setU8(layout.moves + index, move)

    override fun getMovePp(index: Int): Int = u8(layout.pp + index) and 0x3F
    override fun setMovePp(index: Int, pp: Int): Unit = setU8(layout.pp + index, (u8(layout.pp + index) and 0xC0) or min(63, pp))

    override fun getMovePpUps(index: Int): Int = (u8(layout.pp + index) and 0xC0) shr 6
    override fun setMovePpUps(index: Int, ups: Int): Unit = setU8(layout.pp + index, (u8(layout.pp + index) and 0x3F) or ((ups and 3) shl 6))

    /** PP of [move] with [ppUps] applied (PKHeX `GBPKM.GetMovePP`). */
    override fun getMaxPp(move: Int, ppUps: Int): Int {
        val pp = MoveInfo.getBasePp(generation, move)
        return pp + ppUps * min(7, pp / 5)
    }

    /** Restores PP of every move to its maximum with the current PP Ups. */
    override fun healPp() {
        for (i in 0 until 4) setMovePp(i, getMaxPp(getMove(i), getMovePpUps(i)))
    }

    // ---- DVs / stat experience ---------------------------------------------------------------------------------------
    public var dv16: Int
        get() = u16(layout.dv)
        set(value) = setU16(layout.dv, value and 0xFFFF)

    public var ivAttack: Int
        get() = (dv16 shr 12) and 0xF
        set(value) {
            dv16 = (dv16 and (0xF shl 12).inv()) or (min(value, 0xF) shl 12)
        }
    public var ivDefense: Int
        get() = (dv16 shr 8) and 0xF
        set(value) {
            dv16 = (dv16 and (0xF shl 8).inv()) or (min(value, 0xF) shl 8)
        }
    public var ivSpeed: Int
        get() = (dv16 shr 4) and 0xF
        set(value) {
            dv16 = (dv16 and (0xF shl 4).inv()) or (min(value, 0xF) shl 4)
        }
    public var ivSpecial: Int
        get() = dv16 and 0xF
        set(value) {
            dv16 = (dv16 and 0xF.inv()) or min(value, 0xF)
        }

    /** HP DV, derived from the lowest bit of the other four (read-only, as in the games). */
    public val ivHp: Int get() = Dvs(ivAttack, ivDefense, ivSpeed, ivSpecial).hp

    public var evHp: Int
        get() = u16(layout.statExp)
        set(value) = setU16(layout.statExp, value)
    public var evAttack: Int
        get() = u16(layout.statExp + 2)
        set(value) = setU16(layout.statExp + 2, value)
    public var evDefense: Int
        get() = u16(layout.statExp + 4)
        set(value) = setU16(layout.statExp + 4, value)
    public var evSpeed: Int
        get() = u16(layout.statExp + 6)
        set(value) = setU16(layout.statExp + 6, value)
    public var evSpecial: Int
        get() = u16(layout.statExp + 8)
        set(value) = setU16(layout.statExp + 8, value)

    // ---- party data ---------------------------------------------------------------------------------------------------
    override var status: Int
        get() = u8(layout.status)
        set(value) = setU8(layout.status, value)

    override var currentHp: Int
        get() = u16(layout.hpCurrent)
        set(value) = setU16(layout.hpCurrent, value)

    override var maxHp: Int
        get() = u16(layout.battleStats)
        set(value) = setU16(layout.battleStats, value)

    /** Stat as stored in the party data: 0 HP, 1 Atk, 2 Def, 3 Spe, 4 SpA, 5 SpD (Gen 1: SpA/SpD share one value). */
    public fun getStoredStat(index: Int): Int {
        val slot = if (generation == 1 && index == 5) 4 else index
        return u16(layout.battleStats + slot * 2)
    }

    public fun setStoredStat(index: Int, value: Int) {
        if (generation == 1 && index == 5) return
        setU16(layout.battleStats + index * 2, value)
    }

    /** True once party stats are present, false for data that came from a box. */
    override val partyStatsPresent: Boolean get() = maxHp != 0

    // ---- derived -------------------------------------------------------------------------------------------------------
    public val isShiny: Boolean get() = generation == 2 && DvUtil.isShiny(dv16)
    public val hiddenPowerType: Int get() = DvUtil.hiddenPowerType(dv16)

    public val gender: Gender
        get() = when (val gv = personalInfo.gender) {
            PersonalInfo.RATIO_MAGIC_GENDERLESS -> Gender.GENDERLESS
            PersonalInfo.RATIO_MAGIC_FEMALE -> Gender.FEMALE
            PersonalInfo.RATIO_MAGIC_MALE -> Gender.MALE
            else -> if (ivAttack > (gv shr 4)) Gender.MALE else Gender.FEMALE
        }

    /** Unown letter index 0..25 (A..Z) derived from the DVs, 0 for every other species. */
    public val unownForm: Int
        get() {
            if (species != 201) return 0
            var forme = 0
            forme = forme or ((ivAttack and 0x6) shl 5)
            forme = forme or ((ivDefense and 0x6) shl 3)
            forme = forme or ((ivSpeed and 0x6) shl 1)
            forme = forme or ((ivSpecial and 0x6) shr 1)
            return forme / 10
        }

    /** Computed battle stats (PKHeX `GBPKM.LoadStats`): HP, Atk, Def, Spe, SpA, SpD. */
    public fun calculateStats(): StatBlock {
        val p = personalInfo
        val lv = level
        return StatBlock(
            hp = getStat(p.hp, ivHp, evHp, lv) + (5 + lv),
            attack = getStat(p.atk, ivAttack, evAttack, lv),
            defense = getStat(p.def, ivDefense, evDefense, lv),
            speed = getStat(p.spe, ivSpeed, evSpeed, lv),
            specialAttack = getStat(p.spa, ivSpecial, evSpecial, lv),
            specialDefense = getStat(p.spd, ivSpecial, evSpecial, lv),
        )
    }

    /** Recomputes the party stats, level and clears the status (PKHeX `ResetPartyStats`). */
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

    /** [resetPartyStats] followed by [healPp] (PKHeX `Heal`). */
    override fun heal() {
        resetPartyStats()
        healPp()
    }

    // ---- text ----------------------------------------------------------------------------------------------------------
    /** Language of the nickname: Japanese, German by OT glyphs, else matched against default species names. */
    public val language: Int
        get() {
            if (isJapanese) return LanguageId.JAPANESE
            if (StringConverter1.isG12German(otTrash)) return LanguageId.GERMAN
            val nick = StringConverter1.getString(nickTrash, 0, nickTrash.size, false)
            val lang = SpeciesName.getSpeciesNameLanguage(species, nick)
            return if (lang > 0) lang else 0
        }

    /** [language], or [fallbackLanguage] when it can't be determined. */
    internal val textLanguage: Int
        get() {
            val lang = language
            return if (lang > 0) lang else fallbackLanguage
        }

    /** True if the nickname buffer (including trash) differs from the default species name. */
    override val isNicknamed: Boolean get() = !nickTrash.contentEquals(defaultNicknameBytes(textLanguage))

    private fun defaultNicknameBytes(language: Int): ByteArray {
        val expect = ByteArray(nickTrash.size)
        val name = SpeciesName.getGeneration12(species, language)
        encodeText(expect, name, expect.size, StringOption.CLEAR_50)
        for (i in expect.indices) if ((expect[i].toInt() and 0xFF) == 0xF2) expect[i] = 0xE8.toByte() // decimal point fix
        return expect
    }

    /** Resets the nickname to the species name in [language] (default: the guessed language). */
    public fun setNotNicknamed(language: Int = textLanguage) {
        val bytes = defaultNicknameBytes(language)
        bytes.copyInto(nickTrash)
    }

    override fun resetNickname(): Unit = setNotNicknamed()

    override var nickname: String
        get() = decodeText(nickTrash)
        set(value) {
            if (!isNicknamed && nickname == value) return
            setKeepingTerminatorStyle(value.take(maxNicknameLength), nickTrash)
        }

    override var otName: String
        get() = decodeText(otTrash)
        set(value) {
            if (value == otName) return
            setKeepingTerminatorStyle(value.take(maxTrainerNameLength), otTrash)
        }

    private fun setKeepingTerminatorStyle(value: String, exist: ByteArray) {
        // Reset the destination buffer based on the termination style of the existing string.
        val zeroed = exist.any { it.toInt() == 0 }
        encodeText(exist, value, value.length, if (zeroed) StringOption.CLEAR_ZERO else StringOption.CLEAR_50)
    }

    // ---- snapshots -------------------------------------------------------------------------------------------------------
    internal abstract val heldItemId: Int
    internal abstract val friendshipValue: Int?
    internal abstract val caughtDataValue: CaughtData?
    internal abstract val pokerusStrainValue: Int
    internal abstract val pokerusDaysValue: Int

    override fun summary(strings: GameStrings): PokemonSummary {
        val moves = List(4) {
            val id = getMove(it)
            MoveSlot(id, strings.moveName(id), getMovePp(it), getMovePpUps(it), getMaxPp(id, getMovePpUps(it)))
        }
        val held = heldItemId
        return PokemonSummary(
            generation = generation,
            species = species,
            speciesName = strings.speciesName(species),
            nickname = nickname,
            isNicknamed = isNicknamed,
            isEgg = isEgg,
            level = level,
            exp = exp,
            storedLevel = storedLevel,
            otName = otName,
            trainerId = trainerId,
            moves = moves,
            dvs = Dvs(ivAttack, ivDefense, ivSpeed, ivSpecial),
            statExp = StatExp(evHp, evAttack, evDefense, evSpeed, evSpecial),
            stats = calculateStats(),
            storedStats = StatBlock(
                maxHp, getStoredStat(1), getStoredStat(2), getStoredStat(3), getStoredStat(4), getStoredStat(5),
            ),
            currentHp = currentHp,
            status = status,
            heldItem = held,
            heldItemName = if (held == 0) "" else strings.itemName(2, held),
            friendship = friendshipValue,
            pokerusStrain = pokerusStrainValue,
            pokerusDays = pokerusDaysValue,
            caughtData = caughtDataValue,
            isShiny = isShiny,
            gender = gender,
            hiddenPowerType = hiddenPowerType,
            form = unownForm,
        )
    }

    /** Byte-for-byte equality of everything that is stored (body, OT and nickname buffers). */
    public fun contentEquals(other: GbPokemon): Boolean =
        generation == other.generation && data.contentEquals(other.data) &&
            otTrash.contentEquals(other.otTrash) && nickTrash.contentEquals(other.nickTrash)

    internal companion object {
        const val STRING_LENGTH_JAPANESE = 6
        const val STRING_LENGTH_OTHER = 11

        /**
         * `(2 * (base + iv) + sqrt(statExp) / 4) * level / 100 + 5`, with the games' integer square root
         * (PKHeX `GBPKM.GetStat`).
         */
        fun getStat(baseStat: Int, iv: Int, effort: Int, level: Int): Int {
            val firstSquare = min(255, ceil(sqrt(effort.toDouble())).toInt())
            val e = firstSquare shr 2
            return (((2 * (baseStat + iv)) + e) * level / 100) + 5
        }
    }
}

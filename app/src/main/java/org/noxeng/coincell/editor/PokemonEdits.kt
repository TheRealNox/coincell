package org.noxeng.coincell.editor

import org.noxeng.coincell.pkcore.EditablePokemon
import org.noxeng.coincell.pkcore.Gender
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.data.Experience
import org.noxeng.coincell.pkcore.data.Gen3Util
import org.noxeng.coincell.pkcore.data.MoveInfo
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.pkcore.gb.GbPokemon
import org.noxeng.coincell.pkcore.gb.Pk2
import org.noxeng.coincell.pkcore.gba.Pk3
import org.noxeng.coincell.pkcore.text.StringConverter1
import org.noxeng.coincell.pkcore.text.StringConverter2
import org.noxeng.coincell.pkcore.text.StringConverter3
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

enum class DvStat(val label: String) { ATTACK("Attack"), DEFENSE("Defense"), SPEED("Speed"), SPECIAL("Special") }

enum class StatExpStat(val label: String) { HP("HP"), ATTACK("Attack"), DEFENSE("Defense"), SPEED("Speed"), SPECIAL("Special") }

/** The six Generation 3 stats, in the order Pk3 stores IVs and EVs (HP, Atk, Def, Spe, SpA, SpD). */
enum class Stat3 { HP, ATTACK, DEFENSE, SPEED, SP_ATK, SP_DEF }

/**
 * Validated edits of one mutable `:pkcore` Pokemon. Every function either applies the change completely or throws
 * [InvalidEditException] with a message fit for the user, so the UI never invents its own rules. Pure, unit tested.
 */
object PokemonEdits {
    fun maxSpecies(generation: Int): Int = when (generation) {
        1 -> 151
        2 -> 251
        else -> 386
    }

    fun maxMove(generation: Int): Int = when (generation) {
        1 -> 165
        2 -> 251
        else -> 354
    }

    const val MAX_IV = 31
    const val MAX_EV = 255
    const val MAX_EV_TOTAL = 510
    const val BALL_COUNT = 12

    private fun fail(message: String): Nothing = throw InvalidEditException(message)

    // ---- species, level, experience ----------------------------------------------------------------------------------
    fun setSpecies(pk: EditablePokemon, species: Int) {
        if (species !in 1..maxSpecies(pk.generation)) fail("Species $species does not exist in Generation ${pk.generation}.")
        val keepName = pk.isNicknamed
        val level = pk.level
        pk.species = species
        // Gen 3 species have their own growth rates; keep the level rather than the raw experience.
        if (pk is Pk3) pk.level = level
        if (!keepName) pk.resetNickname()
    }

    fun setLevel(pk: EditablePokemon, level: Int) {
        if (level !in 1..100) fail("The level must be between 1 and 100.")
        pk.level = level
    }

    fun setExp(pk: EditablePokemon, exp: Int) {
        val max = maxExp(pk)
        if (exp !in 0..max) fail("Experience must be between 0 and $max for this species.")
        pk.exp = exp
        pk.storedLevel = pk.level
    }

    /** Experience of level 100 for [pk]'s growth group (the largest value the game accepts). */
    fun maxExp(pk: EditablePokemon): Int = Experience.getExp(100, pk.expGrowth).toInt()

    fun maxExp(generation: Int, species: Int): Int = Experience.getExp(100, expGrowthOf(generation, species)).toInt()

    private fun expGrowthOf(generation: Int, species: Int): Int = when (generation) {
        1 -> PersonalTable.Y
        2 -> PersonalTable.C
        else -> PersonalTable.RS
    }[species].expGrowth

    // ---- names -------------------------------------------------------------------------------------------------------
    private fun textLanguage(pk: EditablePokemon): Int = when (pk) {
        is GbPokemon -> pk.fallbackLanguage
        is Pk3 -> pk.language
        else -> 2
    }

    /** An empty [name] puts the species name back (not nicknamed). */
    fun setNickname(pk: EditablePokemon, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            pk.resetNickname()
            return
        }
        if (pk is Pk3 && pk.isEgg) fail("An egg can't have a nickname in Generation 3.")
        TextCheck.check(clean, pk.maxNicknameLength, pk.generation, textLanguage(pk), pk.isJapanese, "nickname")?.let { fail(it) }
        pk.nickname = clean
    }

    fun setOtName(pk: EditablePokemon, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) fail("The original trainer name can't be empty.")
        val language = if (pk is Pk3) pk.otLanguage else textLanguage(pk)
        val japanese = if (pk is Pk3) language == org.noxeng.coincell.pkcore.text.LanguageId.JAPANESE else pk.isJapanese
        TextCheck.check(clean, pk.maxTrainerNameLength, pk.generation, language, japanese, "trainer name")?.let { fail(it) }
        pk.otName = clean
    }

    fun setTrainerId(pk: EditablePokemon, id: Int) {
        if (id !in 0..65535) fail("The trainer ID must be between 0 and 65535.")
        pk.trainerId = id
    }

    // ---- moves -------------------------------------------------------------------------------------------------------
    /** Sets move [slot] (0..3) to [move]; 0 clears it and the later moves move up. PP is refilled and PP Ups reset for a new move. */
    fun setMove(pk: EditablePokemon, slot: Int, move: Int) {
        if (slot !in 0..3) fail("A Pokemon has four move slots.")
        if (move == 0) {
            clearMove(pk, slot)
            return
        }
        if (move !in 1..maxMove(pk.generation) || MoveInfo.getBasePp(pk.generation, move) == 0) fail("Move $move does not exist in Generation ${pk.generation}.")
        // Moves fill the slots from the top; a new move can only go into the first free slot or replace one.
        val firstFree = (0..3).firstOrNull { pk.getMove(it) == 0 } ?: 4
        val target = min(slot, firstFree)
        if (pk.getMove(target) == move) return
        pk.setMove(target, move)
        pk.setMovePpUps(target, 0)
        pk.setMovePp(target, pk.getMaxPp(move, 0))
    }

    fun clearMove(pk: EditablePokemon, slot: Int) {
        if (slot !in 0..3) fail("A Pokemon has four move slots.")
        if (pk.getMove(slot) == 0) return
        if ((0..3).count { pk.getMove(it) != 0 } <= 1) fail("A Pokemon needs at least one move.")
        // Close the gap.
        for (i in slot until 3) {
            pk.setMove(i, pk.getMove(i + 1))
            pk.setMovePpUps(i, pk.getMovePpUps(i + 1))
            pk.setMovePp(i, pk.getMovePp(i + 1))
        }
        pk.setMove(3, 0)
        pk.setMovePpUps(3, 0)
        pk.setMovePp(3, 0)
    }

    fun setPp(pk: EditablePokemon, slot: Int, pp: Int) {
        val move = pk.getMove(slot)
        if (move == 0) fail("This move slot is empty.")
        val max = pk.getMaxPp(move, pk.getMovePpUps(slot))
        if (pp !in 0..max) fail("PP must be between 0 and $max for this move.")
        pk.setMovePp(slot, pp)
    }

    fun setPpUps(pk: EditablePokemon, slot: Int, ups: Int) {
        val move = pk.getMove(slot)
        if (move == 0) fail("This move slot is empty.")
        if (ups !in 0..3) fail("PP Ups must be between 0 and 3.")
        val wasFull = pk.getMovePp(slot) >= pk.getMaxPp(move, pk.getMovePpUps(slot))
        pk.setMovePpUps(slot, ups)
        // PP stays full when it was full, and never exceeds the new maximum.
        val newMax = pk.getMaxPp(move, ups)
        pk.setMovePp(slot, if (wasFull) newMax else min(pk.getMovePp(slot), newMax))
    }

    // ---- DVs and stat experience (Generations 1/2) -------------------------------------------------------------------
    private fun gb(pk: EditablePokemon, what: String): GbPokemon = pk as? GbPokemon ?: fail("$what only exist in Generations 1 and 2.")

    fun setDv(pk: EditablePokemon, stat: DvStat, value: Int) {
        val p = gb(pk, "DVs")
        if (value !in 0..15) fail("A DV must be between 0 and 15.")
        when (stat) {
            DvStat.ATTACK -> p.ivAttack = value
            DvStat.DEFENSE -> p.ivDefense = value
            DvStat.SPEED -> p.ivSpeed = value
            DvStat.SPECIAL -> p.ivSpecial = value
        }
    }

    fun setStatExp(pk: EditablePokemon, stat: StatExpStat, value: Int) {
        val p = gb(pk, "Stat experience values")
        if (value !in 0..65535) fail("Stat experience must be between 0 and 65535.")
        when (stat) {
            StatExpStat.HP -> p.evHp = value
            StatExpStat.ATTACK -> p.evAttack = value
            StatExpStat.DEFENSE -> p.evDefense = value
            StatExpStat.SPEED -> p.evSpeed = value
            StatExpStat.SPECIAL -> p.evSpecial = value
        }
    }

    // ---- held item, friendship, egg (Generations 2 and 3) ------------------------------------------------------------
    fun setHeldItem(pk: EditablePokemon, item: Int) {
        when (pk) {
            is Pk2 -> {
                if (item !in 0..255) fail("Invalid item.")
                pk.heldItem = item
            }
            is Pk3 -> {
                if (item !in 0..376) fail("Invalid item.")
                pk.heldItem = item
            }
            else -> fail("Held items only exist from Generation 2 on.")
        }
    }

    fun setFriendship(pk: EditablePokemon, value: Int) {
        if (value !in 0..255) fail("Friendship must be between 0 and 255.")
        when (pk) {
            is Pk2 -> pk.friendship = value
            is Pk3 -> pk.friendship = value
            else -> fail("Friendship only exists from Generation 2 on.")
        }
    }

    /**
     * Gen 2 eggs only change the list marker. A Gen 3 egg takes the Japanese egg name and language (as the games
     * store them); hatching gives it back the save's language and the species name.
     */
    fun setEgg(pk: EditablePokemon, egg: Boolean) {
        when (pk) {
            is Pk2 -> pk.isEgg = egg
            is Pk3 -> {
                if (pk.isEgg == egg) return
                pk.isEgg = egg
                if (!egg) {
                    pk.language = pk.fallbackLanguage
                    pk.resetNickname()
                }
            }
            else -> fail("Eggs only exist from Generation 2 on.")
        }
    }

    // ---- caught data (Generation 2) ----------------------------------------------------------------------------------
    private fun gen2(pk: EditablePokemon, what: String): Pk2 = pk as? Pk2 ?: fail("$what only exists in Generation 2.")

    fun setMetLevel(pk: EditablePokemon, v: Int) {
        when (pk) {
            is Pk3 -> {
                if (v !in 0..100) fail("The met level must be between 0 and 100.")
                pk.metLevel = v
            }
            else -> {
                if (v !in 0..63) fail("The met level must be between 0 and 63.")
                gen2(pk, "Caught data").metLevel = v
            }
        }
    }

    fun setMetLocation(pk: EditablePokemon, v: Int) {
        when (pk) {
            is Pk3 -> {
                if (v !in 0..255) fail("The met location must be between 0 and 255.")
                pk.metLocation = v
            }
            else -> {
                if (v !in 0..127) fail("The met location must be between 0 and 127.")
                gen2(pk, "Caught data").metLocation = v
            }
        }
    }

    fun setMetTimeOfDay(pk: EditablePokemon, v: Int) {
        if (v !in 0..3) fail("Invalid time of day.")
        gen2(pk, "Caught data").metTimeOfDay = v
    }

    fun setOriginalTrainerGender(pk: EditablePokemon, v: Int) {
        if (v !in 0..1) fail("Invalid trainer gender.")
        when (pk) {
            is Pk3 -> pk.originalTrainerGender = v
            else -> gen2(pk, "Caught data").originalTrainerGender = v
        }
    }

    /**
     * Gen 2 shininess is derived from the DVs (Defense, Speed and Special 10, Attack 2/3/6/7/10/11/14/15), so
     * "make shiny" just sets those DVs, taking the Attack DV closest to the current one that keeps the gender.
     * Changes the HP DV, and an Unown's letter. Gen 3 shininess comes from the PID, which is rerolled keeping the
     * nature, gender and Unown letter.
     */
    fun makeShiny(pk: EditablePokemon, random: Random = Random.Default) {
        if (pk is Pk3) {
            rerollPid(pk, shiny = true, random = random)
            return
        }
        val p = gen2(pk, "Shininess")
        val gender = p.gender
        val candidates = listOf(2, 3, 6, 7, 10, 11, 14, 15).sortedBy { abs(it - p.ivAttack) }
        for (attack in candidates) {
            val t = p.duplicate()
            t.ivAttack = attack
            t.ivDefense = 10
            t.ivSpeed = 10
            t.ivSpecial = 10
            if (t.gender == gender) {
                p.ivAttack = attack
                p.ivDefense = 10
                p.ivSpeed = 10
                p.ivSpecial = 10
                return
            }
        }
        fail("A shiny ${if (gender == Gender.FEMALE) "female" else "male"} of this species can't exist in Generation 2: the Attack DV a shiny needs would change its gender.")
    }

    // ---- Generation 3 ------------------------------------------------------------------------------------------------
    private fun gen3(pk: EditablePokemon, what: String): Pk3 = pk as? Pk3 ?: fail("$what only exist in Generation 3.")

    fun setIv(pk: EditablePokemon, stat: Stat3, value: Int) {
        val p = gen3(pk, "IVs")
        if (value !in 0..MAX_IV) fail("An IV must be between 0 and $MAX_IV.")
        p.setIv(stat.ordinal, value)
    }

    /** EVs are 0..255 each and at most 510 together, as the games allow. */
    fun setEv(pk: EditablePokemon, stat: Stat3, value: Int) {
        val p = gen3(pk, "EVs")
        if (value !in 0..MAX_EV) fail("An EV must be between 0 and $MAX_EV.")
        val others = (0 until 6).filter { it != stat.ordinal }.sumOf { p.getEv(it) }
        if (others + value > MAX_EV_TOTAL) fail("EVs can add up to $MAX_EV_TOTAL at most (${MAX_EV_TOTAL - others} left for this stat).")
        p.setEv(stat.ordinal, value)
    }

    /** Second ability: only for species that have two. */
    fun setAbilitySlot(pk: EditablePokemon, second: Boolean) {
        val p = gen3(pk, "Abilities")
        if (second && !p.hasSecondAbility) fail("This species has a single ability.")
        p.abilityBit = second
    }

    fun setBall(pk: EditablePokemon, ball: Int) {
        if (ball !in 1..BALL_COUNT) fail("Invalid ball.")
        gen3(pk, "Balls").ball = ball
    }

    fun setOriginGame(pk: EditablePokemon, game: Int) {
        if (game !in ORIGIN_GAMES) fail("Invalid game of origin.")
        gen3(pk, "Games of origin").originGame = game
    }

    fun setSecretId(pk: EditablePokemon, sid: Int) {
        if (sid !in 0..65535) fail("The secret ID must be between 0 and 65535.")
        gen3(pk, "Secret IDs").secretId = sid
    }

    /** Game of origin values a Gen 3 Pokemon can carry (1 Sapphire, 2 Ruby, 3 Emerald, 4 FireRed, 5 LeafGreen, 15 Colosseum/XD). */
    val ORIGIN_GAMES = listOf(1, 2, 3, 4, 5, 15)

    /** The nature comes from the PID: a new PID is picked keeping the gender, shininess and Unown letter. */
    fun setNature(pk: EditablePokemon, nature: Int, random: Random = Random.Default) {
        val p = gen3(pk, "Natures")
        if (nature !in 0..24) fail("Invalid nature.")
        if (p.nature == nature) return
        rerollPid(p, nature = nature, random = random)
    }

    /** The gender comes from the PID: a new PID is picked keeping the nature, shininess and Unown letter. */
    fun setGender(pk: EditablePokemon, gender: Gender, random: Random = Random.Default) {
        val p = gen3(pk, "Genders")
        if (p.gender == gender) return
        val ratio = PersonalTable.RS[p.species].gender
        if (ratio !in 1..253) fail("This species can only be ${p.gender.name.lowercase()}.")
        if (gender == Gender.GENDERLESS) fail("This species has a gender.")
        rerollPid(p, gender = gender, random = random)
    }

    /** Unown's letter comes from the PID: a new PID is picked keeping the nature, gender and shininess. */
    fun setUnownForm(pk: EditablePokemon, form: Int, random: Random = Random.Default) {
        val p = gen3(pk, "Unown letters")
        if (p.species != UNOWN) fail("Only Unown has letters.")
        if (form !in 0..27) fail("Invalid letter.")
        if (p.form == form) return
        rerollPid(p, form = form, random = random)
    }

    /**
     * Picks a PID that gives the requested nature / gender / shininess / Unown letter (each defaulting to the current
     * one). The low half is drawn at random; for a shiny the high half is derived so that TID ^ SID ^ high ^ low < 8,
     * otherwise it is drawn too. A few million tries cover every combination the games allow.
     */
    fun rerollPid(
        pk: Pk3,
        nature: Int = pk.nature,
        gender: Gender = pk.gender,
        shiny: Boolean = pk.isShiny,
        form: Int = pk.form,
        random: Random = Random.Default,
    ) {
        val ratio = PersonalTable.RS[pk.species].gender
        val unown = pk.species == UNOWN
        repeat(MAX_PID_TRIES) {
            val lo = random.nextInt(0x10000)
            val hi = if (shiny) (pk.trainerId xor pk.secretId xor lo xor random.nextInt(8)) and 0xFFFF else random.nextInt(0x10000)
            val pid = (hi.toLong() shl 16) or lo.toLong()
            if (Gen3Util.nature(pid) != nature) return@repeat
            if (Gen3Util.gender(pid, ratio) != gender) return@repeat
            if (Gen3Util.isShiny(pid, pk.trainerId, pk.secretId) != shiny) return@repeat
            if (unown && Gen3Util.unownForm(pid) != form) return@repeat
            pk.pid = pid
            return
        }
        fail("No PID was found for this combination. Try again.")
    }

    private const val UNOWN = 201
    private const val MAX_PID_TRIES = 5_000_000

    // ---- bookkeeping -------------------------------------------------------------------------------------------------
    /**
     * Brings the derived stored values back in line after an edit: the stored level follows the experience and,
     * for a party Pokemon, the stats stored with it (HP stays full if it was full, status is kept).
     */
    fun finalize(pk: EditablePokemon, inParty: Boolean) {
        pk.storedLevel = pk.level
        if (!inParty) return
        val oldMax = pk.maxHp
        val oldCurrent = pk.currentHp
        val status = pk.status
        val wasFull = oldMax == 0 || oldCurrent >= oldMax
        pk.resetPartyStats()
        pk.status = status
        if (!wasFull) pk.currentHp = min(oldCurrent, pk.maxHp)
    }

    /** Full HP, no status, full PP (what a Pokemon Center does). */
    fun heal(pk: EditablePokemon) = pk.heal()
}

/** Whether a text fits the character set and length of a save, decided by `:pkcore`'s own converters. */
object TextCheck {
    /** Null when [text] is fine, otherwise the message to show. */
    fun check(text: String, maxLength: Int, generation: Int, language: Int, japanese: Boolean, what: String): String? {
        if (text.length > maxLength) return "The $what can have at most $maxLength characters."
        if (!roundTrips(text, maxLength, generation, language, japanese)) {
            val bad = text.firstOrNull { !roundTrips(it.toString(), 1, generation, language, japanese) }
            return if (bad != null) "The game can't store the character '$bad' in a $what." else "The game can't store this $what."
        }
        return null
    }

    fun roundTrips(text: String, maxLength: Int, generation: Int, language: Int, japanese: Boolean): Boolean {
        val buffer = ByteArray(maxLength + 2)
        return try {
            val decoded = when (generation) {
                1 -> {
                    StringConverter1.setString(buffer, 0, buffer.size, text, maxLength, japanese)
                    StringConverter1.getString(buffer, 0, buffer.size, japanese)
                }
                2 -> {
                    StringConverter2.setString(buffer, 0, buffer.size, text, maxLength, language)
                    StringConverter2.getString(buffer, 0, buffer.size, language)
                }
                else -> {
                    val lang = if (japanese) org.noxeng.coincell.pkcore.text.LanguageId.JAPANESE else language
                    StringConverter3.setString(buffer, 0, buffer.size, text, maxLength, lang)
                    StringConverter3.getString(buffer, 0, buffer.size, lang)
                }
            }
            decoded == text
        } catch (_: Exception) {
            false
        }
    }

    private const val CANDIDATES =
        " ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
            "!?.,-/:;()'’“”…×♂♀▶▷_·ÀÂÄÇÈÉÊËÎÏÔÖÙÛÜàâäçèéêëîïôöùûüœÑñ¡¿ßÆæ"

    /** The characters (of the usual Latin set) the save can store, for the hint under a name field. */
    fun allowedCharacters(generation: Int, language: Int, japanese: Boolean): String =
        if (japanese) "" else CANDIDATES.filter { roundTrips(it.toString(), 1, generation, language, false) }
}

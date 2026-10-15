package org.noxeng.coincell.editor

import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.data.Experience
import org.noxeng.coincell.pkcore.data.MoveInfo
import org.noxeng.coincell.pkcore.data.PersonalTable
import org.noxeng.coincell.pkcore.gb.GbPokemon
import org.noxeng.coincell.pkcore.gb.Pk2
import org.noxeng.coincell.pkcore.text.StringConverter1
import org.noxeng.coincell.pkcore.text.StringConverter2
import kotlin.math.abs
import kotlin.math.min

enum class DvStat(val label: String) { ATTACK("Attack"), DEFENSE("Defense"), SPEED("Speed"), SPECIAL("Special") }

enum class StatExpStat(val label: String) { HP("HP"), ATTACK("Attack"), DEFENSE("Defense"), SPEED("Speed"), SPECIAL("Special") }

/**
 * Validated edits of one mutable `:pkcore` Pokemon. Every function either applies the change completely or throws
 * [InvalidEditException] with a message fit for the user, so the UI never invents its own rules. Pure, unit tested.
 */
object PokemonEdits {
    fun maxSpecies(generation: Int): Int = if (generation == 1) 151 else 251
    fun maxMove(generation: Int): Int = if (generation == 1) 165 else 251

    private fun fail(message: String): Nothing = throw InvalidEditException(message)

    // ---- species, level, experience ----------------------------------------------------------------------------------
    fun setSpecies(pk: GbPokemon, species: Int) {
        if (species !in 1..maxSpecies(pk.generation)) fail("Species ${species} does not exist in Generation ${pk.generation}.")
        val keepName = pk.isNicknamed
        pk.species = species
        if (!keepName) pk.setNotNicknamed()
    }

    fun setLevel(pk: GbPokemon, level: Int) {
        if (level !in 1..100) fail("The level must be between 1 and 100.")
        pk.level = level
    }

    fun setExp(pk: GbPokemon, exp: Int) {
        val max = Experience.getExp(100, expGrowthOf(pk)).toInt()
        if (exp !in 0..max) fail("Experience must be between 0 and $max for this species.")
        pk.exp = exp
        pk.storedLevel = pk.level
    }

    /** Experience of level 100 for [pk]'s growth group (the largest value the game accepts). */
    fun maxExp(pk: GbPokemon): Int = Experience.getExp(100, expGrowthOf(pk)).toInt()

    fun maxExp(generation: Int, species: Int): Int = Experience.getExp(100, expGrowthOf(generation, species)).toInt()

    private fun expGrowthOf(pk: GbPokemon): Int = expGrowthOf(pk.generation, pk.species)

    private fun expGrowthOf(generation: Int, species: Int): Int =
        (if (generation == 1) PersonalTable.Y else PersonalTable.C)[species].expGrowth

    // ---- names -------------------------------------------------------------------------------------------------------
    /** An empty [name] puts the species name back (not nicknamed). */
    fun setNickname(pk: GbPokemon, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            pk.setNotNicknamed()
            return
        }
        TextCheck.check(clean, pk.maxNicknameLength, pk.generation, pk.fallbackLanguage, pk.isJapanese, "nickname")?.let { fail(it) }
        pk.nickname = clean
    }

    fun setOtName(pk: GbPokemon, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) fail("The original trainer name can't be empty.")
        TextCheck.check(clean, pk.maxTrainerNameLength, pk.generation, pk.fallbackLanguage, pk.isJapanese, "trainer name")?.let { fail(it) }
        pk.otName = clean
    }

    fun setTrainerId(pk: GbPokemon, id: Int) {
        if (id !in 0..65535) fail("The trainer ID must be between 0 and 65535.")
        pk.trainerId = id
    }

    // ---- moves -------------------------------------------------------------------------------------------------------
    /** Sets move [slot] (0..3) to [move]; 0 clears it and the later moves move up. PP is refilled and PP Ups reset for a new move. */
    fun setMove(pk: GbPokemon, slot: Int, move: Int) {
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

    fun clearMove(pk: GbPokemon, slot: Int) {
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

    fun setPp(pk: GbPokemon, slot: Int, pp: Int) {
        val move = pk.getMove(slot)
        if (move == 0) fail("This move slot is empty.")
        val max = pk.getMaxPp(move, pk.getMovePpUps(slot))
        if (pp !in 0..max) fail("PP must be between 0 and $max for this move.")
        pk.setMovePp(slot, pp)
    }

    fun setPpUps(pk: GbPokemon, slot: Int, ups: Int) {
        val move = pk.getMove(slot)
        if (move == 0) fail("This move slot is empty.")
        if (ups !in 0..3) fail("PP Ups must be between 0 and 3.")
        val wasFull = pk.getMovePp(slot) >= pk.getMaxPp(move, pk.getMovePpUps(slot))
        pk.setMovePpUps(slot, ups)
        // PP stays full when it was full, and never exceeds the new maximum.
        val newMax = pk.getMaxPp(move, ups)
        pk.setMovePp(slot, if (wasFull) newMax else min(pk.getMovePp(slot), newMax))
    }

    // ---- DVs and stat experience -------------------------------------------------------------------------------------
    fun setDv(pk: GbPokemon, stat: DvStat, value: Int) {
        if (value !in 0..15) fail("A DV must be between 0 and 15.")
        when (stat) {
            DvStat.ATTACK -> pk.ivAttack = value
            DvStat.DEFENSE -> pk.ivDefense = value
            DvStat.SPEED -> pk.ivSpeed = value
            DvStat.SPECIAL -> pk.ivSpecial = value
        }
    }

    fun setStatExp(pk: GbPokemon, stat: StatExpStat, value: Int) {
        if (value !in 0..65535) fail("Stat experience must be between 0 and 65535.")
        when (stat) {
            StatExpStat.HP -> pk.evHp = value
            StatExpStat.ATTACK -> pk.evAttack = value
            StatExpStat.DEFENSE -> pk.evDefense = value
            StatExpStat.SPEED -> pk.evSpeed = value
            StatExpStat.SPECIAL -> pk.evSpecial = value
        }
    }

    // ---- Generation 2 only -------------------------------------------------------------------------------------------
    private fun gen2(pk: GbPokemon, what: String): Pk2 = pk as? Pk2 ?: fail("$what only exists in Generation 2.")

    fun setHeldItem(pk: GbPokemon, item: Int) {
        if (item !in 0..255) fail("Invalid item.")
        gen2(pk, "Held items").heldItem = item
    }

    fun setFriendship(pk: GbPokemon, value: Int) {
        if (value !in 0..255) fail("Friendship must be between 0 and 255.")
        gen2(pk, "Friendship").friendship = value
    }

    fun setEgg(pk: GbPokemon, egg: Boolean) {
        gen2(pk, "Eggs").isEgg = egg
    }

    fun setMetLevel(pk: GbPokemon, v: Int) {
        if (v !in 0..63) fail("The met level must be between 0 and 63.")
        gen2(pk, "Caught data").metLevel = v
    }

    fun setMetLocation(pk: GbPokemon, v: Int) {
        if (v !in 0..127) fail("The met location must be between 0 and 127.")
        gen2(pk, "Caught data").metLocation = v
    }

    fun setMetTimeOfDay(pk: GbPokemon, v: Int) {
        if (v !in 0..3) fail("Invalid time of day.")
        gen2(pk, "Caught data").metTimeOfDay = v
    }

    fun setOriginalTrainerGender(pk: GbPokemon, v: Int) {
        if (v !in 0..1) fail("Invalid trainer gender.")
        gen2(pk, "Caught data").originalTrainerGender = v
    }

    /**
     * Gen 2 shininess is derived from the DVs (Defense, Speed and Special 10, Attack 2/3/6/7/10/11/14/15), so
     * "make shiny" just sets those DVs, taking the Attack DV closest to the current one that keeps the gender.
     * Changes the HP DV, and an Unown's letter.
     */
    fun makeShiny(pk: GbPokemon) {
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
        fail("A shiny ${if (gender == org.noxeng.coincell.pkcore.Gender.FEMALE) "female" else "male"} of this species can't exist in Generation 2: the Attack DV a shiny needs would change its gender.")
    }

    // ---- bookkeeping -------------------------------------------------------------------------------------------------
    /**
     * Brings the derived stored values back in line after an edit: the stored level follows the experience and,
     * for a party Pokemon, the stats stored with it (HP stays full if it was full, status is kept).
     */
    fun finalize(pk: GbPokemon, inParty: Boolean) {
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
    fun heal(pk: GbPokemon) = pk.heal()
}

/** Whether a text fits the character set and length of a Generation 1/2 save, decided by `:pkcore`'s own converters. */
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
            val decoded = if (generation == 1) {
                StringConverter1.setString(buffer, 0, buffer.size, text, maxLength, japanese)
                StringConverter1.getString(buffer, 0, buffer.size, japanese)
            } else {
                StringConverter2.setString(buffer, 0, buffer.size, text, maxLength, language)
                StringConverter2.getString(buffer, 0, buffer.size, language)
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

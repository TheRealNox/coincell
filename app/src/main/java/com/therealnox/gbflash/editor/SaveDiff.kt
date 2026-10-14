package com.therealnox.gbflash.editor

import com.therealnox.gbflash.pkcore.PlayTime
import com.therealnox.gbflash.pkcore.PocketType
import com.therealnox.gbflash.pkcore.PokemonSummary
import com.therealnox.gbflash.pkcore.StatBlock
import com.therealnox.gbflash.pkcore.SlotRef
import com.therealnox.gbflash.pkcore.data.GameStrings

/** One user-visible difference between the loaded save and the working copy. */
data class Change(val area: Area, val text: String) {
    enum class Area { TRAINER, POKEMON, BAG }
}

/**
 * The change summary (docs/UI_DESIGN.md 6.3): compares the working copy with the state the editor started from and
 * says what differs, in the save's language. Pure, so it is unit tested; the note of a new "Edited" backup is built from it.
 */
object SaveDiff {
    fun compute(before: SaveSnapshot, after: SaveSnapshot, strings: GameStrings, generation: Int): List<Change> =
        pokemonChanges(before, after) + trainerChanges(before, after) + bagChanges(before, after, strings, generation)

    // ---- trainer ----------------------------------------------------------------------------------------------------
    private fun trainerChanges(b: SaveSnapshot, a: SaveSnapshot): List<Change> {
        val o = b.trainer
        val n = a.trainer
        val out = ArrayList<String>()
        if (o.name != n.name) out += "name ${o.name}→${n.name}"
        if (o.id != n.id) out += "trainer ID ${o.id}→${n.id}"
        if (o.money != n.money) out += "money ${o.money}→${n.money}"
        if (o.coins != n.coins) out += "coins ${o.coins}→${n.coins}"
        if (o.badges != n.badges) out += "badges ${o.badgeCount}→${n.badgeCount}"
        if (o.playTime != n.playTime) out += "play time ${time(o.playTime)}→${time(n.playTime)}"
        if (o.rivalName != n.rivalName) out += "rival ${o.rivalName}→${n.rivalName}"
        if (o.gender != n.gender) out += "gender ${o.gender}→${n.gender}"
        return out.map { Change(Change.Area.TRAINER, it) }
    }

    private fun time(t: PlayTime) = "%d:%02d".format(t.hours, t.minutes)

    // ---- Pokemon ----------------------------------------------------------------------------------------------------
    private class Entry(val slot: SlotRef, val pk: PokemonSummary)

    /**
     * The same Pokemon. Between the party and a box only the party-only values (current HP, status, stored stats) differ,
     * because a box does not keep them, so those are ignored there; inside one kind of container everything counts.
     */
    private fun sameData(a: Entry, b: Entry): Boolean {
        if ((a.slot is SlotRef.Party) == (b.slot is SlotRef.Party)) return a.pk == b.pk
        return a.pk.withoutPartyData() == b.pk.withoutPartyData()
    }

    private fun PokemonSummary.withoutPartyData(): PokemonSummary =
        copy(currentHp = 0, status = 0, storedStats = StatBlock(0, 0, 0, 0, 0, 0))

    private fun container(slot: SlotRef): Int = when (slot) {
        is SlotRef.Party -> -1
        is SlotRef.Box -> slot.box
    }

    private fun place(slot: SlotRef): String = when (slot) {
        is SlotRef.Party -> "party"
        is SlotRef.Box -> "box ${slot.box + 1}"
    }

    /**
     * Pokemon are matched, not compared by position, so that deleting or moving one does not report everything after
     * it: first identical Pokemon in the same slot, then identical ones elsewhere (moved), then the same Pokemon with
     * different data (edited: same OT and ID). What is left was removed or added.
     */
    private fun pokemonChanges(b: SaveSnapshot, a: SaveSnapshot): List<Change> {
        val old = b.all().map { Entry(it.first, it.second) }.toMutableList()
        val new = a.all().map { Entry(it.first, it.second) }.toMutableList()
        val out = ArrayList<String>()

        // 1. unchanged, same place
        val it1 = old.iterator()
        while (it1.hasNext()) {
            val o = it1.next()
            val m = new.firstOrNull { it.slot == o.slot && sameData(o, it) } ?: continue
            new.remove(m)
            it1.remove()
        }

        // 2. unchanged but elsewhere
        val reordered = sortedSetOf<Int>()
        val it2 = old.iterator()
        while (it2.hasNext()) {
            val o = it2.next()
            val m = new.firstOrNull { sameData(o, it) } ?: continue
            new.remove(m)
            it2.remove()
            if (container(o.slot) != container(m.slot)) {
                out += "moved ${o.pk.speciesName} ${place(o.slot)}→${place(m.slot)}"
            } else {
                reordered += container(o.slot)
            }
        }

        // 3. edited: the same species with the same OT and ID, preferring the same slot
        val it3 = old.iterator()
        while (it3.hasNext()) {
            val o = it3.next()
            val candidates = new.filter { it.pk.trainerId == o.pk.trainerId && it.pk.otName == o.pk.otName && it.pk.species == o.pk.species }
            val m = candidates.firstOrNull { it.slot == o.slot } ?: candidates.firstOrNull() ?: continue
            new.remove(m)
            it3.remove()
            out += describeEdit(o.pk, m.pk)
        }
        // 3b. a different species in the very same slot is an edit too (a species change), unless the container grew or
        // shrank, in which case it is a removal plus an addition.
        val it4 = old.iterator()
        while (it4.hasNext()) {
            val o = it4.next()
            if (sizeOf(b, o.slot) != sizeOf(a, o.slot)) continue
            val m = new.firstOrNull { it.slot == o.slot } ?: continue
            new.remove(m)
            it4.remove()
            out += describeEdit(o.pk, m.pk)
        }

        for (o in old) out += "removed ${label(o.pk)} (${place(o.slot)})"
        for (n in new) out += "added ${label(n.pk)} (${place(n.slot)})"

        // A pure reorder inside one container; when something was added or removed there, the others merely shifted.
        for (c in reordered) {
            val touched = old.any { container(it.slot) == c } || new.any { container(it.slot) == c }
            if (!touched) out += "reordered ${if (c < 0) "the party" else "box ${c + 1}"}"
        }
        return out.map { Change(Change.Area.POKEMON, it) }
    }

    private fun sizeOf(s: SaveSnapshot, slot: SlotRef): Int = when (slot) {
        is SlotRef.Party -> s.party.size
        is SlotRef.Box -> s.boxes.getOrNull(slot.box)?.pokemon?.size ?: 0
    }

    private fun label(p: PokemonSummary) = if (p.isEgg) "${p.speciesName} egg" else "${p.speciesName} L${p.level}"

    private fun describeEdit(o: PokemonSummary, n: PokemonSummary): String {
        val parts = ArrayList<String>()
        if (o.species != n.species) parts += "${o.speciesName}→${n.speciesName}"
        if (o.level != n.level) {
            parts += "L${o.level}→L${n.level}"
        } else if (o.exp != n.exp) {
            parts += "EXP ${o.exp}→${n.exp}"
        }
        if (o.nickname != n.nickname) parts += "nickname"
        if (o.isEgg != n.isEgg) parts += if (n.isEgg) "made an egg" else "hatched"
        if (o.moves.map { it.id } != n.moves.map { it.id }) {
            parts += "moves"
        } else if (o.moves.map { it.pp to it.ppUps } != n.moves.map { it.pp to it.ppUps }) {
            parts += "PP"
        }
        if (o.dvs != n.dvs) parts += "DVs"
        if (o.statExp != n.statExp) parts += "stat EXP"
        if (o.heldItem != n.heldItem) parts += "item ${o.heldItemName.ifEmpty { "none" }}→${n.heldItemName.ifEmpty { "none" }}"
        if (o.friendship != n.friendship) parts += "friendship ${o.friendship}→${n.friendship}"
        if (o.otName != n.otName) parts += "OT"
        if (o.trainerId != n.trainerId) parts += "ID"
        if (o.caughtData != n.caughtData) parts += "met data"
        val bothParty = o.storedStats.hp != 0 && n.storedStats.hp != 0
        val bothFull = o.currentHp >= o.storedStats.hp && n.currentHp >= n.storedStats.hp
        if (bothParty && o.currentHp != n.currentHp && !bothFull) parts += "HP"
        if (bothParty && o.status != n.status) parts += "status"
        if (o.pokerusStrain != n.pokerusStrain || o.pokerusDays != n.pokerusDays) parts += "Pokerus"
        if (parts.isEmpty()) parts += "data"
        val name = n.speciesName
        return if (parts.size == 1 && parts[0].startsWith("L")) "$name ${parts[0]}" else "$name: ${parts.joinToString(", ")}"
    }

    // ---- bag --------------------------------------------------------------------------------------------------------
    private fun bagChanges(b: SaveSnapshot, a: SaveSnapshot, strings: GameStrings, generation: Int): List<Change> {
        val out = ArrayList<String>()
        for (pocket in a.inventory.pockets) {
            val before = b.inventory.pocket(pocket.type)?.items?.associate { it.id to it.count }.orEmpty()
            val after = pocket.items.associate { it.id to it.count }
            if (before == after) continue
            val tag = pocketLabel(pocket.type)
            for ((id, count) in after) {
                val was = before[id]
                val name = strings.itemName(generation, id)
                when {
                    was == null -> out += "$tag: +$name x$count"
                    was != count -> out += "$tag: $name $was→$count"
                }
            }
            for (id in before.keys) if (id !in after) out += "$tag: -${strings.itemName(generation, id)}"
        }
        return out.map { Change(Change.Area.BAG, it) }
    }

    fun pocketLabel(type: PocketType): String = when (type) {
        PocketType.ITEMS -> "Items"
        PocketType.KEY_ITEMS -> "Key items"
        PocketType.TM_HM -> "TM/HM"
        PocketType.BALLS -> "Balls"
        PocketType.PC_ITEMS -> "PC items"
    }

    // ---- note -------------------------------------------------------------------------------------------------------
    /** "Edited: Typhlosion L37→L50; money 63154→999999", cut at [maxLength] with "(+N more)". */
    fun note(changes: List<Change>, maxLength: Int): String {
        if (changes.isEmpty()) return ""
        val prefix = "Edited: "
        val sb = StringBuilder(prefix)
        var used = 0
        for ((i, c) in changes.withIndex()) {
            val piece = (if (i == 0) "" else "; ") + c.text
            val remaining = changes.size - i - 1
            val tail = if (remaining > 0) " (+$remaining more)" else ""
            if (sb.length + piece.length + tail.length > maxLength) {
                if (used == 0) {
                    val room = maxLength - prefix.length - 1
                    sb.append(c.text.take(room.coerceAtLeast(0))).append('…')
                    return sb.toString().take(maxLength)
                }
                val left = changes.size - used
                val more = " (+$left more)"
                if (sb.length + more.length <= maxLength) sb.append(more)
                return sb.toString()
            }
            sb.append(piece)
            used++
        }
        return sb.toString()
    }
}

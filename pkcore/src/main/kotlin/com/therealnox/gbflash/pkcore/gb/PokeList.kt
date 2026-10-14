// Ported from PKHeX.Core/PKM/Shared/PokeList1.cs and PokeList2.cs (PKHeX, GPL-3.0, commit 641c26b).
// The two C# classes only differ in body sizes, the egg marker and the "destination initialised" check of
// MergeSingles (Gen 1 only), so one parametrised class covers both.
//
// Structure of a list in the save:
//   u8               Count of slots filled
//   s8[capacity+1]   GB Species ID in Slot (0xFF = no species, list ends with 0xFF)
//   pkx[capacity]    GB PKM data (no strings): 33/44 (Gen 1) or 32/48 (Gen 2) bytes
//   str[capacity]    Trainer Name
//   str[capacity]    Nickname
// where str has a variable size (6 Japanese / 11 otherwise).
package com.therealnox.gbflash.pkcore.gb

/** One filled slot of a list: the Pokemon and the species marker of the list header (0xFD for Gen 2 eggs). */
internal class Slot(val pk: GbPokemon, var marker: Int)

/** Format description + (un)packing of the Pokemon lists of the Gen 1 / Gen 2 saves. */
internal class PokeListFormat(val generation: Int, val japanese: Boolean) {
    val stringLength: Int = if (japanese) GbPokemon.STRING_LENGTH_JAPANESE else GbPokemon.STRING_LENGTH_OTHER
    private val bodyParty: Int = if (generation == 1) Pk1.PARTY_SIZE else Pk2.PARTY_SIZE
    private val bodyStored: Int = if (generation == 1) Pk1.STORED_SIZE else Pk2.STORED_SIZE

    /** Total bytes of a list with [capacity] slots. */
    fun listLength(capacity: Int, party: Boolean): Int =
        1 + (capacity + 1) + (body(party) * capacity) + (stringLength * capacity * 2)

    /** Size of one body in a list: the party format or the (shorter) box format. */
    private fun body(party: Boolean): Int = if (party) bodyParty else bodyStored

    fun newPokemon(body: ByteArray, ot: ByteArray, nick: ByteArray): GbPokemon =
        if (generation == 1) Pk1(body, ot, nick) else Pk2(body, ot, nick)

    fun blankPokemon(): GbPokemon = if (generation == 1) Pk1(japanese) else Pk2(japanese)

    /** The header marker PKHeX writes for [pk] (`GetHeaderIdentifierMark`). */
    fun headerMark(pk: GbPokemon): Int {
        if (generation == 1) {
            val species = (pk as Pk1).speciesInternal
            return if (species == 0) SLOT_EMPTY else species
        }
        val pk2 = pk as Pk2
        val species = pk2.species
        if (species == 0) return SLOT_EMPTY
        if (pk2.isEgg) return SLOT_EGG
        return species
    }

    /** Unpacks the first `min(capacity, count)` slots of a multi-slot list (`Unpack`). */
    fun unpack(src: ByteArray, offset: Int, capacity: Int, isParty: Boolean): MutableList<Slot> {
        val lengthBody = body(isParty)
        var ofsBody = offset + 1 + (capacity + 1)
        var ofsStr1 = ofsBody + (capacity * lengthBody)
        var ofsStr2 = ofsStr1 + (capacity * stringLength)

        val result = ArrayList<Slot>()
        val count = minOf(capacity, src[offset].toInt() and 0xFF)
        for (i in 0 until count) {
            val marker = src[offset + 1 + i].toInt() and 0xFF
            val body = ByteArray(bodyParty)
            src.copyInto(body, 0, ofsBody, ofsBody + lengthBody)
            val ot = src.copyOfRange(ofsStr1, ofsStr1 + stringLength)
            val nick = src.copyOfRange(ofsStr2, ofsStr2 + stringLength)
            val pk = newPokemon(body, ot, nick)
            if (pk is Pk2) pk.isEgg = marker == SLOT_EGG
            result.add(Slot(pk, marker))
            ofsBody += lengthBody
            ofsStr1 += stringLength
            ofsStr2 += stringLength
        }
        return result
    }

    /**
     * Packs the slots into a multi-slot list at [dest]`[offset]` (`MergeSingles`): present slots first, then
     * empty ones (marker 0xFF, zeroed data).
     * @return false if nothing was written because the list is empty and the destination is uninitialised.
     */
    fun mergeSingles(
        slots: List<Slot>,
        dest: ByteArray,
        offset: Int,
        capacity: Int,
        isParty: Boolean,
        isDestInitialized: Boolean = true,
    ): Boolean {
        val present = slots.filter { isPresent(it.marker) }
        val count = present.size
        val listLength = listLength(capacity, isParty)
        if (count == 0 && (!isDestInitialized || isAllZero(dest, offset, listLength))) {
            return false // No need to merge if all empty and dest is not initialized.
        }

        dest[offset] = count.toByte() // ensure written list is valid
        val lengthBody = body(isParty)
        val ofsStart = offset + 1 + (capacity + 1)
        for (i in 0 until capacity) {
            val slot = present.getOrNull(i)
            val index = i // present slots are packed first, empties after: both are simply in order
            dest[offset + 1 + index] = (slot?.marker ?: SLOT_EMPTY).toByte()

            val ofsBody = ofsStart + (index * lengthBody)
            val ofsStr1 = ofsStart + (capacity * lengthBody) + (index * stringLength)
            val ofsStr2 = ofsStr1 + (capacity * stringLength)
            if (slot == null) {
                dest.fill(0, ofsBody, ofsBody + lengthBody)
                dest.fill(0, ofsStr1, ofsStr1 + stringLength)
                dest.fill(0, ofsStr2, ofsStr2 + stringLength)
            } else {
                slot.pk.data.copyInto(dest, ofsBody, 0, lengthBody)
                slot.pk.otTrash.copyInto(dest, ofsStr1, 0, stringLength)
                slot.pk.nickTrash.copyInto(dest, ofsStr2, 0, stringLength)
            }
        }
        dest[offset + 1 + capacity] = SLOT_EMPTY.toByte() // cap off the list
        return true
    }

    companion object {
        const val SLOT_EMPTY = 0xFF
        const val SLOT_EGG = 0xFD

        /** Checks if the slot has content present. */
        fun isPresent(marker: Int): Boolean = marker != 0 && marker != SLOT_EMPTY

        private fun isAllZero(data: ByteArray, offset: Int, length: Int): Boolean {
            for (i in offset until offset + length) if (data[i].toInt() != 0) return false
            return true
        }

        /** True if the list header is sane (`IsValidHeader`). */
        fun isValidHeader(data: ByteArray, offset: Int, capacity: Int): Boolean {
            val count = data[offset].toInt() and 0xFF
            if (count > capacity) return false
            for (i in 0 until capacity) {
                val present = isPresent(data[offset + 1 + i].toInt() and 0xFF)
                if (present != (i < count)) return false
            }
            return true
        }
    }
}

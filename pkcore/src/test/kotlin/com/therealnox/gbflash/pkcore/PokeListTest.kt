package com.therealnox.gbflash.pkcore

import com.therealnox.gbflash.pkcore.gb.GbPokemon
import com.therealnox.gbflash.pkcore.gb.Pk2
import com.therealnox.gbflash.pkcore.gb.PokeListFormat
import com.therealnox.gbflash.pkcore.gb.Slot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Box / party list packing: the layout shared by every Gen 1/2 save. */
class PokeListTest {
    private fun slotOf(format: PokeListFormat, pk: GbPokemon) = Slot(pk, format.headerMark(pk))

    @Test
    fun listLengthsMatchTheCartridgeLayout() {
        // Gen 1 international: box 0x462, party 0x194; Japanese: box 0x566
        assertEquals(1122, PokeListFormat(1, false).listLength(20, party = false))
        assertEquals(404, PokeListFormat(1, false).listLength(6, party = true))
        assertEquals(1382, PokeListFormat(1, true).listLength(30, party = false))
        assertEquals(344, PokeListFormat(1, true).listLength(6, party = true))
        // Gen 2
        assertEquals(1102, PokeListFormat(2, false).listLength(20, party = false))
        assertEquals(428, PokeListFormat(2, false).listLength(6, party = true))
        assertEquals(1352, PokeListFormat(2, true).listLength(30, party = false))
        assertEquals(PokeListFormat(2, true).listLength(6, party = true), 1 + 7 + 6 * 48 + 6 * 6 * 2)
    }

    @Test
    fun packingWritesHeaderBodiesThenOtThenNicknames() {
        val f = PokeListFormat(1, false)
        val a = SyntheticSaves.pk1(species = 25, level = 7, nick = "AAA", ot = "OTA")
        val b = SyntheticSaves.pk1(species = 16, level = 9, nick = "BBB", ot = "OTB")
        val out = ByteArray(f.listLength(20, false) + 4) { 0x77 }
        assertTrue(f.mergeSingles(listOf(slotOf(f, a), slotOf(f, b)), out, 2, 20, false))

        assertEquals(2, out[2].toInt())
        assertEquals(0x54, out[3].toInt() and 0xFF) // Pikachu, internal id
        assertEquals(0x24, out[4].toInt() and 0xFF) // Pidgey
        assertEquals(0xFF, out[5].toInt() and 0xFF) // first empty slot
        assertEquals(0xFF, out[2 + 1 + 20].toInt() and 0xFF) // list terminator
        // bodies start after count + 21 markers, box bodies are 33 bytes
        val bodies = 2 + 1 + 21
        assertArrayEquals(a.data.copyOf(33), out.copyOfRange(bodies, bodies + 33))
        assertArrayEquals(b.data.copyOf(33), out.copyOfRange(bodies + 33, bodies + 66))
        val ots = bodies + 20 * 33
        assertArrayEquals(a.otTrash, out.copyOfRange(ots, ots + 11))
        assertArrayEquals(b.otTrash, out.copyOfRange(ots + 11, ots + 22))
        val nicks = ots + 20 * 11
        assertArrayEquals(b.nickTrash, out.copyOfRange(nicks + 11, nicks + 22))
        // untouched bytes before and after the list
        assertEquals(0x77, out[0].toInt())
        assertEquals(0x77, out[out.size - 1].toInt())
    }

    @Test
    fun unpackRestoresWhatWasPacked() {
        for (gen in 1..2) for (jp in listOf(false, true)) {
            val f = PokeListFormat(gen, jp)
            val capacity = if (jp) 30 else 20
            val mons = (1..capacity).map { n ->
                val pk = if (gen == 1) SyntheticSaves.pk1(species = n, level = 3 + n % 50, japanese = jp)
                else SyntheticSaves.pk2(species = n, level = 3 + n % 50, japanese = jp)
                pk.data.fill(0, if (gen == 1) 33 else 32) // box form has no party data
                pk
            }
            val buffer = ByteArray(f.listLength(capacity, false))
            assertTrue(f.mergeSingles(mons.map { slotOf(f, it) }, buffer, 0, capacity, false))
            val back = f.unpack(buffer, 0, capacity, false)
            assertEquals(capacity, back.size)
            for (i in mons.indices) assertTrue("gen $gen jp $jp slot $i", mons[i].contentEquals(back[i].pk))
        }
    }

    @Test
    fun emptySlotsAreZeroedAndHolesAreCompacted() {
        val f = PokeListFormat(2, false)
        val a = SyntheticSaves.pk2(species = 1)
        val b = SyntheticSaves.pk2(species = 4)
        val buffer = ByteArray(f.listLength(20, false)) { 0x55 } // leftover garbage
        // A zero marker is a deleted slot: treated like an empty one and compacted away.
        assertTrue(f.mergeSingles(listOf(slotOf(f, a), Slot(SyntheticSaves.pk2(species = 7), 0), slotOf(f, b)), buffer, 0, 20, false))
        assertEquals(2, buffer[0].toInt())
        assertEquals(1, buffer[1].toInt())
        assertEquals(4, buffer[2].toInt())
        assertEquals(0xFF, buffer[3].toInt() and 0xFF)
        val bodies = 1 + 21
        assertTrue((bodies + 2 * 32 until bodies + 20 * 32).all { buffer[it].toInt() == 0 }) // old garbage cleared
        assertEquals(2, f.unpack(buffer, 0, 20, false).size)
    }

    @Test
    fun anEmptyListIsNotWrittenToAnUninitialisedDestination() {
        val f1 = PokeListFormat(1, false)
        val zero = ByteArray(f1.listLength(20, false))
        assertFalse(f1.mergeSingles(emptyList(), zero, 0, 20, false))
        assertTrue(zero.all { it.toInt() == 0 })
        // Gen 1 additionally never writes when the banks are flagged uninitialised
        val dirty = ByteArray(zero.size) { 9 }
        assertFalse(f1.mergeSingles(emptyList(), dirty, 0, 20, false, isDestInitialized = false))
        assertTrue(dirty.all { it.toInt() == 9 })
        // ... but writes an empty (count 0, 0xFF terminated) list over existing data when initialised
        assertTrue(f1.mergeSingles(emptyList(), dirty, 0, 20, false, isDestInitialized = true))
        assertEquals(0, dirty[0].toInt())
        assertEquals(0xFF, dirty[1].toInt() and 0xFF)
        assertEquals(0xFF, dirty[21].toInt() and 0xFF)
    }

    @Test
    fun gen2EggsAreMarkedInTheHeader() {
        val f = PokeListFormat(2, false)
        val egg = SyntheticSaves.pk2(species = 175) // Togepi
        egg.isEgg = true
        assertEquals(0xFD, f.headerMark(egg))
        val buffer = ByteArray(f.listLength(6, true))
        f.mergeSingles(listOf(slotOf(f, egg), slotOf(f, SyntheticSaves.pk2(species = 25))), buffer, 0, 6, true)
        assertEquals(0xFD, buffer[1].toInt() and 0xFF)
        assertEquals(25, buffer[2].toInt())
        val back = f.unpack(buffer, 0, 6, true)
        assertTrue((back[0].pk as Pk2).isEgg)
        assertFalse((back[1].pk as Pk2).isEgg)
        assertEquals(175, back[0].pk.species) // the real species stays in the body
    }

    @Test
    fun headerValidation() {
        val d = ByteArray(30)
        d[0] = 2; d[1] = 1; d[2] = 2; d[3] = 0xFF.toByte()
        assertTrue(PokeListFormat.isValidHeader(d, 0, 6))
        d[0] = 3
        assertFalse(PokeListFormat.isValidHeader(d, 0, 6))
        d[0] = 7
        assertFalse(PokeListFormat.isValidHeader(d, 0, 6))
        assertTrue(PokeListFormat.isPresent(1) && !PokeListFormat.isPresent(0) && !PokeListFormat.isPresent(0xFF))
        assertTrue(PokeListFormat.isPresent(0xFD))
    }
}

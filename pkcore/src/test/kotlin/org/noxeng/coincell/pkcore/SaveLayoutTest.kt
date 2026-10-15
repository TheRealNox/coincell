package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.gb.GbPokemon
import org.noxeng.coincell.pkcore.gb.GbSave
import org.noxeng.coincell.pkcore.gb.Sav1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where lists end up in the raw bytes (incl. Japanese layouts), checksums and the Gen 2 backup copies. */
class SaveLayoutTest {
    private fun ByteArray.u8(ofs: Int) = this[ofs].toInt() and 0xFF

    private data class Variant(val game: Game, val region: Region, val size: Int) {
        val gen get() = game.generation
        val jp get() = region == Region.JAPANESE
        val yellow get() = game == Game.YELLOW
        val partyOffset: Int
            get() = when (game) {
                Game.RED_BLUE, Game.YELLOW -> if (jp) 0x2ED5 else 0x2F2C
                Game.GOLD_SILVER -> if (jp) 0x283E else 0x288A
                Game.CRYSTAL -> if (jp) 0x281A else 0x2865
            }
        val stringLength get() = if (jp) 6 else 11
        val partyBody get() = if (gen == 1) 44 else 48
        val boxBody get() = if (gen == 1) 33 else 32
        val boxCapacity get() = if (jp) 30 else 20
        val boxCount get() = if (gen == 1) (if (jp) 8 else 12) else (if (jp) 9 else 14)
        val boxListLength get() = 2 + boxCapacity * (1 + boxBody + 2 * stringLength)

        /** Raw offset of a box list. */
        fun boxOffset(box: Int): Int = if (gen == 1) {
            if (box < boxCount / 2) 0x4000 + box * boxListLength else 0x6000 + (box - boxCount / 2) * boxListLength
        } else {
            val split = if (jp) 6 else 7
            if (box < split) 0x4000 + box * (boxListLength + 2) else 0x6000 + (box - split) * (boxListLength + 2)
        }
    }

    private val variants = SyntheticSaves.allVariants.map { Variant(it.first, it.second, it.third) }

    private fun load(v: Variant): SaveFile = SaveFile.load(SyntheticSaves.blank(v.game, v.region, v.size, v.yellow))

    @Test
    fun partyListLandsAtTheDocumentedOffset() {
        for (v in variants) {
            val save = load(v)
            val pk = SyntheticSaves.pokemon(save, species = 25, level = 12, nick = if (v.jp) "ピカ" else "SPARK")
            save.setPokemon(SlotRef.Party(0), pk)
            val out = save.export()
            val p = v.partyOffset
            val label = "$v"
            assertEquals(label, 1, out.u8(p))
            val marker = if (v.gen == 1) 0x54 else 25
            assertEquals(label, marker, out.u8(p + 1))
            assertEquals(label, 0xFF, out.u8(p + 2))
            assertEquals(label, 0xFF, out.u8(p + 7)) // terminator after the 6 markers
            val bodies = p + 8
            val saved = save.getPokemon(SlotRef.Party(0)) as GbPokemon
            assertArrayEquals(label, saved.data, out.copyOfRange(bodies, bodies + v.partyBody))
            val ots = bodies + 6 * v.partyBody
            assertArrayEquals(label, saved.otTrash, out.copyOfRange(ots, ots + v.stringLength))
            val nicks = ots + 6 * v.stringLength
            assertArrayEquals(label, saved.nickTrash, out.copyOfRange(nicks, nicks + v.stringLength))

            val reloaded = SaveFile.load(out)
            assertEquals(label, 1, reloaded.party.size)
            assertEquals(label, 25, reloaded.party[0].species)
            assertEquals(label, 12, reloaded.party[0].level)
            assertEquals(label, if (v.jp) "ピカ" else "SPARK", reloaded.party[0].nickname)
            assertTrue(label, reloaded.checksumsValid)
        }
    }

    @Test
    fun boxListsLandAtTheDocumentedOffsets() {
        for (v in variants) {
            val save = load(v)
            val lastBox = v.boxCount - 1
            val boxes = listOf(0, v.boxCount / 2 - 1, v.boxCount / 2, lastBox).distinct()
            for (b in boxes) {
                save.setPokemon(SlotRef.Box(b, 0), SyntheticSaves.pokemon(save, species = 10 + b, level = 5 + b, nick = null))
                save.setPokemon(SlotRef.Box(b, 1), SyntheticSaves.pokemon(save, species = 20 + b, level = 9, nick = if (v.jp) "ボックス" else "BOX$b"))
            }
            val out = save.export()
            for (b in boxes) {
                val o = v.boxOffset(b)
                val label = "$v box $b"
                assertEquals(label, 2, out.u8(o))
                val first = if (v.gen == 1) org.noxeng.coincell.pkcore.data.SpeciesConverter.getInternal1(10 + b) else 10 + b
                assertEquals(label, first, out.u8(o + 1))
                assertEquals(label, 0xFF, out.u8(o + 3))
                assertEquals(label, 0xFF, out.u8(o + 1 + v.boxCapacity)) // terminator after `capacity` markers
                // second body starts one stored-size after the first
                val bodies = o + 2 + v.boxCapacity
                val second = save.getPokemon(SlotRef.Box(b, 1)) as GbPokemon
                assertArrayEquals(label, second.data.copyOf(v.boxBody), out.copyOfRange(bodies + v.boxBody, bodies + 2 * v.boxBody))
                val nicks = bodies + v.boxCapacity * (v.boxBody + v.stringLength)
                assertArrayEquals(label, second.nickTrash, out.copyOfRange(nicks + v.stringLength, nicks + 2 * v.stringLength))
            }
            val reloaded = SaveFile.load(out)
            for (b in boxes) {
                val box = reloaded.boxes[b]
                assertEquals("$v box $b", listOf(10 + b, 20 + b), box.pokemon.map { it.species })
                assertEquals("$v box $b", if (v.jp) "ボックス" else "BOX$b", box.pokemon[1].nickname)
            }
            assertTrue("$v", reloaded.checksumsValid)
        }
    }

    @Test
    fun gen1CurrentBoxIsMirroredAndBankChecksumsAreRefreshed() {
        for (jp in listOf(false, true)) {
            val v = Variant(Game.RED_BLUE, if (jp) Region.JAPANESE else Region.INTERNATIONAL, 0x8000)
            val save = load(v)
            val currentBoxIndexOfs = if (jp) 0x2842 else 0x284C
            val currentBoxOfs = if (jp) 0x302D else 0x30C0
            save.setPokemon(SlotRef.Box(0, 0), SyntheticSaves.pokemon(save, 25, 20))
            var out = save.export()
            // box 0 is the current box and nothing else has content: the flag stays unset, the bank copy and the
            // current-box copy are identical.
            assertEquals(0, out.u8(currentBoxIndexOfs))
            val len = v.boxListLength
            assertArrayEquals(out.copyOfRange(0x4000, 0x4000 + len), out.copyOfRange(currentBoxOfs, currentBoxOfs + len))

            // A box other than the current one makes the banks "initialised" (bit 7).
            save.setPokemon(SlotRef.Box(v.boxCount - 1, 0), SyntheticSaves.pokemon(save, 16, 30))
            out = save.export()
            assertEquals(0x80, out.u8(currentBoxIndexOfs))
            val reloaded = SaveFile.load(out)
            assertEquals(listOf(25), reloaded.boxes[0].pokemon.map { it.species })
            assertEquals(listOf(16), reloaded.boxes[v.boxCount - 1].pokemon.map { it.species })
            assertTrue((reloaded as Sav1).bankChecksumsValid())
            assertTrue(reloaded.checksumsValid)
        }
    }

    @Test
    fun gen1BankChecksumBytesSitAfterEachBank() {
        val v = Variant(Game.RED_BLUE, Region.INTERNATIONAL, 0x8000)
        val save = load(v)
        save.setPokemon(SlotRef.Box(0, 0), SyntheticSaves.pokemon(save, 25, 20))
        save.setPokemon(SlotRef.Box(7, 0), SyntheticSaves.pokemon(save, 25, 20))
        val out = save.export()
        for (bank in 0..1) {
            val start = if (bank == 0) 0x4000 else 0x6000
            val ofs = start + 6 * 1122 // 0x5A4C / 0x7A4C
            assertEquals(if (bank == 0) 0x5A4C else 0x7A4C, ofs)
            fun sum(a: Int, b: Int) = (out.sliceArray(a until b).sumOf { it.toInt() and 0xFF } and 0xFF).inv() and 0xFF
            assertEquals(sum(start, ofs), out.u8(ofs))
            for (i in 0 until 6) assertEquals(sum(start + i * 1122, start + (i + 1) * 1122), out.u8(ofs + 1 + i))
        }
    }

    @Test
    fun gen1MainChecksumCoversTrainerThroughCurrentBox() {
        for (jp in listOf(false, true)) {
            val v = Variant(Game.RED_BLUE, if (jp) Region.JAPANESE else Region.INTERNATIONAL, 0x8000)
            val save = load(v)
            save.updateTrainer(save.trainer.copy(id = 0x1234))
            val out = save.export()
            val end = if (jp) 0x3594 else 0x3523
            var sum = 0
            for (i in 0x2598 until end) sum += out.u8(i)
            assertEquals(((sum and 0xFF).inv()) and 0xFF, out.u8(end))
            assertTrue(SaveFile.load(out).checksumsValid)
        }
    }

    @Test
    fun gen2ChecksumIsAMirroredSixteenBitSum() {
        val cases = listOf(
            Variant(Game.GOLD_SILVER, Region.INTERNATIONAL, 0x8000) to Triple(0x2D68, 0x2D69, 0x7E6D),
            Variant(Game.CRYSTAL, Region.INTERNATIONAL, 0x8000) to Triple(0x2B82, 0x2D0D, 0x1F0D),
            Variant(Game.GOLD_SILVER, Region.JAPANESE, 0x8000) to Triple(0x2C8B, 0x2D0D, 0x7F0D),
            Variant(Game.CRYSTAL, Region.JAPANESE, 0x8000) to Triple(0x2AE2, 0x2D0D, 0x7F0D),
        )
        for ((v, ofs) in cases) {
            val save = load(v)
            save.updateTrainer(save.trainer.copy(money = 4321))
            val out = save.export()
            var sum = 0
            for (i in 0x2009..ofs.first) sum += out.u8(i)
            sum = sum and 0xFFFF
            assertEquals("$v", sum, out.u8(ofs.second) or (out.u8(ofs.second + 1) shl 8))
            assertEquals("$v", sum, out.u8(ofs.third) or (out.u8(ofs.third + 1) shl 8))
            assertTrue("$v", SaveFile.load(out).checksumsValid)
        }
    }

    @Test
    fun gen2BackupCopiesAreRefreshedFromThePrimaryBlock() {
        fun same(out: ByteArray, dest: Int, src: Int, length: Int) {
            assertArrayEquals("copy $src -> $dest", out.copyOfRange(src, src + length), out.copyOfRange(dest, dest + length))
        }
        // Crystal international: one block
        var save = SaveFile.load(SyntheticSaves.blank(Game.CRYSTAL))
        save.updateTrainer(save.trainer.copy(money = 777, coins = 55))
        same(save.export(), 0x1209, 0x2009, 0xB7A)
        // Gold/Silver international: five ranges
        save = SaveFile.load(SyntheticSaves.blank(Game.GOLD_SILVER))
        save.updateTrainer(save.trainer.copy(money = 777, coins = 55))
        var out = save.export()
        same(out, 0x15C7, 0x2009, 0x222F - 0x2009)
        same(out, 0x3D69, 0x222F, 0x23D9 - 0x222F)
        same(out, 0x0C6B, 0x23D9, 0x2856 - 0x23D9)
        same(out, 0x7E39, 0x2856, 0x288A - 0x2856)
        same(out, 0x10E8, 0x288A, 0x2D69 - 0x288A)
        // Japanese: Crystal 0xADA / Gold-Silver 0xC83 bytes to 0x7209
        save = SaveFile.load(SyntheticSaves.blank(Game.CRYSTAL, Region.JAPANESE))
        save.updateTrainer(save.trainer.copy(money = 777))
        same(save.export(), 0x7209, 0x2009, 0xADA)
        save = SaveFile.load(SyntheticSaves.blank(Game.GOLD_SILVER, Region.JAPANESE))
        save.updateTrainer(save.trainer.copy(money = 777))
        out = save.export()
        same(out, 0x7209, 0x2009, 0xC83)
    }

    @Test
    fun anUnmodifiedSaveExportsItsOriginalBytes() {
        for (v in variants) {
            val bytes = SyntheticSaves.blank(v.game, v.region, v.size, v.yellow)
            bytes[0x100] = 0x42 // arbitrary junk outside everything the editor knows
            val save = SaveFile.load(bytes)
            assertFalse(save.isModified)
            assertArrayEquals("$v", bytes, save.export())
        }
    }

    @Test
    fun anEditedSaveIsAFixedPointOfThePkhexRewrite() {
        for (v in variants) {
            val save = load(v)
            save.setPokemon(SlotRef.Party(0), SyntheticSaves.pokemon(save, 25, 20, "ZAP"))
            save.setPokemon(SlotRef.Box(1, 0), SyntheticSaves.pokemon(save, 133, 11))
            save.setPokemon(SlotRef.Box(v.boxCount - 1, 0), SyntheticSaves.pokemon(save, 52, 31))
            val first = save.export()
            val again = (SaveFile.load(first) as GbSave).exportLikePkhex()
            assertArrayEquals("$v", first, again)
        }
    }

    @Test
    fun exportKeepsTheOriginalLength() {
        for (v in variants) assertEquals(v.size, load(v).export().size)
    }
}

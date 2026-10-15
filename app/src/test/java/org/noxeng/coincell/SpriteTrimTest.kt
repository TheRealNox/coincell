package org.noxeng.coincell

import org.noxeng.coincell.sprites.SpriteTrim
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpriteTrimTest {
    private fun image(w: Int, h: Int, vararg opaque: Pair<Int, Int>) = IntArray(w * h).also { px ->
        opaque.forEach { (x, y) -> px[y * w + x] = 0xFF00FF00.toInt() }
    }

    @Test
    fun boundsCoverOpaquePixelsPlusPadding() {
        // A 68x56 box sprite with the Pokemon drawn between (20,30) and (40,50).
        val px = image(68, 56, 20 to 30, 40 to 50, 30 to 40)
        assertArrayEquals(intArrayOf(19, 29, 42, 52), SpriteTrim.opaqueBounds(px, 68, 56))
    }

    @Test
    fun paddingIsClampedToTheImage() {
        val px = image(10, 8, 0 to 0, 9 to 7)
        assertArrayEquals(intArrayOf(0, 0, 10, 8), SpriteTrim.opaqueBounds(px, 10, 8))
    }

    @Test
    fun semiTransparentPixelsCountAsContent() {
        val px = IntArray(4 * 4).also { it[1 * 4 + 2] = 0x01000000 }
        assertArrayEquals(intArrayOf(2, 1, 3, 2), SpriteTrim.opaqueBounds(px, 4, 4, padding = 0))
    }

    @Test
    fun fullyTransparentImageHasNoBounds() {
        assertNull(SpriteTrim.opaqueBounds(IntArray(68 * 56), 68, 56))
    }
}

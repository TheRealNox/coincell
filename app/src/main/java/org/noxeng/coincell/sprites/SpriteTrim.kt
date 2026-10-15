package org.noxeng.coincell.sprites

/**
 * Box sprites are 68x56 canvases with the Pokemon in a small part of them; item icons have transparent padding too.
 * Trimming to the opaque pixels lets a sprite fill the space it is drawn in instead of being shrunk with its padding.
 */
object SpriteTrim {
    /**
     * Bounds `[left, top, right, bottom)` of the pixels with non-zero alpha in [argb] (row-major, [width] x [height]),
     * grown by [padding] pixels and clamped to the image; null if every pixel is fully transparent.
     */
    fun opaqueBounds(argb: IntArray, width: Int, height: Int, padding: Int = 1): IntArray? {
        require(argb.size >= width * height) { "Pixel buffer smaller than $width x $height" }
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (argb[row + x] ushr 24 != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return null
        return intArrayOf(
            maxOf(0, left - padding),
            maxOf(0, top - padding),
            minOf(width, right + 1 + padding),
            minOf(height, bottom + 1 + padding),
        )
    }
}

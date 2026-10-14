package com.therealnox.gbflash.sprites

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException

/**
 * The app's view of the optional sprite pack: resolves sprites through [SpriteResolver] and decodes them once into
 * an LRU cache. Every function returns null when the pack or the file is missing; the UI then draws initials / text.
 */
class SpriteStore(private val resolver: SpriteResolver) {
    constructor(assets: AssetManager) : this(
        SpriteResolver(
            SpriteIndex.load(assetSource(assets)),
            assetSource(assets),
        ),
    )

    val available: Boolean get() = resolver.available

    private val cache = LruCache<String, Holder>(CACHE_SIZE)

    private class Holder(val bitmap: ImageBitmap?)

    fun pokemon(key: PokemonSpriteKey): ImageBitmap? = cached("p:$key") { resolver.pokemon(key.species, key.shiny, key.form, key.female, key.egg) }

    fun item(generation: Int, itemId: Int, dark: Boolean): ImageBitmap? =
        cached("i:$generation:$itemId:$dark") { resolver.item(generation, itemId, dark) }

    private fun cached(key: String, load: () -> ResolvedSprite?): ImageBitmap? {
        cache.get(key)?.let { return it.bitmap }
        val bitmap = try {
            load()?.let { BitmapFactory.decodeByteArray(it.bytes, 0, it.bytes.size) }?.let(::trimmed)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
        cache.put(key, Holder(bitmap))
        return bitmap
    }

    /** [bitmap] cropped to its opaque pixels (see [SpriteTrim]); unchanged when it is fully transparent or already tight. */
    private fun trimmed(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val b = SpriteTrim.opaqueBounds(pixels, w, h) ?: return bitmap
        if (b[0] == 0 && b[1] == 0 && b[2] == w && b[3] == h) return bitmap
        return Bitmap.createBitmap(bitmap, b[0], b[1], b[2] - b[0], b[3] - b[1])
    }

    private companion object {
        const val CACHE_SIZE = 600

        fun assetSource(assets: AssetManager) = AssetSource { path ->
            try {
                assets.open(path)
            } catch (_: IOException) {
                null
            }
        }
    }
}

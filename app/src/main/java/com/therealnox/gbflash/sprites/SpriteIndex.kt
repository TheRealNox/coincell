package com.therealnox.gbflash.sprites

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where asset files come from: the Android AssetManager in the app, a map in the tests. Returns null for a file that
 * is not there. The sprite pack (`pkassets/`) is private-use artwork that must be removable, so nothing may assume it exists.
 */
fun interface AssetSource {
    fun open(path: String): InputStream?
}

/**
 * Lookup rules of the optional sprite pack (pkassets/README.md): turns "this Pokemon / this item" into asset paths.
 * Built from `sprites/index.json`, a slim copy of the pack's `manifest.json` that the Gradle build generates when
 * `pkassets/` exists. When the pack was removed the index is [EMPTY] and every lookup returns no candidates.
 */
class SpriteIndex private constructor(
    private val entries: Map<String, String>,
    private val gen1Items: List<Int>,
    private val gen2Items: List<Int>,
) {
    /** False when the pack is absent: callers then show text and initials only. */
    val available: Boolean get() = entries.isNotEmpty()

    private fun path(key: String): String? = entries[key]

    /**
     * Asset paths to try, best first, for a Gen 1/2 Pokemon sprite (PKHeX rules): an egg is the egg sprite; a shiny
     * tries the shiny set first; a female tries the `-female` file when the pack has one; Unown (and other forms)
     * use the numeric form alias; then the default form, then the "unknown" sprite.
     */
    fun pokemon(species: Int, shiny: Boolean, form: Int, female: Boolean, egg: Boolean): List<String> {
        if (!available) return emptyList()
        if (egg) return listOfNotNull(path("pokemon/box/egg"))
        val sets = if (shiny) listOf("box_shiny", "box") else listOf("box")
        val out = ArrayList<String>()
        for (set in sets) {
            if (female) path("pokemon/$set/$species-female")?.let { out += it }
            if (form > 0) path("pokemon/$set/$species-$form")?.let { out += it }
            path("pokemon/$set/$species")?.let { out += it }
        }
        path("pokemon/box/unknown")?.let { out += it }
        return out.distinct()
    }

    /** Paths for a bag or held item (Gen 1 bag ids and Gen 2 ids converted to the pack's ids); outline art first on dark backgrounds. */
    fun item(generation: Int, itemId: Int, dark: Boolean): List<String> {
        if (!available) return emptyList()
        val map = if (generation == 1) gen1Items else gen2Items
        val modern = map.getOrNull(itemId) ?: 0
        if (modern <= 0) return emptyList()
        val keys = if (dark) listOf("item_outline/$modern", "item/$modern") else listOf("item/$modern")
        return keys.mapNotNull { path(it) }
    }

    companion object {
        const val INDEX_FILE = "sprites/index.json"

        val EMPTY = SpriteIndex(emptyMap(), emptyList(), emptyList())

        /** Reads [INDEX_FILE]; a missing or unreadable index is [EMPTY], never an error. */
        fun load(source: AssetSource): SpriteIndex = try {
            source.open(INDEX_FILE)?.use { parse(it.readBytes().toString(Charsets.UTF_8)) } ?: EMPTY
        } catch (_: Exception) {
            EMPTY
        }

        fun parse(json: String): SpriteIndex = try {
            val root = Json.parseToJsonElement(json).jsonObject
            val entries = root["entries"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            val maps = root["itemMaps"] as? JsonObject
            fun ints(name: String): List<Int> = (maps?.get(name) as? JsonArray)?.map { it.jsonPrimitive.int }.orEmpty()
            SpriteIndex(entries, ints("gen1"), ints("gen2"))
        } catch (_: Exception) {
            EMPTY
        }
    }
}

/**
 * Reads the first existing file among candidate paths. A path that is in the index but whose file is gone (a
 * partly deleted pack) just moves on to the next candidate; null means "draw text instead".
 */
class SpriteResolver(val index: SpriteIndex, private val source: AssetSource) {
    val available: Boolean get() = index.available

    fun pokemon(species: Int, shiny: Boolean, form: Int, female: Boolean, egg: Boolean): ResolvedSprite? =
        firstReadable(index.pokemon(species, shiny, form, female, egg))

    fun item(generation: Int, itemId: Int, dark: Boolean): ResolvedSprite? = firstReadable(index.item(generation, itemId, dark))

    private fun firstReadable(paths: List<String>): ResolvedSprite? {
        for (p in paths) {
            val bytes = try {
                source.open(p)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
            if (bytes != null && bytes.isNotEmpty()) return ResolvedSprite(p, bytes)
        }
        return null
    }
}

class ResolvedSprite(val path: String, val bytes: ByteArray)

/** What decides a Pokemon's box sprite: derived from the summary by [com.therealnox.gbflash.sprites.SpriteRules]. */
data class PokemonSpriteKey(val species: Int, val shiny: Boolean, val form: Int, val female: Boolean, val egg: Boolean)

object SpriteRules {
    const val UNOWN = 201

    /** Unown's letter (0 = A ... 25 = Z) from the DVs, exactly as the Gen 2 games and PKHeX derive it. */
    fun unownForm(dvs: com.therealnox.gbflash.pkcore.Dvs): Int {
        var form = 0
        form = form or ((dvs.attack and 0x6) shl 5)
        form = form or ((dvs.defense and 0x6) shl 3)
        form = form or ((dvs.speed and 0x6) shl 1)
        form = form or ((dvs.special and 0x6) shr 1)
        return form / 10
    }

    fun keyOf(p: com.therealnox.gbflash.pkcore.PokemonSummary): PokemonSpriteKey = PokemonSpriteKey(
        species = p.species,
        shiny = p.isShiny,
        form = if (p.species == UNOWN) unownForm(p.dvs) else 0,
        female = p.gender == com.therealnox.gbflash.pkcore.Gender.FEMALE,
        egg = p.isEgg,
    )

    /** "ABC" for Pokemon whose sprite is missing: the first three letters of the name. */
    fun initials(name: String): String = name.filter { it.isLetterOrDigit() }.take(3).uppercase().ifEmpty { "?" }
}

package org.noxeng.coincell.library

import org.noxeng.coincell.editor.PokemonSaveInfo
import org.noxeng.coincell.editor.PokemonSaveKind
import org.noxeng.coincell.editor.PokemonSaveProbe
import org.noxeng.coincell.save.SaveImport

/** What the import dialog pre-fills for a file imported without its cartridge. */
data class ImportGuess(val platform: DisplayPlatform?, val name: String)

object ImportGuesses {
    /**
     * A Generation 1-3 Pokemon save names itself ("Pokemon Crystal (French)") and its platform (Gen 1 is GB, Gen 2
     * GBC, Gen 3 GBA). Anything else gets the file name without its extension and no platform: the user picks it, since
     * save sizes do not tell GB, GBC and GBA apart.
     */
    fun of(fileName: String, bytes: ByteArray): ImportGuess {
        // A clock footer (Crystal, Gold, Silver; FlashGBX's GBA footer) is cut off first, like the import itself does.
        val gb = (SaveImport.parseLoose(bytes, gba = false) as? SaveImport.Result.Ok)?.save ?: bytes
        val gba = (SaveImport.parseLoose(bytes, gba = true) as? SaveImport.Result.Ok)?.save ?: bytes
        val info = (PokemonSaveProbe.probeBytes(gb) as? PokemonSaveKind.Editable)?.info
            ?: (PokemonSaveProbe.probeBytes(gba) as? PokemonSaveKind.Editable)?.info
        if (info != null) {
            val platform = when (info.generation) {
                1 -> DisplayPlatform.GB
                2 -> DisplayPlatform.GBC
                else -> DisplayPlatform.GBA
            }
            return ImportGuess(platform, "Pokemon ${PokemonSaveInfo.gameName(info.game)} (${PokemonSaveInfo.languageName(info.language)})")
        }
        val base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        return ImportGuess(null, GameInfo.normalizeName(base) ?: "Imported save")
    }
}

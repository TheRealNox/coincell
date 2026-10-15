package org.noxeng.coincell.pkcore

import org.noxeng.coincell.pkcore.gb.GbSave
import org.noxeng.coincell.pkcore.gb.Pk1
import org.noxeng.coincell.pkcore.gb.Pk2
import org.noxeng.coincell.pkcore.gb.GbPokemon

/** Builds empty-but-valid saves from scratch, so the tests need no real save file. */
object SyntheticSaves {
    /** Offsets of the two lists PKHeX's detection looks at (party, current box) for each variant. */
    private fun listOffsets(game: Game, region: Region): Pair<Int, Int> = when (game) {
        Game.RED_BLUE, Game.YELLOW -> if (region == Region.JAPANESE) 0x2ED5 to 0x302D else 0x2F2C to 0x30C0
        Game.GOLD_SILVER -> if (region == Region.JAPANESE) 0x2D10 to 0x283E else 0x288A to 0x2D6C
        Game.CRYSTAL -> if (region == Region.JAPANESE) 0x2D10 to 0x281A else 0x2865 to 0x2D10
        else -> error("Generation 1/2 only")
    }

    /**
     * An empty save of the requested kind with valid checksums. Gen 2 Japanese saves default to 0x8000 bytes
     * (the cartridge size), pass [size] = 0x10000 for the larger virtual-console size.
     */
    fun blank(game: Game, region: Region = Region.INTERNATIONAL, size: Int = 0x8000, yellow: Boolean = false): ByteArray {
        val data = ByteArray(size)
        val (a, b) = listOffsets(game, region)
        for (ofs in listOf(a, b)) {
            data[ofs] = 0 // count
            data[ofs + 1] = 0xFF.toByte() // species list terminator
        }
        if (yellow) {
            // Starter = Pikachu (internal id 0x54) tells Yellow apart from Red/Blue.
            data[if (region == Region.JAPANESE) 0x29B9 else 0x29C3] = 0x54
        }
        return fixChecksums(data)
    }

    /** Runs the PKHeX-equivalent finalisation (checksums, backups) on [data]. */
    fun fixChecksums(data: ByteArray): ByteArray = (SaveFile.load(data) as GbSave).exportLikePkhex()

    /** A Gen 1 Pokemon with sensible defaults. */
    fun pk1(species: Int = 25, level: Int = 5, nick: String? = null, ot: String = "RED", tid: Int = 12345, japanese: Boolean = false): Pk1 {
        val pk = Pk1(japanese)
        pk.species = species
        pk.level = level
        pk.trainerId = tid
        pk.otName = ot
        pk.dv16 = 0xFAAA
        pk.setMove(0, 33)
        pk.setMovePp(0, 35)
        pk.setNotNicknamed(2)
        if (nick != null) pk.nickname = nick
        pk.resetPartyStats()
        return pk
    }

    /** A Gen 2 Pokemon with sensible defaults. */
    fun pk2(species: Int = 25, level: Int = 5, nick: String? = null, ot: String = "GOLD", tid: Int = 54321, japanese: Boolean = false): Pk2 {
        val pk = Pk2(japanese)
        pk.species = species
        pk.level = level
        pk.trainerId = tid
        pk.otName = ot
        pk.dv16 = 0xFAAA
        pk.friendship = 70
        pk.setMove(0, 33)
        pk.setMovePp(0, 35)
        pk.setNotNicknamed(2)
        if (nick != null) pk.nickname = nick
        pk.resetPartyStats()
        return pk
    }

    fun pokemon(save: SaveFile, species: Int = 25, level: Int = 5, nick: String? = null): GbPokemon =
        if (save.generation == 1) pk1(species, level, nick, japanese = save.region == Region.JAPANESE)
        else pk2(species, level, nick, japanese = save.region == Region.JAPANESE)

    val allVariants: List<Triple<Game, Region, Int>> = listOf(
        Triple(Game.RED_BLUE, Region.INTERNATIONAL, 0x8000),
        Triple(Game.YELLOW, Region.INTERNATIONAL, 0x8000),
        Triple(Game.RED_BLUE, Region.JAPANESE, 0x8000),
        Triple(Game.YELLOW, Region.JAPANESE, 0x8000),
        Triple(Game.GOLD_SILVER, Region.INTERNATIONAL, 0x8000),
        Triple(Game.CRYSTAL, Region.INTERNATIONAL, 0x8000),
        Triple(Game.GOLD_SILVER, Region.JAPANESE, 0x8000),
        Triple(Game.CRYSTAL, Region.JAPANESE, 0x8000),
        Triple(Game.GOLD_SILVER, Region.JAPANESE, 0x10000),
        Triple(Game.CRYSTAL, Region.JAPANESE, 0x10000),
    )
}

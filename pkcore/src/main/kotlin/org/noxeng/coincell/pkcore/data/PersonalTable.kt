// Ported from PKHeX.Core/PersonalInfo/Info/PersonalInfo1.cs, PersonalInfo2.cs, Table/PersonalTable1.cs,
// Table/PersonalTable2.cs (PKHeX, GPL-3.0, commit 641c26b). Data: Resources/byte/personal/personal_{rb,y,gs,c}.
package org.noxeng.coincell.pkcore.data

/** Loads the packaged binary/text resources of this module. */
internal object Resources {
    private const val BASE = "/org/noxeng/coincell/pkcore/data/"

    fun bytes(name: String): ByteArray =
        (Resources::class.java.getResourceAsStream(BASE + name) ?: error("Missing resource $name")).use { it.readBytes() }

    /** UTF-8 text split on LF (CR is tolerated); the trailing newline does not produce an extra element. */
    fun lines(name: String): List<String> {
        val text = String(bytes(name), Charsets.UTF_8).replace("\r\n", "\n")
        return text.removeSuffix("\n").split('\n')
    }
}

/** Base stats and misc. species data (one record of the personal table). */
public class PersonalInfo internal constructor(internal val raw: ByteArray, public val generation: Int) {
    private fun u8(ofs: Int): Int = raw[ofs].toInt() and 0xFF

    /** Gender ratio byte (254 = female only, 255 = genderless, 0 = male only, else threshold). */
    public val gender: Int get() = if (generation == 1) u8(0x00) else u8(0x0D)
    public val hp: Int get() = u8(0x01)
    public val atk: Int get() = u8(0x02)
    public val def: Int get() = u8(0x03)
    public val spe: Int get() = u8(0x04)

    /** Special Attack. Generation 1 has a single Special stat. */
    public val spa: Int get() = u8(0x05)

    /** Special Defense. Generation 1 has a single Special stat, so this equals [spa]. */
    public val spd: Int get() = if (generation == 1) u8(0x05) else u8(0x06)
    public val type1: Int get() = if (generation == 1) u8(0x06) else u8(0x07)
    public val type2: Int get() = if (generation == 1) u8(0x07) else u8(0x08)
    public val catchRate: Int get() = if (generation == 1) u8(0x08) else u8(0x09)
    public val baseExp: Int get() = if (generation == 1) u8(0x09) else u8(0x0A)
    public val expGrowth: Int get() = if (generation == 1) u8(0x13) else u8(0x16)
    public val baseFriendship: Int get() = if (generation == 1) 0 else 70

    public companion object {
        public const val RATIO_MAGIC_GENDERLESS: Int = 255
        public const val RATIO_MAGIC_FEMALE: Int = 254
        public const val RATIO_MAGIC_MALE: Int = 0
    }
}

/** A table of [PersonalInfo] indexed by National Dex id. */
public class PersonalTable internal constructor(private val entries: List<PersonalInfo>) {
    public val size: Int get() = entries.size

    /** Out-of-range species fall back to entry 0, as in PKHeX. */
    public operator fun get(species: Int): PersonalInfo = entries[if (species in entries.indices) species else 0]

    public companion object {
        private fun load(name: String, generation: Int, recordSize: Int): PersonalTable {
            val data = Resources.bytes("personal_$name.bin")
            val entries = List(data.size / recordSize) {
                PersonalInfo(data.copyOfRange(it * recordSize, (it + 1) * recordSize), generation)
            }
            return PersonalTable(entries)
        }

        /** Red/Blue (also the Japanese Red/Green/Blue). 152 entries. */
        public val RB: PersonalTable by lazy { load("rb", 1, 0x1C) }

        /** Yellow. 152 entries. */
        public val Y: PersonalTable by lazy { load("y", 1, 0x1C) }

        /** Gold/Silver. 252 entries. */
        public val GS: PersonalTable by lazy { load("gs", 2, 0x20) }

        /** Crystal. 252 entries. */
        public val C: PersonalTable by lazy { load("c", 2, 0x20) }
    }
}

// Ported from PKHeX.Core/Saves/SAV3.cs, SAV3RS.cs, SAV3E.cs, SAV3FRLG.cs, Saves/Substructures/Gen3/SAV3BlockDetection.cs,
// Saves/Util/SaveUtil.cs (IsG3, GetVersionG3SAV), Saves/Util/Checksums.cs (CheckSum32) and the slot operations of
// Saves/SaveFile.cs (PKHeX, GPL-3.0, commit 641c26b).
//
// Like the Gen 1/2 port, an untouched save exports byte-identically: the sectors of the active save slot are only
// rewritten (with fresh checksums) once something was edited.
package org.noxeng.coincell.pkcore.gba

import org.noxeng.coincell.pkcore.BoxSummary
import org.noxeng.coincell.pkcore.Game
import org.noxeng.coincell.pkcore.InvalidEditException
import org.noxeng.coincell.pkcore.Inventory
import org.noxeng.coincell.pkcore.ItemStack
import org.noxeng.coincell.pkcore.PlayTime
import org.noxeng.coincell.pkcore.PocketType
import org.noxeng.coincell.pkcore.Pokemon
import org.noxeng.coincell.pkcore.PokemonSummary
import org.noxeng.coincell.pkcore.Region
import org.noxeng.coincell.pkcore.SaveFile
import org.noxeng.coincell.pkcore.SlotRef
import org.noxeng.coincell.pkcore.Trainer
import org.noxeng.coincell.pkcore.TrainerGender
import org.noxeng.coincell.pkcore.UnsupportedSaveException
import org.noxeng.coincell.pkcore.data.GameStrings
import org.noxeng.coincell.pkcore.data.ItemInfo
import org.noxeng.coincell.pkcore.gb.setU16le
import org.noxeng.coincell.pkcore.gb.u16le
import org.noxeng.coincell.pkcore.text.LanguageId
import org.noxeng.coincell.pkcore.text.StringConverter3
import org.noxeng.coincell.pkcore.text.StringOption

internal class Sav3(original: ByteArray, private val layout: Gen3Layout, requestedLanguage: Int?) : SaveFile {
    private val pristine = original.copyOf()

    /** Working copy of the whole Flash image (PKHeX `SaveFile.Data`); refreshed from the objects on export. */
    private val data = original.copyOf()

    // The three save objects, reassembled from the sectors of the active slot (PKHeX Small / Large / Storage).
    private val small = ByteArray(SIZE_SECTOR_USED)
    private val large = ByteArray(4 * SIZE_SECTOR_USED)
    private val storage = ByteArray(9 * SIZE_SECTOR_USED)

    private val activeSlot: Int = getActiveSlot(original)
    private val inventoryRw = Gen3Inventory(layout)

    override val generation: Int = 3
    override val game: Game = layout.game
    override val partyCapacity: Int = 6
    override val boxCount: Int = COUNT_BOX
    override val boxCapacity: Int = COUNT_SLOTS_PER_BOX
    override val compactBoxes: Boolean = false

    /**
     * OT name is the first 8 bytes of Small. Japanese games are limited to 5 characters and never touch the last 2
     * bytes, so they stay zero (PKHeX `SAV3` constructor).
     */
    val japanese: Boolean
    override val region: Region
    override val language: Int

    private var dirty = false
    override val isModified: Boolean get() = dirty

    init {
        readSectors(original, activeSlot)
        japanese = small.u16le(0x6) == 0
        region = if (japanese) Region.JAPANESE else Region.INTERNATIONAL
        language = when {
            japanese -> LanguageId.JAPANESE
            requestedLanguage != null -> requestedLanguage
            else -> guessLanguage()
        }
    }

    private val strings: GameStrings get() = GameStrings.forLanguage(language)

    // ---- sectors -------------------------------------------------------------------------------------------------------
    private fun chunkOf(id: Int): Pair<ByteArray, Int> = when {
        id >= 5 -> storage to (id - 5) * SIZE_SECTOR_USED
        id >= 1 -> large to (id - 1) * SIZE_SECTOR_USED
        else -> small to 0
    }

    private fun readSectors(src: ByteArray, slot: Int) {
        val start = slot * SIZE_MAIN
        for (ofs in start until start + SIZE_MAIN step SIZE_SECTOR) {
            val (dest, at) = chunkOf(src.s16le(ofs + 0xFF4))
            src.copyInto(dest, at, ofs, ofs + SIZE_SECTOR_USED)
        }
    }

    private fun writeSectors(dest: ByteArray, slot: Int) {
        val start = slot * SIZE_MAIN
        for (ofs in start until start + SIZE_MAIN step SIZE_SECTOR) {
            val (src, at) = chunkOf(dest.s16le(ofs + 0xFF4))
            src.copyInto(dest, ofs, at, at + SIZE_SECTOR_USED)
        }
    }

    // ---- checksums -----------------------------------------------------------------------------------------------------
    private fun setChecksums() {
        val start = activeSlot * SIZE_MAIN
        for (ofs in start until start + SIZE_MAIN step SIZE_SECTOR) {
            data.setU16le(ofs + 0xFF6, checksum32(data, ofs, SIZE_SECTOR_USED))
        }
        for (i in 0 until COUNT_EXTRA) {
            val ofs = EXTRA_START + i * SIZE_SECTOR
            if (isSectorUninitialized(data, ofs)) continue
            data.setU16le(ofs + 0xFF4, checksum32(data, ofs, SIZE_SECTOR_USED))
        }
    }

    override val checksumsValid: Boolean
        get() {
            val start = activeSlot * SIZE_MAIN
            for (ofs in start until start + SIZE_MAIN step SIZE_SECTOR) {
                if (checksum32(data, ofs, SIZE_SECTOR_USED) != data.u16le(ofs + 0xFF6)) return false
            }
            for (i in 0 until COUNT_EXTRA) {
                val ofs = EXTRA_START + i * SIZE_SECTOR
                if (isSectorUninitialized(data, ofs)) continue
                if (checksum32(data, ofs, SIZE_SECTOR_USED) != data.u16le(ofs + 0xFF4)) return false
            }
            return true
        }

    // ---- export --------------------------------------------------------------------------------------------------------
    override fun export(): ByteArray {
        if (!dirty) return pristine.copyOf()
        writeSectors(data, activeSlot)
        setChecksums()
        return data.copyOf()
    }

    /** What PKHeX would export for the loaded state (every sector of the active slot rewritten). For tests. */
    internal fun exportLikePkhex(): ByteArray {
        dirty = true
        return export()
    }

    // ---- trainer -------------------------------------------------------------------------------------------------------
    private val securityKey: Long get() = if (layout.securityKey < 0) 0L else small.u32le(layout.securityKey)
    private val trainerNameLength: Int get() = if (japanese) 5 else MAX_TRAINER_NAME

    private fun getEventFlag(flag: Int): Boolean {
        require(flag in 0 until layout.eventFlagCount)
        return (large[layout.eventFlags + (flag shr 3)].toInt() shr (flag and 7)) and 1 != 0
    }

    private fun setEventFlag(flag: Int, value: Boolean) {
        require(flag in 0 until layout.eventFlagCount)
        setFlag(large, layout.eventFlags + (flag shr 3), flag and 7, value)
    }

    override val trainer: Trainer
        get() {
            var badges = 0
            for (i in 0 until 8) if (getEventFlag(layout.badgeFlagStart + i)) badges = badges or (1 shl i)
            val key = securityKey
            return Trainer(
                name = StringConverter3.getString(small, 0, trainerNameLength, japanese),
                id = small.u16le(0xA),
                money = (large.u32le(layout.money) xor key).toInt(),
                badges = badges,
                playTime = PlayTime(small.u16le(0xE), small.u8at(0x10), small.u8at(0x11)),
                rivalName = if (layout.rivalName < 0) null else StringConverter3.getString(large, layout.rivalName, 8, japanese),
                gender = if (small.u8at(8) == 1) TrainerGender.GIRL else TrainerGender.BOY,
                coins = (large.u16le(layout.coins) xor (key and 0xFFFF).toInt()),
                secretId = small.u16le(0xC),
            )
        }

    override fun updateTrainer(trainer: Trainer) {
        val old = this.trainer
        if (old == trainer) return
        val key = securityKey
        if (trainer.name != old.name) {
            // Preserve the original pattern (PKHeX `SAV3.OT` setter, StringConverterOption.None).
            StringConverter3.setString(small, 0, trainerNameLength, trainer.name, trainerNameLength, language, StringOption.NONE)
        }
        if (trainer.id != old.id) small.setU16le(0xA, trainer.id and 0xFFFF)
        val sid = trainer.secretId
        if (sid != null && sid != old.secretId) small.setU16le(0xC, sid and 0xFFFF)
        if (trainer.money != old.money) large.setU32le(layout.money, (trainer.money.toLong() and 0xFFFFFFFFL) xor key)
        if (trainer.coins != old.coins) large.setU16le(layout.coins, (trainer.coins xor (key and 0xFFFF).toInt()) and 0xFFFF)
        if (trainer.badges != old.badges) {
            for (i in 0 until 8) setEventFlag(layout.badgeFlagStart + i, trainer.badges and (1 shl i) != 0)
        }
        if (trainer.playTime != old.playTime) {
            small.setU16le(0xE, trainer.playTime.hours)
            small[0x10] = trainer.playTime.minutes.toByte()
            small[0x11] = trainer.playTime.seconds.toByte()
        }
        val rival = trainer.rivalName
        if (layout.rivalName >= 0 && rival != null && rival != old.rivalName) {
            StringConverter3.setString(large, layout.rivalName, 8, rival, MAX_TRAINER_NAME, language, StringOption.CLEAR_ZERO)
        }
        if (trainer.gender != null && trainer.gender != old.gender) small[8] = (if (trainer.gender == TrainerGender.GIRL) 1 else 0).toByte()
        dirty = true
    }

    // ---- language ------------------------------------------------------------------------------------------------------
    /**
     * Deviation: PKHeX guesses the language from the file name. Here the trainer's own Pokemon vote with their
     * language byte (eggs, traded and Japanese-tagged Pokemon don't count); English when nothing decides.
     */
    private fun guessLanguage(): Int {
        val tid = small.u16le(0xA)
        val sid = small.u16le(0xC)
        val votes = HashMap<Int, Int>()
        for (pk in allPokemon()) {
            if (pk.isEgg || pk.trainerId != tid || pk.secretId != sid) continue
            val lang = pk.language
            if (lang in INTERNATIONAL_LANGUAGES) votes.merge(lang, 1, Int::plus)
        }
        return votes.maxByOrNull { it.value }?.key ?: LanguageId.ENGLISH
    }

    private fun allPokemon(): Sequence<Pk3> = sequence {
        for (i in 0 until partyCount) yield(readParty(i))
        for (b in 0 until COUNT_BOX) for (s in 0 until COUNT_SLOTS_PER_BOX) readBox(b, s)?.let { yield(it) }
    }

    // ---- slots ---------------------------------------------------------------------------------------------------------
    private var partyCount: Int
        get() = large.u8at(layout.partyCount).coerceAtMost(6)
        set(value) {
            large[layout.partyCount] = value.toByte()
        }

    override val currentBox: Int get() = storage.u8at(0)

    private fun partyOffset(index: Int) = layout.party + index * Pk3.PARTY_SIZE
    private fun boxOffset(box: Int, index: Int) = 4 + Pk3.STORED_SIZE * (box * COUNT_SLOTS_PER_BOX + index)

    private fun readParty(index: Int): Pk3 =
        Pk3.fromEncrypted(large, partyOffset(index), Pk3.PARTY_SIZE).also { it.fallbackLanguage = language }

    private fun readBox(box: Int, index: Int): Pk3? {
        val ofs = boxOffset(box, index)
        if (!Pk3.isPresent(storage, ofs)) return null
        return Pk3.fromEncrypted(storage, ofs, Pk3.STORED_SIZE).also { it.fallbackLanguage = language }
    }

    private fun writeParty(index: Int, pk: Pk3?) {
        val ofs = partyOffset(index)
        if (pk == null) {
            large.fill(0, ofs, ofs + Pk3.PARTY_SIZE)
        } else {
            pk.encryptedParty().copyInto(large, ofs)
        }
    }

    private fun writeBox(box: Int, index: Int, pk: Pk3?) {
        val ofs = boxOffset(box, index)
        if (pk == null) {
            storage.fill(0, ofs, ofs + Pk3.STORED_SIZE)
        } else {
            pk.encryptedStored().copyInto(storage, ofs)
        }
    }

    override val party: List<PokemonSummary> get() = List(partyCount) { readParty(it).summary(strings) }

    override val boxes: List<BoxSummary>
        get() = List(COUNT_BOX) { b ->
            BoxSummary(b, boxName(b), b == currentBox, COUNT_SLOTS_PER_BOX, List(COUNT_SLOTS_PER_BOX) { readBox(b, it)?.summary(strings) })
        }

    override fun boxName(box: Int): String {
        val ofs = boxOffset(COUNT_BOX, 0) + box * COUNT_BOX_NAME
        return StringConverter3.getString(storage, ofs, COUNT_BOX_NAME, japanese)
    }

    private fun checkSlot(slot: SlotRef) {
        when (slot) {
            is SlotRef.Party -> if (slot.index !in 0 until 6) throw InvalidEditException("Party slot ${slot.index} out of range")
            is SlotRef.Box -> {
                if (slot.box !in 0 until COUNT_BOX) throw InvalidEditException("Box ${slot.box} out of range (0..${COUNT_BOX - 1})")
                if (slot.index !in 0 until COUNT_SLOTS_PER_BOX) throw InvalidEditException("Box slot ${slot.index} out of range")
            }
        }
    }

    private fun read(slot: SlotRef): Pk3? {
        checkSlot(slot)
        return when (slot) {
            is SlotRef.Party -> if (slot.index < partyCount) readParty(slot.index) else null
            is SlotRef.Box -> readBox(slot.box, slot.index)
        }
    }

    override fun getPokemon(slot: SlotRef): Pokemon? = read(slot)

    private fun requireCompatible(pokemon: Pokemon): Pk3 {
        val pk = pokemon as? Pk3 ?: throw InvalidEditException("A Generation 3 Pokemon is required for this save")
        if (pk.species == 0) throw InvalidEditException("Can't store an empty Pokemon (species 0)")
        return pk.duplicate()
    }

    /** PKHeX `SAV3.SetPartyValues` + `SaveFile.SetPartyValues`. */
    private fun adapt(pk: Pk3, toParty: Boolean) {
        // If no mail ID is set, ensure it is set to 0xFF for party and 0x00 for box format.
        if (!ItemInfo.isMail3(pk.heldItem)) pk.heldMailId = if (toParty) -1 else 0
        if (toParty) {
            if (!pk.partyStatsPresent) pk.resetPartyStats()
        } else {
            pk.data.fill(0, Pk3.STORED_SIZE, Pk3.PARTY_SIZE) // the box keeps the stored part only
        }
    }

    /** Writes [pk] (already adapted) at [slot]; a party index at or past the count appends. */
    private fun place(slot: SlotRef, pk: Pk3) {
        when (slot) {
            is SlotRef.Party -> {
                val count = partyCount
                if (slot.index >= count) {
                    if (count >= 6) throw InvalidEditException("The party is full")
                    writeParty(count, pk)
                    partyCount = count + 1
                } else {
                    writeParty(slot.index, pk)
                }
            }
            is SlotRef.Box -> writeBox(slot.box, slot.index, pk)
        }
    }

    override fun setPokemon(slot: SlotRef, pokemon: Pokemon, updatePokedex: Boolean) {
        checkSlot(slot)
        val pk = requireCompatible(pokemon)
        if (slot is SlotRef.Party && slot.index > partyCount) {
            throw InvalidEditException("The party is contiguous: slot ${slot.index} is beyond the first free slot ($partyCount)")
        }
        if (updatePokedex) setDex(pk)
        adapt(pk, slot is SlotRef.Party)
        place(slot, pk)
        dirty = true
    }

    override fun addPokemon(pokemon: Pokemon, updatePokedex: Boolean): SlotRef? {
        for (step in 0 until COUNT_BOX) {
            val box = (currentBox.coerceIn(0, COUNT_BOX - 1) + step) % COUNT_BOX
            for (i in 0 until COUNT_SLOTS_PER_BOX) {
                if (Pk3.isPresent(storage, boxOffset(box, i))) continue
                val ref = SlotRef.Box(box, i)
                setPokemon(ref, pokemon, updatePokedex)
                return ref
            }
        }
        return null
    }

    override fun removePokemon(slot: SlotRef): Pokemon? {
        val pk = read(slot) ?: return null
        when (slot) {
            // PKHeX `DeletePartySlot`: the following Pokemon move up and the last slot is blanked.
            is SlotRef.Party -> {
                val count = partyCount
                for (i in slot.index until count - 1) large.copyInto(large, partyOffset(i), partyOffset(i + 1), partyOffset(i + 2))
                writeParty(count - 1, null)
                partyCount = count - 1
            }
            is SlotRef.Box -> writeBox(slot.box, slot.index, null)
        }
        dirty = true
        return pk
    }

    override fun movePokemon(from: SlotRef, to: SlotRef) {
        checkSlot(from)
        checkSlot(to)
        val moving = read(from) ?: throw InvalidEditException("Nothing to move at $from")
        if (from == to) return
        val other = read(to)
        val target = if (to is SlotRef.Party && other == null) SlotRef.Party(partyCount) else to
        if (target == from) return
        if (other != null) { // swap
            adapt(moving, to is SlotRef.Party)
            adapt(other, from is SlotRef.Party)
            place(to, moving)
            place(from, other)
        } else {
            if (target is SlotRef.Party && partyCount >= 6) throw InvalidEditException("Destination is full")
            adapt(moving, target is SlotRef.Party)
            // Empty the source first: a party source shifts the party, which moves a party target down by one.
            removePokemon(from)
            val dest = if (target is SlotRef.Party && from is SlotRef.Party) SlotRef.Party(partyCount) else target
            place(dest, moving)
        }
        dirty = true
    }

    // ---- inventory -----------------------------------------------------------------------------------------------------
    override val inventory: Inventory get() = Inventory(inventoryRw.read(large, small))

    override fun setPocket(type: PocketType, items: List<ItemStack>) {
        inventoryRw.write(large, small, type, items)
        dirty = true
    }

    // ---- pokedex -------------------------------------------------------------------------------------------------------
    override fun isSeen(species: Int): Boolean = species in 1..MAX_SPECIES && getDexBit(small, POKEDEX + 0x44, species)
    override fun isCaught(species: Int): Boolean = species in 1..MAX_SPECIES && getDexBit(small, POKEDEX + 0x10, species)

    /** PKHeX `SAV3.SetDex`: caught and seen (seen mirrored into the two copies of the large block). */
    private fun setDex(pk: Pk3) {
        val species = pk.species
        if (species !in 1..MAX_SPECIES || pk.isEgg) return
        val before = isSeen(species) && isCaught(species)
        when (species) {
            UNOWN -> if (!isSeen(species)) small.setU32le(0x1C, pk.pid)
            SPINDA -> if (!isSeen(species)) small.setU32le(0x20, pk.pid)
        }
        setDexBit(small, POKEDEX + 0x10, species)
        setDexBit(small, POKEDEX + 0x44, species)
        setDexBit(large, layout.seenOffset2, species)
        setDexBit(large, layout.seenOffset3, species)
        if (!before) dirty = true
    }

    private fun getDexBit(buffer: ByteArray, offset: Int, species: Int): Boolean {
        val bit = species - 1
        return (buffer[offset + (bit shr 3)].toInt() shr (bit and 7)) and 1 != 0
    }

    private fun setDexBit(buffer: ByteArray, offset: Int, species: Int) {
        val bit = species - 1
        setFlag(buffer, offset + (bit shr 3), bit and 7, true)
    }

    internal companion object {
        const val SIZE_RAW = 0x20000
        const val SIZE_SECTOR = 0x1000
        const val SIZE_SECTOR_USED = 0xF80
        const val COUNT_MAIN = 14
        const val SIZE_MAIN = COUNT_MAIN * SIZE_SECTOR
        const val COUNT_EXTRA = 4
        const val EXTRA_START = 0x1C000
        const val COUNT_BOX = 14
        const val COUNT_SLOTS_PER_BOX = 30
        const val COUNT_BOX_NAME = 8 + 1
        const val POKEDEX = 0x18
        const val MAX_SPECIES = 386
        const val MAX_TRAINER_NAME = 7
        const val UNOWN = 201
        const val SPINDA = 327
        val INTERNATIONAL_LANGUAGES = setOf(LanguageId.ENGLISH, LanguageId.FRENCH, LanguageId.ITALIAN, LanguageId.GERMAN, LanguageId.SPANISH)

        /** Detects a Gen 3 save and its game (PKHeX `SaveUtil.IsG3` + `GetVersionG3SAV`), or null. */
        fun detect(data: ByteArray): Gen3Layout? {
            if (data.size != SIZE_RAW) return null
            var smallOffset = -1
            for (slot in 0 until 2) {
                val s0 = allMainSectorsPresent(data, slot)
                if (s0 >= 0) {
                    smallOffset = s0
                    break
                }
            }
            if (smallOffset < 0) return null
            // 0xAC: FR/LG has a fixed value (1), R/S has battle tower data (0 when none), E the security key.
            return when (data.u32le(smallOffset + 0xAC)) {
                1L -> Gen3Layout.FRLG
                0L -> Gen3Layout.RS
                else -> {
                    // RS data structure only extends 0x890 bytes; check if any data is present afterward.
                    val emerald = (smallOffset + 0x890 until smallOffset + 0xF2C).any { data[it].toInt() != 0 }
                    if (emerald) Gen3Layout.E else Gen3Layout.RS
                }
            }
        }

        fun load(data: ByteArray, language: Int?): Sav3 {
            val layout = detect(data) ?: throw UnsupportedSaveException(
                "Not a recognised Generation 3 Pokemon save: no complete set of save sectors was found.",
            )
            return Sav3(data, layout, language)
        }

        /** PKHeX `SAV3.IsAllMainSectorsPresent`: offset of sector 0 if all 14 sectors of [slot] are present, else -1. */
        fun allMainSectorsPresent(data: ByteArray, slot: Int): Int {
            val start = SIZE_MAIN * slot
            var bitTrack = 0
            var sector0 = 0
            for (ofs in start until start + SIZE_MAIN step SIZE_SECTOR) {
                val id = data.s16le(ofs + 0xFF4)
                if (id !in 0 until COUNT_MAIN) return -1 // invalid sector ID
                bitTrack = bitTrack or (1 shl id)
                if (id == 0) sector0 = ofs
            }
            return if (bitTrack == 0b0011_1111_1111_1111) sector0 else -1
        }

        private fun getActiveSlot(data: ByteArray): Int {
            val s0 = allMainSectorsPresent(data, 0)
            val s1 = allMainSectorsPresent(data, 1)
            if (s0 < 0) return if (s1 >= 0) 1 else 0
            if (s1 < 0) return 0
            return compareFooters(data, s0, s1)
        }

        /** PKHeX `SAV3BlockDetection.CompareFooters`: 0 = first slot, 1 = second (the higher save counter wins). */
        private fun compareFooters(data: ByteArray, offset1: Int, offset2: Int): Int {
            val counter1 = data.u32le(offset1 + 0x0FFC)
            val counter2 = data.u32le(offset2 + 0x0FFC)
            val max = 0xFFFFFFFFL
            // Uninitialized -- only continue if a rollover case (humanly impossible)
            if (counter1 == max && counter2 != max - 1) return 1
            if (counter2 == max && counter1 != max - 1) return 0
            return if (counter1 < counter2) 1 else 0 // Same -> First, shouldn't happen for valid saves.
        }

        /** PKHeX `Checksums.CheckSum32`: sum of little-endian u32 words, folded to 16 bits. */
        fun checksum32(data: ByteArray, offset: Int, length: Int): Int {
            var chk = 0L
            for (i in offset until offset + length step 4) chk = (chk + data.u32le(i)) and 0xFFFFFFFFL
            return ((chk + (chk ushr 16)) and 0xFFFF).toInt()
        }

        private fun isSectorUninitialized(data: ByteArray, offset: Int): Boolean =
            (offset until offset + SIZE_SECTOR).all { data[it].toInt() == 0 || data[it].toInt() == -1 }

        private fun setFlag(buffer: ByteArray, offset: Int, bit: Int, value: Boolean) {
            val b = buffer[offset].toInt()
            buffer[offset] = (if (value) b or (1 shl bit) else b and (1 shl bit).inv()).toByte()
        }
    }
}

internal fun ByteArray.u8at(ofs: Int): Int = this[ofs].toInt() and 0xFF
internal fun ByteArray.s16le(ofs: Int): Int = u16le(ofs).toShort().toInt()
internal fun ByteArray.u32le(ofs: Int): Long = u16le(ofs).toLong() or (u16le(ofs + 2).toLong() shl 16)
internal fun ByteArray.setU32le(ofs: Int, value: Long) {
    setU16le(ofs, (value and 0xFFFF).toInt())
    setU16le(ofs + 2, ((value ushr 16) and 0xFFFF).toInt())
}

package org.noxeng.coincell.save

import org.noxeng.coincell.cart.AgbCartIo
import org.noxeng.coincell.cart.AgbFlashChip
import org.noxeng.coincell.cart.AgbSaveDetection
import org.noxeng.coincell.cart.AgbSaveTypes
import org.noxeng.coincell.cart.SaveTypeSource
import org.noxeng.coincell.cart.findRepeatingSize

/**
 * Step (c) of the save type detection: probing the cartridge the way FlashGBX's "Detect Cartridge" does
 * (`_DetectCartridge_Worker`, LK:1392-1525, spec 5.8 step 3):
 *
 *  1. read the 128 KiB save window as if it were 1M SRAM (two banks) and find the size after which it repeats;
 *  2. `ReadFlashSaveID`: a known Flash chip decides the type and the size; a bootleg chip is told apart by the data;
 *  3. otherwise a repeating size above 256 bytes means SRAM / FRAM (128 KiB, 64 KiB or 32 KiB);
 *  4. otherwise EEPROM: read it as 4K and as 64K; an empty 64K read means no save, a 4K read that equals the start of
 *     the 64K read means 64K, anything else 4K.
 *
 * Batteryless SRAM (`CheckBatterylessSRAM`) and DACS 8M are not probed. The result is always a guess: the app shows it
 * with its source and lets the user confirm or change it before anything is restored. All cartridge access goes through
 * [AgbCartIo], so the writes are the Flash identify and bank select ones only.
 */
internal class AgbDetector(
    private val io: AgbCartIo,
    private val prepare: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    fun probe(isCancelled: () -> Boolean, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): AgbSaveDetection {
        prepare()
        val reader = AgbVerifiedReader(io, log, "The probe could not read the cartridge.")
        val window = reader.readSave(AgbSaveLayout(AgbSaveTypes.SRAM_1M, null), verify = false, isCancelled, onProgress)
        var size = findRepeatingSize(window)
        log("Probe: the 128 KiB save window repeats every $size bytes")

        var type: Int? = null
        var chip: AgbFlashChip? = null
        var note: String? = null
        when (val id = AgbFlashId.read(io, log)) {
            AgbFlashId.Result.LooksLikeSram -> Unit
            is AgbFlashId.Result.Flash -> {
                chip = id.chip
                if (id.chip.id != 0) {
                    if (id.chip.known) {
                        size = id.chip.size
                        type = when {
                            id.chip.isBootleg -> when {
                                window.copyOfRange(0, 0x20000).all { it == 0xFF.toByte() } -> AgbSaveTypes.FLASH_1M
                                window.copyOfRange(0, 0x10000).contentEquals(window.copyOfRange(0x10000, 0x20000)) -> AgbSaveTypes.FLASH_512K
                                else -> AgbSaveTypes.FLASH_1M
                            }
                            size == 0x20000 -> AgbSaveTypes.FLASH_1M
                            size == 0x10000 -> AgbSaveTypes.FLASH_512K
                            else -> null
                        }
                    } else {
                        note = "Unknown FLASH save chip (0x%04X). Choose the save type yourself if you know it.".format(id.chip.id)
                    }
                }
            }
        }

        if (type == null && note == null) {
            if (size > 256) {
                type = when (size) {
                    0x20000 -> AgbSaveTypes.SRAM_1M
                    0x10000 -> AgbSaveTypes.SRAM_512K
                    0x8000 -> AgbSaveTypes.SRAM_256K
                    8192 -> AgbSaveTypes.EEPROM_64K
                    else -> null
                }
                if (type == null) note = "The save window repeats every $size bytes, which matches no GBA save type."
            } else {
                type = probeEeprom(isCancelled, onProgress)
                if (type == AgbSaveTypes.NONE) note = "No save memory answered (no SRAM, Flash or EEPROM)."
            }
        }
        val label = chip?.takeIf { it.id != 0 }?.label
        return AgbSaveDetection(type, SaveTypeSource.PROBE, note, chipLabel = label)
    }

    /** The EEPROM test of LK:1494-1512. */
    private fun probeEeprom(isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit): Int {
        val reader = AgbVerifiedReader(io, log, "The EEPROM probe could not read the cartridge.")
        prepare()
        val e4k = reader.readSave(AgbSaveLayout(AgbSaveTypes.EEPROM_4K, null), verify = false, isCancelled, onProgress)
        prepare()
        val e64k = reader.readSave(AgbSaveLayout(AgbSaveTypes.EEPROM_64K, null), verify = false, isCancelled, onProgress)
        return when {
            e64k.all { it == 0xFF.toByte() } || e64k.all { it == 0.toByte() } -> AgbSaveTypes.NONE
            e4k.contentEquals(e64k.copyOfRange(0, e4k.size)) -> AgbSaveTypes.EEPROM_64K
            else -> AgbSaveTypes.EEPROM_4K
        }
    }
}

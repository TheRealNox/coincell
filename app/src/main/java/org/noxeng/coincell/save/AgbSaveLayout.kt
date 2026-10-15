package org.noxeng.coincell.save

import org.noxeng.coincell.cart.AgbCartIo
import org.noxeng.coincell.cart.AgbFlashChip
import org.noxeng.coincell.cart.AgbHeader
import org.noxeng.coincell.cart.AgbSaveTypes
import org.noxeng.coincell.cart.SaveTypeSource
import org.noxeng.coincell.protocol.ProtocolException

/** A problem with the GBA save memory itself (no Flash chip found, chip too small, erase did not finish). */
class AgbSaveException(message: String) : ProtocolException(message)

/** What a backup of the inserted GBA cartridge would involve, or why it cannot be done. */
internal sealed interface AgbBackupPlan {
    class Ready(
        val saveType: Int,
        val source: SaveTypeSource,
        /** The header bytes at 0xC5 / 0xC7 / 0xC9 are zero, FlashGBX's precondition for looking for a clock (LK:1219). */
        val rtcCandidate: Boolean = true,
    ) : AgbBackupPlan {
        val saveSize: Int get() = AgbSaveTypes.size(saveType)
        val saveLabel: String get() = AgbSaveTypes.label(saveType)
    }

    class Unavailable(val reason: String) : AgbBackupPlan

    companion object {
        /** [saveType] is the detected or chosen FlashGBX save type index, or null while it is not known. */
        fun forHeader(h: AgbHeader, saveType: Int?, source: SaveTypeSource?): AgbBackupPlan {
            if (!h.logoValid) return Unavailable("The cartridge logo is not valid: check the cartridge contacts and the selected type.")
            return when {
                saveType == null || source == null -> Unavailable("The save type of this cartridge is not known yet. Choose it, or let the app probe the cartridge.")
                saveType == AgbSaveTypes.NONE -> Unavailable("This cartridge has no save memory according to ${source.label}. Choose a save type if that is wrong.")
                saveType == AgbSaveTypes.DACS_8M -> Unavailable("8M DACS cartridges are not supported.")
                saveType == AgbSaveTypes.BATTERYLESS -> Unavailable("Batteryless SRAM cartridges keep the save in ROM Flash and are not supported.")
                !AgbSaveTypes.isSupported(saveType) -> Unavailable("Save type $saveType is not supported.")
                else -> Ready(saveType, source, rtcCandidate = listOf(AgbCartIo.GPIO_DAT, AgbCartIo.GPIO_CNT, AgbCartIo.GPIO_RE).all { h.raw[it + 1].toInt() == 0 })
            }
        }
    }
}

/** How a 1M save is split into the 64 KiB banks the cartridge exposes (LK:3611-3626). */
internal enum class AgbBankSwitch { NONE, FLASH, BOOTLEG }

/**
 * Chunking of one save: which type, which Flash chip, how many banks and how many bytes per chunk
 * (spec 6.1.2 / 6.6, LK:3446-3480). [flashChip] is required for the Flash types.
 */
internal class AgbSaveLayout(val saveType: Int, val flashChip: AgbFlashChip?) {
    init {
        require(AgbSaveTypes.isSupported(saveType)) { "Save type $saveType is not supported" }
        require(!AgbSaveTypes.isFlash(saveType) || flashChip != null) { "A Flash save needs the chip" }
    }

    val saveSize: Int = AgbSaveTypes.size(saveType)
    val banks: Int = (saveSize + BANK_SIZE - 1) / BANK_SIZE
    val isEeprom: Boolean get() = AgbSaveTypes.isEeprom(saveType)
    val isFlash: Boolean get() = AgbSaveTypes.isFlash(saveType)

    /** EEPROM carts take the 4K / 64K selector of the wire command (`C5 01` / `C5 02`). */
    val eepromType: Int get() = saveType

    val bankSwitch: AgbBankSwitch
        get() = when {
            saveSize <= BANK_SIZE -> AgbBankSwitch.NONE
            saveType == AgbSaveTypes.SRAM_1M || flashChip?.isBootleg == true -> AgbBankSwitch.BOOTLEG
            saveType == AgbSaveTypes.FLASH_1M -> AgbBankSwitch.FLASH
            else -> AgbBankSwitch.NONE
        }

    /** Bytes in the 64 KiB bank [bank]. */
    fun bankLength(bank: Int): Int = minOf(saveSize - bank * BANK_SIZE, BANK_SIZE)

    /** Bytes per chunk when reading ([restore] false) or writing. */
    fun bufferLen(restore: Boolean): Int = when {
        isEeprom -> if (restore) 0x40 else 0x100
        isFlash -> flashChip!!.bufferLen
        else -> 0x2000
    }

    companion object {
        const val BANK_SIZE = 0x10000
    }
}

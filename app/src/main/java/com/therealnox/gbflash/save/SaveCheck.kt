package com.therealnox.gbflash.save

/** Plausibility check of freshly read save data. */
object SaveCheck {
    /** A human-readable reason if the data looks like a dead battery, wrong mapper or bad contacts. */
    fun suspectReason(data: ByteArray): String? = when {
        data.isEmpty() -> "The save is empty."
        data.all { it == 0xFF.toByte() } ->
            "All bytes are 0xFF. This usually means a dead battery, a wrong mapper or bad contacts."
        data.all { it == 0.toByte() } ->
            "All bytes are 0x00. This usually means a dead battery, a wrong mapper or bad contacts."
        else -> null
    }

    /** `<cleaned title>.sav`, with characters that are invalid in file names replaced. */
    fun exportFileName(title: String): String {
        val cleaned = title.replace(Regex("""[<>:"/\|?*\u0000-\u001F]"""), "_").trim().trim('.')
        return (cleaned.ifEmpty { "save" }) + ".sav"
    }
}

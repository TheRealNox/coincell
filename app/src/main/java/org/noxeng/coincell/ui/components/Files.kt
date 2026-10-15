package org.noxeng.coincell.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

// ---- file helpers for the .sav import ------------------------------------------------------------

/** The name the file picker shows for [uri] (falls back to the last path segment). */
fun displayName(context: Context, uri: Uri): String {
    val fromProvider = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    return fromProvider ?: (uri.lastPathSegment ?: "file.sav")
}

/** Reads at most [limit] + 1 bytes, so an oversized file is detected without loading all of it. */
fun readLimited(context: Context, uri: Uri, limit: Int): ByteArray {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the chosen file")
    return input.use {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= limit) {
            val n = it.read(buf, 0, minOf(buf.size, limit + 1 - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}

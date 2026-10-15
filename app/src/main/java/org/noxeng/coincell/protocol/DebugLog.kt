package org.noxeng.coincell.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Bounded, thread-safe in-memory log of TX/RX traffic and app messages for the debug view. */
class DebugLog(private val maxLines: Int = 2000) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(line: String) {
        _lines.update { (it + line).takeLast(maxLines) }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}

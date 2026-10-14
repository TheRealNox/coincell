package com.therealnox.gbflash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.hexDump
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.FirmwareInfo

@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val log by vm.debugLog.lines.collectAsState()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("GBFlash", style = MaterialTheme.typography.headlineMedium)
                ConnectionCard(state, onConnect = vm::refresh, onDisconnect = vm::disconnect)
                state.firmware?.let { FirmwareCard(it) }
                if (state.status == ConnectionStatus.CONNECTED) {
                    PlatformCard(state, vm)
                }
                state.error?.let {
                    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text("Error: $it", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                state.header?.let { HeaderCard(it) }
                state.agbSaveHint?.let { InfoCard("GBA save window (read-only probe)", it) }
                state.header?.let { h ->
                    Collapsible("Raw header hex (${h.raw.size} bytes)") { Mono(h.raw.hexDump()) }
                }
                Collapsible("Debug log (TX/RX, ${log.size} lines)") {
                    OutlinedButton(onClick = { vm.debugLog.clear() }) { Text("Clear") }
                    Mono(log.joinToString("\n"), Modifier.height(280.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)))
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: UiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val label = when (state.status) {
                ConnectionStatus.NO_DEVICE -> "Not connected"
                ConnectionStatus.NEEDS_PERMISSION -> "Waiting for USB permission"
                ConnectionStatus.CONNECTING -> "Connecting..."
                ConnectionStatus.CONNECTED -> "Connected"
                ConnectionStatus.FAILED -> "Connection failed"
            }
            Text("Status: $label", style = MaterialTheme.typography.titleMedium)
            state.statusDetail?.let { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status == ConnectionStatus.CONNECTED) {
                    OutlinedButton(onClick = onDisconnect, enabled = !state.busy) { Text("Disconnect (cart power off)") }
                } else {
                    Button(onClick = onConnect, enabled = state.status != ConnectionStatus.CONNECTING) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun FirmwareCard(fw: FirmwareInfo) {
    InfoCard(
        "Firmware",
        buildString {
            appendLine("Version: ${fw.versionString}")
            appendLine("PCB: ${fw.pcbLabel} (pcb_ver ${fw.pcbVer})")
            appendLine("Name: ${fw.deviceName ?: "(none)"}")
            appendLine("Built: ${fw.timestampString}")
            append("Flags: power ctrl=${fw.cartPowerCtrl}, presence switch=${fw.cartPresenceSwitch}, ")
            append("mode switch=${fw.cartModeSwitch}, bootloader reset=${fw.bootloaderReset}, unregistered=${fw.unregistered}")
        },
    )
}

@Composable
private fun PlatformCard(state: UiState, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cartridge type", style = MaterialTheme.typography.titleMedium)
            Text("Pick the type of the inserted cartridge. A GBA cartridge must never be used in GB / GBC mode.")
            Column(Modifier.selectableGroup()) {
                CartPlatform.entries.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = state.platform == p, enabled = !state.busy, role = Role.RadioButton) {
                                vm.selectPlatform(p)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.platform == p, onClick = null, enabled = !state.busy)
                        Text(p.label, Modifier.padding(start = 8.dp))
                    }
                }
            }
            Button(onClick = vm::readHeader, enabled = state.platform != null && !state.busy) {
                Text(if (state.busy) "Working..." else "Read header")
            }
            if (state.platform == CartPlatform.GBA) {
                OutlinedButton(onClick = vm::probeAgbSave, enabled = !state.busy) { Text("Probe GBA save window (read-only)") }
            }
        }
    }
}

@Composable
private fun HeaderCard(h: CartHeader) {
    val text = buildString {
        if (h.likelyNoCart) appendLine("WARNING: logo area is blank - no cartridge, or wrong cartridge type selected.")
        appendLine("Title: ${h.title.ifEmpty { "(empty)" }}")
        when (h) {
            is DmgHeader -> {
                h.gameCode?.let { appendLine("Game code: $it") }
                appendLine("Cart type: ${h.mapperName} (0x%02X)".format(h.cartTypeByte))
                if (!h.supportedMapper) appendLine("Mapper not in FlashGBX's supported list")
                if (h.hasRtcDeclared) appendLine("Header declares RTC (not probed)")
                appendLine("ROM size: ${h.romSizeBytes?.let { "${it / 1024} KiB" } ?: "unknown"} (code 0x%02X)".format(h.romSizeCode))
                appendLine("CGB flag: 0x%02X  SGB flag: 0x%02X  Version: ${h.version}".format(h.cgbFlag, h.sgbFlag))
                appendLine("Header checksum: stored 0x%02X, computed 0x%02X".format(h.headerChecksumStored, h.headerChecksumComputed))
                appendLine("Global checksum: 0x%04X (header-only sum 0x%04X, informational)".format(h.globalChecksumStored, h.globalChecksumComputed))
                appendLine("RAM code: 0x%02X".format(h.ramSizeCode))
            }
            is AgbHeader -> {
                appendLine("Game code: ${h.gameCode}  Maker: ${h.makerCode}  Version: ${h.version}")
                appendLine("0x96 fixed byte: ${if (h.fixed96Valid) "OK" else "BAD"}")
                appendLine("Header checksum: stored 0x%02X, computed 0x%02X".format(h.headerChecksumStored, h.headerChecksumComputed))
                appendLine("DB key (SHA-1): ${h.dbKey}")
            }
        }
        appendLine("Logo: ${if (h.logoValid) "valid" else "INVALID"}")
        appendLine("Header checksum: ${if (h.headerChecksumValid) "valid" else "INVALID"}")
        val sizeText = h.save.sizeBytes?.let { " ($it bytes)" } ?: ""
        appendLine("Save: ${h.save.label}$sizeText [${h.save.status}]")
        h.save.note?.let { append(it) }
    }
    InfoCard("Cartridge header", text.trimEnd())
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(body) }
        }
    }
}

@Composable
private fun Collapsible(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            TextButton(onClick = { open = !open }) { Text((if (open) "v " else "> ") + title) }
            if (open) content()
        }
    }
}

@Composable
private fun Mono(text: String, modifier: Modifier = Modifier) {
    SelectionContainer {
        Text(text, modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    }
}

package com.therealnox.gbflash.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therealnox.gbflash.cart.AgbHeader
import com.therealnox.gbflash.cart.CartHeader
import com.therealnox.gbflash.cart.DmgHeader
import com.therealnox.gbflash.cart.hexDump
import com.therealnox.gbflash.device.ConnectionStatus
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.ui.LocalShell
import com.therealnox.gbflash.ui.ScreenFrame
import com.therealnox.gbflash.ui.components.KeyValueRow
import com.therealnox.gbflash.ui.components.SectionHeader
import com.therealnox.gbflash.ui.components.Severity
import com.therealnox.gbflash.ui.components.StatusCard
import com.therealnox.gbflash.ui.theme.ThemeMode

// ---- Settings home -------------------------------------------------------------------------------

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    onFirmware: () -> Unit,
    onAdvanced: () -> Unit,
    onAbout: () -> Unit,
) {
    val mode by prefs.themeMode.collectAsState()
    val dynamic by prefs.dynamicColor.collectAsState()
    ScreenFrame(title = "Settings") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GroupTitle("Appearance")
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = mode == m, role = Role.RadioButton, onClick = { prefs.setThemeMode(m) })
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = null)
                        Text(m.label, Modifier.padding(start = 16.dp))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = dynamic, role = Role.Switch, onValueChange = prefs::setDynamicColor)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Use dynamic colour", Modifier.weight(1f))
                    Switch(checked = dynamic, onCheckedChange = null)
                }
            }
            HorizontalDivider()
            GroupTitle("Reader")
            NavRow("Firmware and device info", "Version, PCB and capabilities of the reader", onFirmware)
            HorizontalDivider()
            GroupTitle("Data")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Library location")
                Text(
                    "App-private storage on this phone. Uninstalling the app deletes the library, so export saves you want to keep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            GroupTitle("Advanced")
            NavRow("Advanced", "Debug log, raw cartridge header, GBA read-only probe", onAdvanced)
            HorizontalDivider()
            GroupTitle("Support")
            SupportRow()
            HorizontalDivider()
            GroupTitle("About")
            NavRow("About GBFlash", "Version, licence and credits", onAbout)
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    SectionHeader(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

// ---- Advanced ------------------------------------------------------------------------------------

@Composable
fun AdvancedScreen(onBack: () -> Unit, onDebugLog: () -> Unit, onRawHeader: () -> Unit, onProbe: () -> Unit) {
    ScreenFrame(title = "Advanced", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            NavRow("Debug log", "TX/RX traffic and app messages", onDebugLog)
            NavRow("Raw cartridge header", "Parsed fields and hex dump of the last header read", onRawHeader)
            NavRow("GBA save window probe", "Read-only look at the GBA save area", onProbe)
        }
    }
}

@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val shell = LocalShell.current
    val log by shell.device.debugLog.lines.collectAsState()
    val context = LocalContext.current
    val text = log.joinToString("\n")
    ScreenFrame(title = "Debug log", onBack = onBack, statusBar = true) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { shell.device.debugLog.clear() }, Modifier.heightIn(min = 48.dp)) { Text("Clear") }
            OutlinedButton(onClick = { copyText(context, text) }, enabled = log.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy") }
            OutlinedButton(onClick = { shareText(context, text) }, enabled = log.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
        }
        Text("${log.size} lines", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                if (log.isEmpty()) "(empty)" else text,
                Modifier.fillMaxSize().verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(horizontal = 16.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("GBFlash debug log", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun RawHeaderScreen(onBack: () -> Unit) {
    val shell = LocalShell.current
    val state by shell.device.state.collectAsState()
    val header = state.header
    ScreenFrame(title = "Raw cartridge header", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (header == null) {
                Text(
                    "No header has been read in this session. Choose the cartridge type on the Device tab to read one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SectionHeader("Parsed fields")
                SelectionContainer { Text(headerText(header)) }
                SectionHeader("Hex dump (${header.raw.size} bytes)")
                SelectionContainer {
                    Text(header.raw.hexDump(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

/** The parsed header as plain text (what the old header card showed). */
internal fun headerText(h: CartHeader): String = buildString {
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
}.trimEnd()

@Composable
fun GbaProbeScreen(onBack: () -> Unit) {
    val shell = LocalShell.current
    val state by shell.device.state.collectAsState()
    val ready = state.status == ConnectionStatus.CONNECTED && state.platform == CartPlatform.GBA && !state.busy
    ScreenFrame(title = "GBA save window probe", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "A read-only look at the save area of a Game Boy Advance cartridge, for hardware debugging. Nothing is written. " +
                    "GBA backup and restore are on the Device tab; this probe only reads the 64 KiB save window.",
            )
            Button(onClick = shell.device::probeAgbSave, enabled = ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Probe GBA save window (read-only)")
            }
            if (!ready) {
                Text(
                    when {
                        state.status != ConnectionStatus.CONNECTED -> "Connect the reader first."
                        state.busy -> "Wait for the current operation to finish."
                        else -> "On the Device tab, choose GBA and power on at 3.3 V first. The probe needs the GBA mode."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { StatusCard("Error", it, Severity.ERROR) }
            state.agbSaveHint?.let { StatusCard("Result", it, Severity.INFO) }
        }
    }
}

// ---- Firmware ------------------------------------------------------------------------------------

@Composable
fun FirmwareScreen(onBack: () -> Unit) {
    val shell = LocalShell.current
    val state by shell.device.state.collectAsState()
    val fw = state.firmware
    ScreenFrame(title = "Firmware and device info", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fw == null) {
                Text("Connect the reader to see its firmware details.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                KeyValueRow("Firmware", fw.versionString)
                KeyValueRow("PCB", "${fw.pcbLabel} (pcb_ver ${fw.pcbVer})")
                KeyValueRow("Name", fw.deviceName ?: "(none)")
                KeyValueRow("Built", fw.timestampString)
                SectionHeader("Capabilities")
                Capability("Cartridge power control", fw.cartPowerCtrl)
                Capability("Cartridge presence switch", fw.cartPresenceSwitch)
                Capability("Cartridge mode switch", fw.cartModeSwitch)
                Capability("Bootloader reset", fw.bootloaderReset)
                Capability("Unregistered firmware", fw.unregistered)
                if (!fw.cartPresenceSwitch) {
                    Text("This reader cannot detect a cartridge.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Capability(label: String, on: Boolean) {
    Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (on) Icons.Filled.CheckCircle else Icons.Filled.Close,
            contentDescription = if (on) "Yes" else "No",
            Modifier.size(20.dp),
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label)
    }
}

// ---- About ---------------------------------------------------------------------------------------

private const val MIT_TEXT = """MIT License

Copyright (c) 2011-2013 Google Inc.
Copyright (c) 2013 Mike Wakerly

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

@Composable
fun AboutScreen(versionName: String, onBack: () -> Unit, onFirmware: () -> Unit) {
    val context = LocalContext.current
    val shell = LocalShell.current
    val state by shell.device.state.collectAsState()
    var showGpl by remember { mutableStateOf(false) }
    var showMit by remember { mutableStateOf(false) }
    val gpl = remember {
        try {
            context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "See https://www.gnu.org/licenses/gpl-3.0.txt"
        }
    }
    ScreenFrame(title = "About GBFlash", onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GBFlash for Android", style = MaterialTheme.typography.headlineSmall)
            Text("Version $versionName", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Backs up and restores Game Boy, Game Boy Color and Game Boy Advance save games with a GBFlash cartridge reader over USB.",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.firmware?.let { fw ->
                KeyValueRow("Reader firmware", "${fw.versionString}, PCB ${fw.pcbLabel}")
                TextButton(onClick = onFirmware, Modifier.heightIn(min = 48.dp)) { Text("Firmware details") }
            }
            HorizontalDivider()
            SectionHeader("Support")
            SupportRow()
            HorizontalDivider()
            SectionHeader("Licence")
            Text("GBFlash for Android is free software, licensed under the GNU General Public License, version 3 (GPL-3.0).")
            OutlinedButton(onClick = { showGpl = !showGpl }, Modifier.heightIn(min = 48.dp)) { Text(if (showGpl) "Hide GPL-3.0 text" else "Show GPL-3.0 text") }
            if (showGpl) SelectionContainer { Text(gpl, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
            HorizontalDivider()
            SectionHeader("Credits")
            Text("FlashGBX by Lesserkuma (GPL-3.0)", style = MaterialTheme.typography.titleSmall)
            Text(
                "The cartridge protocol, the header parsing and the save backup and restore logic are ported from FlashGBX. " +
                    "https://github.com/lesserkuma/FlashGBX",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("GBA cartridge database (GPL-3.0)", style = MaterialTheme.typography.titleSmall)
            Text(
                "The GBA save type lookup uses a reduced copy of FlashGBX's db_AGB.json (config/db_AGB.json), (c) Lesserkuma and the FlashGBX contributors, GPL-3.0.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("usb-serial-for-android by Mike Wakerly and contributors (MIT)", style = MaterialTheme.typography.titleSmall)
            Text(
                "USB serial access to the reader's CH340 chip. Includes LenientCh34xSerialDriver, a modified copy of the library's " +
                    "Ch34xSerialDriver (3.11.0). https://github.com/mik3y/usb-serial-for-android",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { showMit = !showMit }, Modifier.heightIn(min = 48.dp)) { Text(if (showMit) "Hide MIT licence" else "Show MIT licence") }
            if (showMit) SelectionContainer { Text(MIT_TEXT, style = MaterialTheme.typography.bodySmall) }
            Text("Also used: AndroidX, Jetpack Compose and Kotlin (Apache License 2.0).", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text(
                "Pokemon and Game Boy are trademarks of their owners. GBFlash is not affiliated with or endorsed by them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

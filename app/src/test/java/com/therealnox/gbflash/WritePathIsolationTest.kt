package com.therealnox.gbflash

import com.therealnox.gbflash.cart.AgbCartIo
import com.therealnox.gbflash.cart.AgbCartWriter
import com.therealnox.gbflash.cart.DmgCartIo
import com.therealnox.gbflash.cart.DmgCartWriter
import com.therealnox.gbflash.protocol.AgbCartAccess
import com.therealnox.gbflash.protocol.Commands
import com.therealnox.gbflash.protocol.DmgCartAccess
import com.therealnox.gbflash.protocol.FirmwareInfo
import com.therealnox.gbflash.protocol.Op
import com.therealnox.gbflash.protocol.Transport
import com.therealnox.gbflash.save.DmgSaveBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The SRAM write path (`B3`) is reachable from the restore engine only, never from backup code. */
class WritePathIsolationTest {
    private val srcRoot = File("src/main/java/com/therealnox/gbflash")

    private val writePathTokens = Regex("""0xB3|DMG_CART_WRITE_SRAM|DmgCartWriter|dmgCartWriter|DmgCartWriteAccess|Mbc3RtcWrite|DmgSaveRestore|restoreDmgSave""")

    /** Source files that may mention any write-path name (code, not comments). */
    private val allowed = setOf(
        "cart/DmgCartWriter.kt",
        "cart/Mbc3RtcWrite.kt",
        "protocol/DmgCartWriteAccess.kt",
        "protocol/Commands.kt",
        "protocol/DeviceSession.kt",
        "save/DmgSaveRestore.kt",
        "restore/RestoreCoordinator.kt", // calls DeviceSession.restoreDmgSave, the single entry point
    )

    private fun codeLines(f: File): List<String> =
        f.readLines().filter { l ->
            val t = l.trim()
            !(t.startsWith("*") || t.startsWith("/*") || t.startsWith("//"))
        }

    @Test
    fun onlyTheRestorePathMentionsTheWriteOpcodeOrWriter() {
        assertTrue("run from the app module directory", srcRoot.isDirectory)
        val offenders = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter { f ->
            val rel = f.relativeTo(srcRoot).invariantSeparatorsPath
            rel !in allowed && codeLines(f).any { writePathTokens.containsMatchIn(it) }
        }.map { it.relativeTo(srcRoot).invariantSeparatorsPath }.toList()
        assertEquals("write path leaked into: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun theOpcodeConstantExistsInExactlyOnePlace_andNotInOp() {
        val holders = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> codeLines(f).any { Regex("""\b0xB3\b""").containsMatchIn(it) } }
            .map { it.relativeTo(srcRoot).invariantSeparatorsPath }.toList()
        assertEquals(listOf("protocol/DmgCartWriteAccess.kt"), holders)
        assertTrue("Op must not define the SRAM write", Op::class.java.declaredFields.none { it.name.contains("DMG") && it.name.contains("WRITE") && it.name.contains("SRAM") })
    }

    @Test
    fun backupClasses_doNotReferenceTheWriterType() {
        for (name in listOf("save/DmgSaveBackup.kt", "save/VerifiedRamReader.kt", "protocol/DmgCartAccess.kt", "cart/DmgMapper.kt", "cart/Mbc3Rtc.kt", "library/SaveLibrary.kt")) {
            val f = File(srcRoot, name)
            assertTrue(name, f.isFile)
            assertFalse(name, codeLines(f).any { writePathTokens.containsMatchIn(it) })
        }
    }

    @Test
    fun theUiNeverTouchesTheWriter() {
        val ui = File(srcRoot, "ui").listFiles { f -> f.extension == "kt" }!!
        for (f in ui) assertFalse(f.name, codeLines(f).any { writePathTokens.containsMatchIn(it) })
    }

    @Test
    fun dmgCartIo_hasNoMethodThatCarriesData() {
        val byteArrayParams = DmgCartIo::class.java.methods.filter { m -> m.parameterTypes.any { it == ByteArray::class.java } }
        assertEquals(emptyList<String>(), byteArrayParams.map { it.name })
        assertEquals(setOf("writeRegister", "clockToggle", "ramRead"), DmgCartIo::class.java.declaredMethods.map { it.name }.toSet())
    }

    @Test
    fun backupAccess_isNotAWriter() {
        assertFalse(DmgCartWriter::class.java.isAssignableFrom(DmgCartAccess::class.java))
        assertFalse(DmgCartIo::class.java.isAssignableFrom(DmgCartWriter::class.java))
        assertFalse(DmgCartWriter::class.java.isAssignableFrom(DmgSaveBackup::class.java))
    }

    // ---- the writer's own guards -------------------------------------------------------------

    private fun writer(): Pair<DmgCartWriter, FakeSerialLink> {
        val link = FakeSerialLink()
        val transport = Transport(link, link)
        link.enqueue(SPEC_FW_REPLY)
        val info = FirmwareInfo.read(transport, 1000)
        val w = Commands(transport, info).dmgCartWriter()
        link.writes.clear()
        return w to link
    }

    @Test
    fun writer_refusesAnythingOutsideTheRamWindow_andSendsNothing() {
        val (w, link) = writer()
        assertThrows(IllegalArgumentException::class.java) { w.writeRam(0x2000, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeRam(-1, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeRam(0x1FFF, byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeRam(0, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeRam(0, ByteArray(257)) }
        assertTrue(link.writes.isEmpty())
    }

    @Test
    fun registerWriteGuard_stillRefusesTheSramRange() {
        val link = FakeSerialLink()
        val transport = Transport(link, link)
        link.enqueue(SPEC_FW_REPLY)
        val access = Commands(transport, FirmwareInfo.read(transport, 1000)).dmgCartAccess()
        link.writes.clear()
        for (addr in listOf(0x8000, 0xA000, 0xBFFF)) assertThrows(IllegalArgumentException::class.java) { access.writeRegister(addr, 1) }
        assertTrue(link.writes.isEmpty())
    }

    // ---- GBA: erase / program / data writes exist in the restore-only writer ---------------------

    private val agbWriteTokens = Regex("""AgbCartWriter|agbCartWriter|AgbCartWriteAccess|AgbRtcWrite|AgbSaveRestore|restoreAgbSave""")

    private val agbWritePathFiles = setOf(
        "cart/AgbCartIo.kt", // declares AgbCartWriter next to the read-side interface
        "cart/AgbRtcWrite.kt",
        "protocol/AgbCartWriteAccess.kt",
        "protocol/Commands.kt",
        "protocol/DeviceSession.kt",
        "save/AgbSaveRestore.kt",
        "restore/RestoreCoordinator.kt",
    )

    private val backupSideAgbFiles = listOf(
        "cart/AgbRtcRead.kt", "protocol/AgbCartAccess.kt", "save/AgbSaveBackup.kt", "save/AgbVerifiedReader.kt",
        "save/AgbFlashId.kt", "save/AgbDetector.kt", "save/AgbSaveLayout.kt", "cart/AgbSaveDetection.kt", "cart/AgbSaveTypes.kt",
    )

    private fun allKt() = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }

    private fun rel(f: File) = f.relativeTo(srcRoot).invariantSeparatorsPath

    @Test
    fun agb_onlyTheRestorePathMentionsTheWriterTypes() {
        val offenders = allKt().filter { rel(it) !in agbWritePathFiles && codeLines(it).any { l -> agbWriteTokens.containsMatchIn(l) } }.map(::rel).toList()
        assertEquals("GBA write path leaked into: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun agb_saveWriteOpcodesExistOnlyInTheWriter() {
        fun holders(re: Regex) = allKt().filter { f -> codeLines(f).any { re.containsMatchIn(it) } }.map(::rel).sorted().toList()
        assertEquals(listOf("protocol/AgbCartWriteAccess.kt"), holders(Regex("""\b0x(C7|D2|D3)\b""")))
        // C4 / C6 are also the GPIO register addresses of the clock; the opcode use is in the writer only.
        assertEquals(listOf("cart/AgbCartIo.kt", "protocol/AgbCartWriteAccess.kt"), holders(Regex("""\b0x(C4|C6)\b""")))
        val io = codeLines(File(srcRoot, "cart/AgbCartIo.kt")).filter { Regex("""\b0x(C4|C6)\b""").containsMatchIn(it) }
        assertTrue(io.toString(), io.all { it.contains("GPIO_") })
        assertTrue("Op must not define the GBA write opcodes", Op::class.java.declaredFields.none { it.name.contains("WRITE") && it.name.contains("AGB") })
    }

    @Test
    fun agb_backupAndDetectionCodeNeverNamesAnEraseOrProgramValue() {
        val erase = Regex("""\b0x(80|30|10|A0)\b""")
        for (name in backupSideAgbFiles) {
            val f = File(srcRoot, name)
            assertTrue(name, f.isFile)
            val hits = codeLines(f).filter { erase.containsMatchIn(it) && !it.contains("STATUS_REGISTER") && !it.contains("status and") }
            assertEquals("$name names an erase / program value: $hits", emptyList<String>(), hits)
            assertFalse(name, codeLines(f).any { agbWriteTokens.containsMatchIn(it) })
        }
    }

    @Test
    fun agb_noVoltageOrDmgModeOpcodeOutsideThePlatformEnum() {
        val holders = allKt().filter { f -> codeLines(f).any { Regex("""SET_VOLTAGE_5V|SET_MODE_DMG|\b0xA5\b|\b0xA3\b""").containsMatchIn(it) } }.map(::rel).sorted().toList()
        assertEquals(listOf("protocol/Opcodes.kt"), holders)
        for (name in backupSideAgbFiles + listOf("protocol/AgbCartWriteAccess.kt", "save/AgbSaveRestore.kt")) {
            assertFalse(name, codeLines(File(srcRoot, name)).any { it.contains("GB_GBC") || it.contains("DMG") })
        }
    }

    @Test
    fun agbCartIo_hasNoMethodThatCarriesData() {
        val byteArrayParams = AgbCartIo::class.java.methods.filter { m -> m.parameterTypes.any { it == ByteArray::class.java } }
        assertEquals(emptyList<String>(), byteArrayParams.map { it.name })
        assertEquals(
            setOf(
                "saveRead", "eepromRead", "romRead", "selectFlashBank", "selectBootlegBank", "flashIdProbeFlip", "flashIdProbeRestore",
                "flashIdEnter", "flashIdExit", "flashIdRestoreBytes", "gpioWrite", "readGpioRtc", "sleep",
            ),
            AgbCartIo::class.java.declaredMethods.map { it.name }.toSet(),
        )
        assertFalse(AgbCartWriter::class.java.isAssignableFrom(AgbCartAccess::class.java))
        assertFalse(AgbCartIo::class.java.isAssignableFrom(AgbCartWriter::class.java))
    }

    private fun agbAccess(): Triple<AgbCartIo, AgbCartWriter, FakeSerialLink> {
        val link = FakeSerialLink()
        val transport = Transport(link, link)
        link.enqueue(SPEC_FW_REPLY)
        val commands = Commands(transport, FirmwareInfo.read(transport, 1000))
        link.writes.clear()
        return Triple(commands.agbCartAccess(), commands.agbCartWriter(), link)
    }

    @Test
    fun agbBackupAccess_refusesNonGpioRomWritesAndBadBanks_andSendsNothing() {
        val (io, _, link) = agbAccess()
        for (addr in listOf(0x0, 0x1000, 0xC5, 0x8000000, 0x1000000)) assertThrows(IllegalArgumentException::class.java) { io.gpioWrite(addr, 1) }
        assertThrows(IllegalArgumentException::class.java) { io.selectFlashBank(2) }
        assertThrows(IllegalArgumentException::class.java) { io.selectBootlegBank(-1) }
        assertThrows(IllegalArgumentException::class.java) { io.eepromRead(3, 0, 8) }
        assertTrue(link.writes.isEmpty())
    }

    @Test
    fun agbWriter_refusesAnythingOutsideTheSaveWindow_andSendsNothing() {
        val (_, w, link) = agbAccess()
        assertThrows(IllegalArgumentException::class.java) { w.writeSram(0xFFFF, byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeSram(0, ByteArray(257)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeSram(0, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeEeprom(3, 0, ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeEeprom(1, 0, ByteArray(7)) }
        assertThrows(IllegalArgumentException::class.java) { w.writeFlash(0xFFFF, ByteArray(2), false) }
        assertThrows(IllegalArgumentException::class.java) { w.writeFlash(64, ByteArray(128), true) }
        assertThrows(IllegalArgumentException::class.java) { w.eraseFlashSector(0x10000) }
        assertThrows(IllegalArgumentException::class.java) { w.writeGpio(0x1000, 0) }
        assertTrue(link.writes.isEmpty())
    }
}

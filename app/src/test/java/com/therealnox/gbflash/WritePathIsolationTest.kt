package com.therealnox.gbflash

import com.therealnox.gbflash.cart.DmgCartIo
import com.therealnox.gbflash.cart.DmgCartWriter
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
}

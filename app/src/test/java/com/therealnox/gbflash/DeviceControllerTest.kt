package com.therealnox.gbflash

import com.therealnox.gbflash.device.BackupUi
import com.therealnox.gbflash.device.ConnectionStatus
import com.therealnox.gbflash.device.DeviceController
import com.therealnox.gbflash.device.DeviceState
import com.therealnox.gbflash.device.OperationAction
import com.therealnox.gbflash.device.RestoreUi
import com.therealnox.gbflash.device.StatusBarState
import com.therealnox.gbflash.device.statusBarState
import com.therealnox.gbflash.library.GameInfo
import com.therealnox.gbflash.library.NewBackup
import com.therealnox.gbflash.library.SaveLibrary
import com.therealnox.gbflash.protocol.CartPlatform
import com.therealnox.gbflash.protocol.DeviceSession
import com.therealnox.gbflash.usb.ReaderGateway
import com.therealnox.gbflash.usb.ReaderRef
import com.therealnox.gbflash.usb.SerialLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val link = FakeSerialLink()
    private val fw = FakeFirmware(link)

    private class FakeGateway(val link: SerialLink) : ReaderGateway {
        var onDetached: (ReaderRef) -> Unit = {}
        val ref = ReaderRef(7, "fake")

        override fun start(onPermissionResult: (ReaderRef, Boolean) -> Unit, onDetached: (ReaderRef) -> Unit) {
            this.onDetached = onDetached
        }

        override fun stop() = Unit

        override fun find(): ReaderRef = ref

        override fun hasPermission(reader: ReaderRef) = true

        override fun requestPermission(reader: ReaderRef) = Unit

        override fun open(reader: ReaderRef): SerialLink = link
    }

    private val gateway = FakeGateway(link)

    private fun TestScope.controller(idleMs: Long = 180_000): DeviceController {
        val d = UnconfinedTestDispatcher(testScheduler)
        return DeviceController(
            gateway = gateway,
            library = SaveLibrary(tmp.newFolder()),
            scope = CoroutineScope(d),
            io = Dispatchers.Unconfined,
            openSession = { DeviceSession.open(it, clock = link, io = Dispatchers.Unconfined) },
            idlePowerOffMillis = idleMs,
        )
    }

    // ---- platform choice is never remembered ---------------------------------------------------

    @Test
    fun noPlatformIsPreselected_afterConnect() = runTest {
        val c = controller()
        c.refresh()
        assertEquals(ConnectionStatus.CONNECTED, c.state.value.status)
        assertNull(c.state.value.platform)
        assertFalse(c.state.value.cartPowered)
        assertEquals(StatusBarState.ConnectedNoPlatform, statusBarState(c.state.value))
    }

    @Test
    fun confirmPlatform_readsHeaderAtThatVoltage_andIdentifiesTheGame() = runTest {
        fw.rom = dmgHeader(title = "ZELDA".toByteArray())
        val c = controller()
        c.refresh()
        c.confirmPlatform(CartPlatform.GB_GBC)
        val s = c.state.value
        assertEquals(CartPlatform.GB_GBC, s.platform)
        assertTrue(s.cartPowered)
        assertNotNull(s.header)
        assertEquals("ZELDA", s.cartGame?.title)
        assertEquals(StatusBarState.CartPowered(CartPlatform.GB_GBC), statusBarState(s))
        assertTrue(fw.voltageAtPowerOn.isNotEmpty() && fw.voltageAtPowerOn.all { it == "5V" })
    }

    @Test
    fun powerOff_forgetsPlatformAndHeader() = runTest {
        fw.rom = dmgHeader()
        val c = controller()
        c.refresh()
        c.confirmPlatform(CartPlatform.GB_GBC)
        c.powerOffCart()
        val s = c.state.value
        assertNull(s.platform)
        assertNull(s.header)
        assertNull(s.cartGame)
        assertFalse(s.cartPowered)
        assertFalse(fw.powered)
    }

    @Test
    fun disconnect_forgetsPlatform() = runTest {
        fw.rom = dmgHeader()
        val c = controller()
        c.refresh()
        c.confirmPlatform(CartPlatform.GB_GBC)
        c.disconnect()
        assertNull(c.state.value.platform)
        assertEquals(ConnectionStatus.NO_DEVICE, c.state.value.status)
        assertTrue(link.closed)
    }

    @Test
    fun detach_forgetsPlatform_andReconnectStartsEmpty() = runTest {
        fw.rom = dmgHeader()
        val c = controller()
        c.refresh()
        c.confirmPlatform(CartPlatform.GB_GBC)
        gateway.onDetached(gateway.ref)
        assertNull(c.state.value.platform)
        assertEquals(StatusBarState.NoReader("USB device detached"), statusBarState(c.state.value))
        c.refresh()
        assertNull(c.state.value.platform)
    }

    @Test
    fun changingPlatform_neverChangesVoltageWhilePowered() = runTest {
        fw.rom = dmgHeader()
        val c = controller()
        c.refresh()
        c.confirmPlatform(CartPlatform.GB_GBC)
        fw.rom = agbHeader()
        c.powerOffCart()
        c.confirmPlatform(CartPlatform.GBA)
        assertEquals(CartPlatform.GBA, c.state.value.platform)
        assertFalse(fw.voltageChangedWhilePowered)
        assertEquals(listOf("5V", "3.3V"), fw.voltageAtPowerOn.distinct())
    }

    // ---- idle power-off ------------------------------------------------------------------------

    @Test
    fun idle_poweredCartIsSwitchedOff_andPlatformForgotten() = runTest {
        fw.rom = dmgHeader()
        val c = controller(idleMs = 1000)
        c.refresh()
        c.confirmPlatform(CartPlatform.GB_GBC)
        advanceTimeBy(900)
        assertTrue(c.state.value.cartPowered)
        advanceTimeBy(200)
        assertFalse(c.state.value.cartPowered)
        assertNull(c.state.value.platform)
        assertTrue(c.state.value.info!!.contains("idle"))
    }

    // ---- status bar mapping --------------------------------------------------------------------

    @Test
    fun statusBar_mapsEveryHardwareState() {
        assertEquals(StatusBarState.NoReader(null), statusBarState(DeviceState()))
        assertEquals(StatusBarState.PermissionNeeded, statusBarState(DeviceState(status = ConnectionStatus.NEEDS_PERMISSION)))
        assertEquals(StatusBarState.Connecting, statusBarState(DeviceState(status = ConnectionStatus.CONNECTING)))
        assertEquals(StatusBarState.ConnectedNoPlatform, statusBarState(DeviceState(status = ConnectionStatus.CONNECTED)))
        assertEquals(
            StatusBarState.CartPowered(CartPlatform.GBA),
            statusBarState(DeviceState(status = ConnectionStatus.CONNECTED, platform = CartPlatform.GBA, cartPowered = true)),
        )
        assertEquals(
            StatusBarState.Error("boom"),
            statusBarState(DeviceState(status = ConnectionStatus.FAILED, statusDetail = "boom")),
        )
    }

    @Test
    fun statusBar_operationOverridesConnectionState_withProgressAndCancel() {
        val running = DeviceState(
            status = ConnectionStatus.CONNECTED, cartPowered = true, busy = true,
            backup = BackupUi.Running("POKEMON BLUE", 8192L to 65536L),
        )
        val op = statusBarState(running) as StatusBarState.Operation
        assertEquals("Backing up POKEMON BLUE", op.label)
        assertEquals(0.125f, op.fraction!!, 0.0001f)
        assertEquals(OperationAction.CANCEL, op.action)
        val busy = statusBarState(
            DeviceState(status = ConnectionStatus.CONNECTED, busy = true, busyLabel = "Reading GB / GBC header"),
        ) as StatusBarState.Operation
        assertNull(busy.fraction)
        assertEquals(OperationAction.NONE, busy.action)
    }

    @Test
    fun restoreUnavailableReason_explainsWhy() {
        assertFalse(DeviceState().restoreAvailable)
        assertTrue(DeviceState().restoreUnavailableReason!!.contains("Connect the reader"))
        assertTrue(DeviceState(status = ConnectionStatus.CONNECTED).restoreUnavailableReason!!.contains("cartridge type"))
    }

    @Test
    fun restoreStartedWithoutCartridge_isRefused() = runTest {
        val c = controller()
        c.refresh()
        val lib = SaveLibrary(tmp.newFolder())
        val e = lib.store(
            GameInfo("DMG", "X", "DMG_X_0001", "", null, 1, 1, null),
            NewBackup(ByteArray(0x8000), null, null, false, "", "", true, null),
        )
        c.startRestore(e)
        assertNull(c.state.value.restore)
        assertFalse(c.state.value.restore is RestoreUi.Loading)
    }
}

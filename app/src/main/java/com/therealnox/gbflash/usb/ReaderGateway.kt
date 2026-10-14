package com.therealnox.gbflash.usb

import android.hardware.usb.UsbDevice

/** A plugged-in reader, independent of the Android class so the device logic can run in JVM tests. */
data class ReaderRef(val id: Int, val name: String)

/** What [com.therealnox.gbflash.device.DeviceController] needs from the USB stack. */
interface ReaderGateway {
    /** Starts listening for the permission result and for detach events. Idempotent. */
    fun start(onPermissionResult: (ReaderRef, Boolean) -> Unit, onDetached: (ReaderRef) -> Unit)

    fun stop()

    fun find(): ReaderRef?

    fun hasPermission(reader: ReaderRef): Boolean

    fun requestPermission(reader: ReaderRef)

    /** Blocking; call on an I/O thread. */
    fun open(reader: ReaderRef): SerialLink
}

/** [ReaderGateway] on top of [UsbConnector]. */
class UsbReaderGateway(private val connector: UsbConnector) : ReaderGateway {
    private val devices = mutableMapOf<Int, UsbDevice>()

    private fun UsbDevice.ref(): ReaderRef = ReaderRef(deviceId, deviceName).also { devices[deviceId] = this }

    override fun start(onPermissionResult: (ReaderRef, Boolean) -> Unit, onDetached: (ReaderRef) -> Unit) {
        connector.start(
            onPermissionResult = { d, granted -> onPermissionResult(d.ref(), granted) },
            onDetached = { d -> onDetached(d.ref()) },
        )
    }

    override fun stop() = connector.stop()

    override fun find(): ReaderRef? = connector.findDevice()?.ref()

    override fun hasPermission(reader: ReaderRef): Boolean = devices[reader.id]?.let(connector::hasPermission) ?: false

    override fun requestPermission(reader: ReaderRef) {
        devices[reader.id]?.let(connector::requestPermission)
    }

    override fun open(reader: ReaderRef): SerialLink =
        connector.open(devices[reader.id] ?: throw java.io.IOException("The reader is no longer attached"))
}

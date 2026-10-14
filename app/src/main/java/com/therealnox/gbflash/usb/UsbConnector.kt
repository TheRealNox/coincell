package com.therealnox.gbflash.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/** Finds the GBFlash's CH340, handles the permission dialog and opens it at 2 Mbaud 8N1. */
class UsbConnector(context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var permissionCallback: ((UsbDevice, Boolean) -> Unit)? = null
    private var detachCallback: ((UsbDevice) -> Unit)? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    permissionCallback?.invoke(device, granted)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> detachCallback?.invoke(device)
            }
        }
    }

    /** Registers receivers for the permission result and for device detach. Idempotent. */
    fun start(
        onPermissionResult: (UsbDevice, Boolean) -> Unit,
        onDetached: (UsbDevice) -> Unit,
    ) {
        permissionCallback = onPermissionResult
        detachCallback = onDetached
        if (registered) return
        // The permission result is delivered through our own PendingIntent, so a not-exported
        // receiver is correct. DETACHED is a system broadcast, hence exported.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
    }

    fun stop() {
        if (registered) {
            appContext.unregisterReceiver(receiver)
            registered = false
        }
        permissionCallback = null
        detachCallback = null
    }

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == VID && it.productId == PID }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice) {
        // The system fills in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED, so the PendingIntent must
        // be FLAG_MUTABLE; on API 34+ a mutable PendingIntent must wrap an explicit intent
        // (setPackage pins it to our own app).
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    /** Opens the device at 2,000,000 baud 8N1. Throws [IOException] with a readable message. */
    fun open(device: UsbDevice): SerialLink {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw IOException("No USB-serial driver for ${device.deviceName}")
        val connection = usbManager.openDevice(device)
            ?: throw IOException("Could not open USB device (permission missing?)")
        val port = driver.ports.firstOrNull() ?: throw IOException("Driver exposes no ports")
        try {
            port.open(connection)
            port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // pyserial asserts DTR and RTS on open and FlashGBX never changes them (spec 1.1 / 9.2 Q2).
            try {
                port.dtr = true
                port.rts = true
            } catch (_: UnsupportedOperationException) {
                // Driver cannot drive the control lines; nothing to do.
            }
        } catch (e: Exception) {
            try {
                port.close()
            } catch (_: IOException) {
                // Ignore secondary failure.
            }
            throw IOException("Opening serial port failed: ${e.message}", e)
        }
        return UsbSerialLink(port)
    }

    companion object {
        const val VID = 0x1A86
        const val PID = 0x7523
        const val BAUD = 2_000_000
        const val ACTION_USB_PERMISSION = "com.therealnox.gbflash.USB_PERMISSION"

        @Suppress("DEPRECATION")
        fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) {
                getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }
}

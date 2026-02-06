package com.sanvar.ble.internal

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import com.sanvar.ble.sanner.ScanManager

/**
 * 扫描辅助器 - 内部类
 */
internal class ScanAssistant(
    private val targetMac: String,
    private val onDeviceFound: (BluetoothDevice, Int) -> Unit
) {
    private val scanManager = ScanManager.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isRegistered = false
    private var timeoutRunnable: Runnable? = null

    private val scanCallback = object : ScanManager.ScanCallback {
        override fun onScanDevice(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            device ?: return
            if (device.address.equals(targetMac, ignoreCase = true)) {
                onDeviceFound(device, rssi)
            }
        }
    }

    fun start(timeout: Long = 0) {
        if (isRegistered) return

        scanManager.startScan(scanCallback)
        isRegistered = true

        if (timeout > 0) {
            timeoutRunnable = Runnable { stop() }
            mainHandler.postDelayed(timeoutRunnable!!, timeout)
        }
    }

    fun stop() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        if (isRegistered) {
            scanManager.stopScan(scanCallback)
            isRegistered = false
        }
    }
}
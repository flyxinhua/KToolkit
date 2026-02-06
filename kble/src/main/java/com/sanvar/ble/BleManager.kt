package com.sanvar.ble

import android.content.Context
import android.content.pm.PackageManager
import com.sanvar.ble.monitor.AppLifecycleMonitor
import com.sanvar.ble.monitor.BluetoothStateMonitor
import com.sanvar.ble.utils.BleLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙SDK管理器 - 单例
 */
class BleManager private constructor() {

    private lateinit var context: Context
    private var isInitialized = false

    private val devices = ConcurrentHashMap<String, BleDevice>()

    private var lifecycleCallback: AppLifecycleMonitor.Callback? = null
    private var bluetoothCallback: BluetoothStateMonitor.Callback? = null

    companion object {
        val instance: BleManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BleManager() }
    }

    fun init(context: Context, enableLog: Boolean = true): BleManager {
        if (isInitialized) return this

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw IllegalStateException("BLE not supported")
        }

        this.context = context.applicationContext
        BleLogger.setEnabled(enableLog)
        BluetoothStateMonitor.instance.init(this.context)

        setupListeners()

        isInitialized = true
        BleLogger.d("BleManager initialized")
        return this
    }

    private fun setupListeners() {
        lifecycleCallback = AppLifecycleMonitor.Callback { isForeground ->
            devices.values.forEach { it.onAppForegroundChanged(isForeground) }
        }
        AppLifecycleMonitor.instance.register(lifecycleCallback!!)

        bluetoothCallback = BluetoothStateMonitor.Callback { enabled ->
            devices.values.forEach { it.onBluetoothStateChanged(enabled) }
        }
        BluetoothStateMonitor.instance.register(bluetoothCallback!!)
    }

    fun getOrPutDevice(config: BleConfig): BleDevice {
        checkInitialized()
        val mac = config.macAddress.uppercase()
        return devices.getOrPut(mac) { BleDevice(context, config) }
    }

    fun getDevice(macAddress: String): BleDevice? = devices[macAddress.uppercase()]

    fun removeDevice(macAddress: String) {
        devices.remove(macAddress.uppercase())?.release()
    }

    fun removeAllDevices() {
        devices.values.forEach { it.release() }
        devices.clear()
    }

    fun getAllDevices(): List<BleDevice> = devices.values.toList()

    fun getConnectedDevices(): List<BleDevice> = devices.values.filter { it.isConnected() }

    fun isBluetoothEnabled(): Boolean = BluetoothStateMonitor.instance.isBluetoothEnabled()

    fun isAppForeground(): Boolean = AppLifecycleMonitor.instance.isForeground()

    private fun checkInitialized() {
        check(isInitialized) { "BleManager not initialized" }
    }
}
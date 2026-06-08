package com.sanvar.ble

import android.content.Context
import android.content.pm.PackageManager
import com.sanvar.ble.monitor.AppLifecycleMonitor
import com.sanvar.ble.monitor.BluetoothStateMonitor
import com.sanvar.ble.utils.BleLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙 SDK 管理器 - 单例模式
 *
 * 负责全局初始化、设备管理、生命周期协调等核心功能。
 */
class BleManager private constructor() {

    private val logger = BleLogger.withTag("BleManager")
    private lateinit var context: Context
    private var isInitialized = false

    private val devices = ConcurrentHashMap<String, BleDevice>()

    private var lifecycleCallback: AppLifecycleMonitor.Callback? = null
    private var bluetoothCallback: BluetoothStateMonitor.Callback? = null

    companion object {
        val instance: BleManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BleManager() }
    }

    /**
     * 初始化蓝牙管理器
     * @param context 全局上下文
     * @param enableLog 是否启用日志，默认为 true
     * @return BleManager 实例
     * @throws IllegalStateException 如果设备不支持 BLE
     */
    fun init(context: Context, enableLog: Boolean = true): BleManager {
        if (isInitialized) {
            logger.d("BleManager has already been initialized, skipping.")
            return this
        }

        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            val errorMsg = "BLE not supported on this device"
            logger.e(errorMsg)
            throw IllegalStateException(errorMsg)
        }

        this.context = context.applicationContext
        BleLogger.setEnabled(enableLog)
        BluetoothStateMonitor.instance.init(this.context)

        setupListeners()

        isInitialized = true
        logger.i("BleManager initialized successfully (enableLog = $enableLog)")
        return this
    }

    /**
     * 设置自定义日志打印器
     *
     * @param printer 日志输出回调函数，接收日志级别、标签和消息
     */
    fun setLogPrinter(printer: (priority: Int, tag: String, message: String) -> Unit) {
        BleLogger.setLogPrinter(printer)
        logger.d("Custom log printer set.")
    }

    /**
     * 设置全局最小日志输出级别
     *
     * @param priority 日志级别，如 [android.util.Log.INFO]、[android.util.Log.DEBUG]
     */
    fun setMinPriority(priority: Int) {
        BleLogger.setMinPriority(priority)
        logger.d("Min log priority set to $priority.")
    }

    /**
     * 配置生命周期和蓝牙状态监听器
     *
     * 内部方法，注册应用前后台监听和蓝牙状态监听，实现智能连接管理。
     */
    private fun setupListeners() {
        lifecycleCallback = AppLifecycleMonitor.Callback { isForeground ->
            logger.d("App lifecycle changed: isForeground = $isForeground")
            devices.values.forEach { it.onAppForegroundChanged(isForeground) }
        }
        AppLifecycleMonitor.instance.register(lifecycleCallback!!)

        bluetoothCallback = BluetoothStateMonitor.Callback { enabled ->
            logger.d("Bluetooth state changed: enabled = $enabled")
            devices.values.forEach { it.onBluetoothStateChanged(enabled) }
        }
        BluetoothStateMonitor.instance.register(bluetoothCallback!!)
    }

    /**
     * 获取或创建指定设备的实例
     *
     * @param config 设备连接配置，包含 MAC 地址和连接参数
     * @return 对应的 [BleDevice] 实例，如果已存在则返回现有实例
     * @throws IllegalStateException 如果 BleManager 尚未初始化
     */
    fun getOrPutDevice(config: BleConfig): BleDevice {
        checkInitialized()
        val mac = config.macAddress.uppercase()
        logger.d("getOrPutDevice: MAC = $mac")
        return devices.getOrPut(mac) {
            logger.i("Creating new BleDevice instance for MAC: $mac")
            BleDevice(context, config)
        }
    }

    /**
     * 根据 MAC 地址查找已管理的设备实例
     *
     * @param macAddress 设备的 MAC 地址
     * @return 对应的 [BleDevice] 实例，如果未找到则返回 null
     */
    fun getDevice(macAddress: String): BleDevice? {
        val mac = macAddress.uppercase()
        val device = devices[mac]
        logger.d("getDevice: MAC = $mac, found = ${device != null}")
        return device
    }

    /**
     * 移除并释放指定设备
     * @param macAddress 设备的 MAC 地址
     */
    fun removeDevice(macAddress: String) {
        val mac = macAddress.uppercase()
        logger.i("removeDevice: MAC = $mac")
        devices.remove(mac)?.let {
            it.release()
            logger.d("Device $mac released and removed.")
        } ?: logger.w("removeDevice: Device $mac not found.")
    }

    /**
     * 释放并移除所有已管理的设备
     */
    fun removeAllDevices() {
        val size = devices.size
        logger.i("removeAllDevices: clearing all $size devices.")
        devices.values.forEach { it.release() }
        devices.clear()
    }

    /**
     * 获取所有已管理的设备
     */
    fun getAllDevices(): List<BleDevice> = devices.values.toList()

    /**
     * 获取所有当前处于连接状态的设备
     */
    fun getConnectedDevices(): List<BleDevice> = devices.values.filter { it.isConnected() }

    /**
     * 检查当前蓝牙开关是否开启
     */
    fun isBluetoothEnabled(): Boolean = BluetoothStateMonitor.instance.isBluetoothEnabled()

    /**
     * 检查当前应用是否处于前台
     */
    fun isAppForeground(): Boolean = AppLifecycleMonitor.instance.isForeground()

    /**
     * 检查初始化状态
     */
    private fun checkInitialized() {
        check(isInitialized) { "BleManager not initialized" }
    }
}
package com.sanvar.ble.internal

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.sanvar.ble.BleConfig
import com.sanvar.ble.ConnectionPriority
import com.sanvar.ble.ConnectionState
import com.sanvar.ble.DisconnectReason
import com.sanvar.ble.monitor.AppLifecycleMonitor
import com.sanvar.ble.monitor.BluetoothStateMonitor
import com.sanvar.ble.sanner.ScanManager
import com.sanvar.ble.utils.BleHelper
import com.sanvar.ble.utils.BleLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** 内部回调接口，专门供 BleDevice 监听 */
internal interface InnerGattCallback {
    fun onConnectionStateChange(
        state: ConnectionState,
        reason: DisconnectReason?,
        device: BluetoothDevice?
    )

    fun onServicesDiscovered(gatt: BluetoothGatt, status: Int)
    fun onMtuChanged(mtu: Int, status: Int)
    fun onCharacteristicRead(char: BluetoothGattCharacteristic, value: ByteArray, status: Int)
    fun onCharacteristicWrite(char: BluetoothGattCharacteristic, status: Int)
    fun onCharacteristicChanged(char: BluetoothGattCharacteristic, value: ByteArray)
    fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int)
}

/**
 * 蓝牙连接器 - 负责底层 GATT 连接管理
 *
 * 处理连接建立、断开、服务发现等底层操作，将原始 GATT 回调转发给 BleDevice。
 * 支持两种连接模式：
 * 1. 扫描辅助连接：先扫描设备，找到后立即连接
 * 2. 自动连接：通过系统自动连接机制后台等待
 *
 * @param context Android 上下文
 * @param config 蓝牙设备配置
 * @param workerHandler 工作线程 Handler，用于执行耗时操作
 * @param listener 内部回调监听器，用于通知 BleDevice 状态变化
 */
@SuppressLint("MissingPermission")
internal class BleConnector(
    private val context: Context,
    private val config: BleConfig,
    private val workerHandler: Handler,
    private val listener: InnerGattCallback
) {
    private val TAG = "BleConnector"
    private val logger = BleLogger.withTag(TAG + "_${config.macAddress}")

    /** 蓝牙适配器，用于获取远程设备和检查蓝牙状态 */
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    /** 当前 GATT 连接实例，连接成功后设置，断开后清空 */
    var bluetoothGatt: BluetoothGatt? = null
        private set

    /** 当前连接状态，初始为 DISCONNECTED */
    var connectionState = ConnectionState.DISCONNECTED
        private set

    /**
     * 计算连接超时时间
     *
     * 取配置的超时时间和 30 秒中的较大值，确保最小超时时间为 30 秒。
     *
     * @return 连接超时时间（毫秒）
     */
    private fun connectTimeOut() = max(config.connectionTimeout, 30 * 1000)


    /** 连接超时任务，用于处理连接超时 */
    private var connectionTimeoutRunnable: Runnable? = null

    /** 是否是用户主动断开连接 */
    private var isUserDisconnect = false

    /** 是否已通过扫描找到设备（原子操作，线程安全） */
    private val hasFoundDevice = AtomicBoolean(false)

    /** 主线程 Handler，用于 UI 线程操作 */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * GATT 回调对象，处理底层蓝牙事件
     *
     * 包含连接状态变化、服务发现、MTU 变化、特征值读写等回调。
     * 所有回调都会转发给 listener（BleDevice）进行处理。
     */
    private val gattCallback = object : BluetoothGattCallback() {
        /**
         * 连接状态变化回调
         *
         * @param gatt GATT 连接实例
         * @param status 状态码，GATT_SUCCESS 表示成功
         * @param newState 新的连接状态：STATE_CONNECTED 或 STATE_DISCONNECTED
         */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logger.w("onConnectionStateChange: status:$status , newState:$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 连接成功，取消超时任务
                    cancelTimeout()
                    // 更新状态为 CONNECTED
                    updateState(ConnectionState.CONNECTED, null, gatt.device)
                    // 标记未在发送大数据状态
                    BleHelper.setSendingBigData(config.macAddress, false)
                    // 延迟 300ms 后开始发现服务（给系统一点时间稳定）
                    mainHandler.postDelayed({
                        // 确保状态还是 CONNECTED 才继续
                        if (ConnectionState.CONNECTED == connectionState) {
                            updateState(ConnectionState.DISCOVERING, null, gatt.device)
                            gatt.discoverServices()
                        }
                    }, 300)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    // 连接断开，取消超时任务
                    cancelTimeout()
                    // 根据不同情况判断断开原因
                    val reason = when {
                        isUserDisconnect -> DisconnectReason.USER_REQUEST      // 用户主动断开
                        connectionState == ConnectionState.CONNECTING -> DisconnectReason.CONNECT_FAILED  // 连接过程中断开
                        status != BluetoothGatt.GATT_SUCCESS -> DisconnectReason.ERROR  // 错误导致断开
                        else -> DisconnectReason.DEVICE_DISCONNECT  // 设备主动断开
                    }

                    // 如果是非正常断开（status != 0），刷新 GATT 缓存
                    if (status != 0) {
                        refreshDeviceCache(gatt)
                    }

                    // 清理主线程任务和 GATT 资源
                    mainHandler.removeCallbacksAndMessages(null)
                    cleanupGatt(gatt)
                    updateState(ConnectionState.DISCONNECTED, reason, null)

                    // 错误码 8 表示连接超时或设备不可达，需要上报
                    if ((status == 8)) {
                        logger.e("断开连接，错误码为: $status")
                        BleHelper.reportSuspiciousStatus8(context, config.macAddress)
                    }
                }
            }
        }

        /**
         * 服务发现完成回调
         *
         * @param gatt GATT 连接实例
         * @param status 状态码，GATT_SUCCESS 表示成功
         */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            listener.onServicesDiscovered(gatt, status)
        }

        /**
         * MTU 协商完成回调
         *
         * @param gatt GATT 连接实例
         * @param mtu 协商后的 MTU 值
         * @param status 状态码，GATT_SUCCESS 表示成功
         */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            listener.onMtuChanged(mtu, status)
        }

        /**
         * 特征值读取完成回调（Android 12+ 新 API）
         */
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            listener.onCharacteristicRead(characteristic, value, status)
        }

        /**
         * 特征值读取完成回调（旧 API，兼容旧版本）
         */
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            listener.onCharacteristicRead(characteristic, characteristic.value, status)
        }

        /**
         * 特征值写入完成回调
         */
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            listener.onCharacteristicWrite(characteristic, status)
        }

        /**
         * 特征值变化通知回调（Android 12+ 新 API）
         */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            listener.onCharacteristicChanged(characteristic, value)
        }

        /**
         * 特征值变化通知回调（旧 API，兼容旧版本）
         */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            listener.onCharacteristicChanged(characteristic, characteristic.value)
        }

        /**
         * 描述符写入完成回调
         */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            listener.onDescriptorWrite(descriptor, status)
        }
    }

    /**
     * 扫描回调，用于扫描辅助连接模式
     *
     * 当扫描到目标设备时，停止扫描并发起连接。
     * 使用 AtomicBoolean 确保只处理一次找到设备的事件。
     */
    private var scanCallback = object : ScanManager.ScanCallbackWrapper {
        override fun onScanResult(result: ScanResult) {
            // 检查是否是目标设备，并且是第一次找到（原子操作保证线程安全）
            if (result.device.address == config.macAddress
                && hasFoundDevice.compareAndSet(false, true)
            ) {
                logger.i("Found tag device , ${result.device.name}")
                // 停止扫描
                ScanManager.stopScan(this)
                // 通知扫描管理器有连接请求，暂停扫描一段时间
                ScanManager.onConnectionSignal()
                // 延迟 200ms 后发起连接（给扫描停止一点时间）
                mainHandler.postDelayed({ connectGatt(result.device) }, 200)
            }
        }
    }

    /**
     * 刷新设备缓存（通过反射调用隐藏 API）
     *
     * 当连接出现问题时，刷新 GATT 缓存可以解决一些连接问题。
     *
     * @param gatt GATT 连接实例
     */
    private fun refreshDeviceCache(gatt: BluetoothGatt) {
        try {
            // 调用隐藏的 refresh() 方法
            val method = gatt.javaClass.getMethod("refresh")
            method.invoke(gatt)
        } catch (e: Exception) {
            // 反射调用可能失败，静默处理
            e.printStackTrace()
        }
    }

    /**
     * 发起蓝牙连接
     *
     * 连接流程：
     * 1. 检查蓝牙是否开启
     * 2. 检查当前状态是否允许连接
     * 3. 检查设备是否已在系统连接列表中
     * 4. 如果在系统列表中，直接连接；否则启动扫描辅助连接
     */
    fun connect() {
        // 蓝牙未开启，直接返回 DISCONNECTED 状态
        if (!bluetoothAdapter.isEnabled) {
            updateState(ConnectionState.DISCONNECTED, null, null)
            return
        }

        // 如果已经在连接中或已连接，不重复连接
        if (connectionState >= ConnectionState.CONNECTING) return

        // 标记不是用户主动断开
        isUserDisconnect = false
        // 更新状态为 CONNECTING
        updateState(ConnectionState.CONNECTING, null, null)

        // 初始化扫描管理器
        ScanManager.init(context)

        // 检查设备是否已在系统连接列表或已配对列表中
        val isInSystem = ScanManager.getSystemDevices().any { it.address == config.macAddress }
        if (isInSystem) {
            logger.i("tag device ${config.macAddress} is in system")
            // 通知扫描管理器暂停扫描
            ScanManager.onConnectionSignal()
            // 直接连接（不需要扫描）
            connectGatt(bluetoothAdapter.getRemoteDevice(config.macAddress))
            return
        }

        // 设备不在系统列表中，使用扫描辅助连接模式

        // 先清空之前的自动连接任务
        mainHandler.removeCallbacks(autoConnectRunnable)
        // 重置找到设备的标记
        hasFoundDevice.set(false)
        // 启动扫描
        ScanManager.startScan(scanCallback)
        // 启动连接超时任务
        startTimeout()

        // 注释：原逻辑中有半程检测机制（超时一半时间后自动切换到 autoConnect）
        // 目前已注释掉，如需启用可以取消注释
        // mainHandler.postDelayed(autoConnectRunnable, connectTimeOut() / 2L)
    }

    /**
     * 执行 GATT 连接
     *
     * @param device 蓝牙设备
     * @param autoConnect 是否使用自动连接模式（后台等待），默认为 false（直接连接）
     */
    private fun connectGatt(device: BluetoothDevice, autoConnect: Boolean = false) {
        // 先清理之前的 GATT 连接
        cleanupGatt(bluetoothGatt)
        // 延迟 300ms 后执行连接（避免频繁连接）
        mainHandler.postDelayed({
            bluetoothGatt = device.connectGatt(
                context,
                autoConnect,           // autoConnect: true 表示后台等待模式
                gattCallback,          // GATT 回调
                BluetoothDevice.TRANSPORT_LE  // 指定使用 LE 传输
            )
        }, 300)
    }

    /**
     * 断开蓝牙连接
     *
     * 用户主动断开连接，会标记 isUserDisconnect 为 true，
     * 这样在 GATT 回调中会判断为用户主动断开。
     */
    fun disconnect() {
        logger.i("disconnect from user!")
        // 标记为用户主动断开
        isUserDisconnect = true
        // 取消自动连接任务
        mainHandler.removeCallbacks(autoConnectRunnable)
        // 取消连接超时任务
        cancelTimeout()
        // 停止扫描
        ScanManager.stopScan(scanCallback)

        // 如果有活跃的 GATT 连接
        bluetoothGatt?.let { gatt ->
            // 更新状态为 DISCONNECTING
            updateState(ConnectionState.DISCONNECTING, null, gatt.device)
            try {
                // 调用 GATT 断开方法
                gatt.disconnect()
            } catch (e: Exception) {
                // 静默处理异常
            } finally {
                // 清理 GATT 资源
                cleanupGatt(gatt)
                // 更新状态为 DISCONNECTED
                updateState(ConnectionState.DISCONNECTED, DisconnectReason.USER_REQUEST, null)
            }
        } ?: updateState(ConnectionState.DISCONNECTED, DisconnectReason.USER_REQUEST, null)
    }

    /**
     * 自动连接兜底任务
     *
     * 当扫描辅助连接超时一半时间后，如果还没找到设备，
     * 自动切换到 autoConnect=true 模式，让系统后台等待连接。
     *
     * 目前此任务已被注释掉，如需启用可以在 connect() 方法中取消注释。
     */
    private val autoConnectRunnable = Runnable {
        // 如果状态不是 CONNECTING 或者用户主动断开，直接返回
        if (connectionState != ConnectionState.CONNECTING || isUserDisconnect) {
            return@Runnable
        }

        // 如果已经找到设备，正在连接中，不需要兜底
        if (hasFoundDevice.get()) {
            logger.i("半程检测：设备已扫到，正在高速握手，无需兜底。")
            return@Runnable
        }

        // 如果还没连接成功，切换到 autoConnect 模式
        if (connectionState < ConnectionState.CONNECTED) {
            logger.w("半程检测：扫描未果，强制切入 autoConnect=true 后台等待模式！")
            ScanManager.stopScan(scanCallback)
            connectGatt(bluetoothAdapter.getRemoteDevice(config.macAddress), true)
        }
    }

    /**
     * 释放所有资源
     *
     * 调用 disconnect() 断开连接并清理资源。
     */
    fun release() {
        disconnect()
    }

    /**
     * 清理 GATT 连接资源
     *
     * @param gatt 要清理的 GATT 连接实例
     */
    private fun cleanupGatt(gatt: BluetoothGatt?) {
        try {
            // 关闭 GATT 连接
            gatt?.close()
        } catch (e: Exception) {
            // 静默处理异常
        }
        // 如果清理的是当前的 bluetoothGatt，清空引用
        if (this.bluetoothGatt === gatt) this.bluetoothGatt = null
    }

    /**
     * 启动连接超时任务
     *
     * 如果在超时时间内没有成功连接，会自动断开并通知 DISCONNECTED 状态。
     */
    private fun startTimeout() {
        // 先取消之前的超时任务
        cancelTimeout()
        // 创建新的超时任务
        connectionTimeoutRunnable = Runnable {
            // 只有在 CONNECTING 状态时才处理超时
            if (connectionState == ConnectionState.CONNECTING) {
                // 清理 GATT 资源
                cleanupGatt(bluetoothGatt)
                // 更新状态为 DISCONNECTED，原因是 TIMEOUT
                updateState(ConnectionState.DISCONNECTED, DisconnectReason.TIMEOUT, null)
            }
        }
        // 延迟执行超时任务
        workerHandler.postDelayed(connectionTimeoutRunnable!!, connectTimeOut())
    }

    /**
     * 取消连接超时任务
     */
    private fun cancelTimeout() {
        connectionTimeoutRunnable?.let { workerHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
    }

    /**
     * 强制将状态设置为 READY
     *
     * 用于某些特殊场景，比如不需要 MTU 协商时直接推进到 READY 状态。
     */
    fun forceStateReady() {
        updateState(ConnectionState.READY, null, bluetoothGatt?.device)
    }

    /**
     * 请求连接优先级
     *
     * @param priority 连接优先级，默认高优先级
     */
    fun requestConnectionPriority(priority: ConnectionPriority = ConnectionPriority.HIGH) {
        mainHandler.post {
            bluetoothGatt?.requestConnectionPriority(priority.value)
        }
    }

    /**
     * 更新连接状态
     *
     * @param state 新的连接状态
     * @param reason 断开原因（仅在断开时有效）
     * @param device 蓝牙设备（可为 null）
     */
    private fun updateState(
        state: ConnectionState,
        reason: DisconnectReason?,
        device: BluetoothDevice?
    ) {
        // 如果状态没有变化，不处理
        if (connectionState == state) return
        // 更新状态
        connectionState = state
        // 通知监听器状态变化
        listener.onConnectionStateChange(state, reason, device)
    }

    /**
     * 初始化块 - 注册蓝牙状态监听
     *
     * 当系统蓝牙关闭时，自动断开所有连接。
     */
    init {
        BluetoothStateMonitor.instance.register({ enable ->
            if (!enable) {
                // 蓝牙关闭，断开连接
                disconnect()
            }
        })
    }
}
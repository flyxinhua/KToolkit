package com.sanvar.ble


import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.sanvar.ble.internal.BleConnector
import com.sanvar.ble.internal.BleGuardian
import com.sanvar.ble.internal.BleTask
import com.sanvar.ble.internal.BleTaskQueue
import com.sanvar.ble.internal.InnerGattCallback
import com.sanvar.ble.sanner.BleUUID
import com.sanvar.ble.utils.BleHelper
import com.sanvar.ble.utils.BleLogger
import com.sanvar.ble.utils.HighSpeedReceiver
import java.util.UUID


/**
 * 蓝牙设备管理类 (核心调度中心 Hub)
 *
 * 每个实例独立管理一个蓝牙设备的连接、服务发现和数据操作。
 * 采用组合模式，内部包含任务队列引擎、连接器和守护组件。
 */
class BleDevice internal constructor(context: Context, val config: BleConfig) : InnerGattCallback {
    val macAddress: String = config.macAddress.uppercase()
    private var _callback: BleCallback? = null
    private var isReleased = false
    private val logger = BleLogger.withTag("BleDevice_${config.macAddress}")

    // 缓存的特征值列表
    private var writeUUIDs: MutableList<GattCharacteristic> = mutableListOf()
    private var readUUIDs: MutableList<GattCharacteristic> = mutableListOf()
    private var notifyUUIDs: MutableList<GattCharacteristic> = mutableListOf()

    // 工作线程
    private val workThread = HandlerThread("BLEThread-${config.macAddress}").apply { start() }
    private val workHandler = Handler(workThread.looper)

    // ==================== 组合子模块 ====================

    // 任务队列引擎：负责处理业务调用、分包、排队执行
    private val taskQueue = BleTaskQueue(context, config, workHandler)

    // 纯粹的底层连接器：只负责建立连接和抛出原始 GATT 回调
    private val connector = BleConnector(context, config, workHandler, this)

    // 守护连接
    private var guardian: BleGuardian? = if (config.enableGuard) {
        BleGuardian(config) { connector.connect() }.apply { startGuard() }
    } else null

    // ==================== 回调管理 ====================

    /**
     * 注册蓝牙回调监听器
     *
     * @param callback 回调接口，用于接收连接状态、数据通知等事件
     * @return BleDevice 实例，支持链式调用
     */
    fun registerCallback(callback: BleCallback): BleDevice {
        _callback = callback
        highSpeedReceiver = HighSpeedReceiver(callback)
        return this
    }

    private var baseInfo = BleBaseInfo()


    private val updateRunner = Runnable {
        // 还有callback
        _callback?.onReportBaseInfo(baseInfo)
    }

    private fun updateBaseInfo(info: BleBaseInfo) {
        workHandler.removeCallbacks(updateRunner)
        baseInfo = info
        workHandler.postDelayed(updateRunner, 500)
    }


    @OptIn(ExperimentalStdlibApi::class)
    private fun handleCharacteristicRead(uuid: UUID, data: ByteArray) {
        val feature = BleUUID.identify(uuid)

        when (feature) {
            is BleUUID.BleFeature.Battery -> {
                runCatching {
                    val newValue = baseInfo.copy(battery = data[0].toInt())
                    updateBaseInfo(newValue)
                    logger.i("battery: ${data[0]}")
                }
            }

            is BleUUID.BleFeature.FirmwareVersion -> {
                runCatching {
                    val newValue = baseInfo.copy(firmVersion = String(data))
                    updateBaseInfo(newValue)
                    logger.i("firmwareVersion:${newValue.firmVersion}")
                }
            }

            is BleUUID.BleFeature.SoftwareVersion -> {
                runCatching {
                    val newValue = baseInfo.copy(softVersion = String(data))
                    updateBaseInfo(newValue)
                    logger.i("softVersion:${newValue.softVersion}")
                }
            }

            is BleUUID.BleFeature.HardwareVersion -> {
                runCatching {
                    val newValue = baseInfo.copy(hardVersion = String(data))
                    updateBaseInfo(newValue)
                    logger.i("hardwareVersion:${newValue.hardVersion}")
                }
            }

            is BleUUID.BleFeature.SystemId -> {
                runCatching {
                    val hexString = data.toHexString(HexFormat.UpperCase)
                    val newValue = baseInfo.copy(systemId = hexString)
                    updateBaseInfo(newValue)
                    logger.i("systemId:${newValue.systemId}")
                }
            }

            is BleUUID.BleFeature.ModelNumber -> {
                runCatching {
                    val newValue = baseInfo.copy(modelString = String(data))
                    updateBaseInfo(newValue)
                    logger.i("model:${newValue.modelString}")
                }
            }

            is BleUUID.BleFeature.SerialNumber -> {
                val newValue = baseInfo.copy(serialNumber = String(data))
                updateBaseInfo(newValue)
                logger.i("serialNumber:${newValue.serialNumber}")
            }

            is BleUUID.BleFeature.ManufacturerName -> {
                runCatching {
                    val newValue = baseInfo.copy(manufacturerName = String(data))
                    updateBaseInfo(newValue)
                    logger.i("manufacturer:${newValue.manufacturerName}")
                }
            }

            else -> {}
        }
    }


    /**
     * 读取设备基本信息
     *
     * 批量读取设备的固件版本、软件版本、硬件版本、序列号、电池电量、型号、系统ID和制造商信息。
     * 读取完成后通过 [BleCallback.onReportBaseInfo] 回调通知。
     */
    fun readBaseInfo() {
        logger.i("readBaseInfo")
        read(BleUUID.FIRM_VERSION)
        read(BleUUID.SOFT_VERSION)
        read(BleUUID.HARD_VERSION)
        read(BleUUID.SERIAL)
        read(BleUUID.BATTERY)
        read(BleUUID.MODEL)
        read(BleUUID.SYSID)
        read(BleUUID.MANU)
    }

    // ==================== 底层 GATT 回调处理 (实现 InnerGattCallback) ====================

    override fun onConnectionStateChange(
        state: ConnectionState,
        reason: DisconnectReason?,
        device: BluetoothDevice?
    ) {
        logger.w("onConnectionStateChange: $state ")
        // 1. 处理业务逻辑
        if (state == ConnectionState.READY) {
            guardian?.onConnected()
            // 自动开启通知 (利用队列顺序执行，非常安全)
            if (config.enableNotify) {
                notifyUUIDs.forEach { char ->
                    if (char.canIndicate) {
                        enableIndication(char.uuid)
                    } else if (char.canNotify) {
                        enableNotification(char.uuid)
                    }
                }
                // 读取基本信息.
                readBaseInfo()
            }
        } else if (state == ConnectionState.DISCONNECTED) {
            taskQueue.setGatt(null) // 清理队列中未完成的任务
            guardian?.onDisconnected(reason ?: DisconnectReason.ERROR, -1, false)
        }

        // 2. 通知外层
        _callback?.onConnectionStateChanged(state, device)
        if (state == ConnectionState.DISCONNECTED && reason != null) {
            _callback?.onDisconnected(reason, -1, false)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            writeUUIDs.clear()
            readUUIDs.clear()
            notifyUUIDs.clear()

            // 1. 解析特征值
            val characteristicList = arrayListOf<GattCharacteristic>()
            gatt.services.forEach { service ->
                val list = service.characteristics.map { char ->
                    GattCharacteristic(
                        serviceUUID = service.uuid, uuid = char.uuid, properties = char.properties,
                        canRead = (char.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                        canWrite = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0,
                        canWriteNoResponse = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
                        canNotify = (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
                        canIndicate = (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ).also {
                        if (it.canNotify || it.canIndicate) notifyUUIDs.add(it)
                        if (it.canWrite || it.canWriteNoResponse) writeUUIDs.add(it)
                        if (it.canRead) readUUIDs.add(it)
                    }
                }
                characteristicList.addAll(list)
            }
            _callback?.onServicesDiscovered(characteristicList)

            // 2. 赋予队列执行权
            taskQueue.setGatt(gatt)


            // 3. 处理自动 MTU 申请
            if (config.autoNegotiateMtu) {
                taskQueue.enqueueRequestMtu(config.preferredMtu)
            } else {
                // 如果不需要申请 MTU，则直接推进到 READY 状态
                connector.forceStateReady()
            }
        } else {
            _callback?.onError(
                BleError(
                    BleError.ERROR_SERVICE_NOT_FOUND,
                    "Service discovery failed: $status"
                )
            )
        }
    }

    override fun onMtuChanged(mtu: Int, status: Int) {
        val success = status == BluetoothGatt.GATT_SUCCESS
        if (success) {
            _callback?.onMtuChanged(mtu)

            // MTU 握手成功后，将状态推进到 READY
            if (connector.connectionState == ConnectionState.DISCOVERING) {
                connector.forceStateReady()
            }
        }
        // 【解锁】通知任务队列 // 更新队列的 MTU (分包算法需要)
        taskQueue.onMTUChange(success, mtu)
    }

    override fun onCharacteristicRead(
        char: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        printData("read", char.uuid, value)
        val success = status == BluetoothGatt.GATT_SUCCESS
        // 【解锁】通知任务队列
        taskQueue.readCompleted(char.uuid, success)
        if (success) {
            handleCharacteristicRead(char.uuid, value)
            _callback?.onReadData(char.service.uuid, char.uuid, value)
        }

    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun printData(
        from: String,
        uuid: UUID,
        data: ByteArray?
    ) {
        if (config.enableConnectorLog) {
            val shortUuid = BleHelper.uuidMap.getOrPut(uuid) {
                uuid.toString().substring(4, 8)
            }
            if (data != null) {
                val len = data.size
                // 核心改造：使用 Kotlin 现代语法，极其清晰，且没有性能陷阱
                logger.d("$from $shortUuid, l:$len")
            } else {
                logger.d("$from $shortUuid")
            }
        }
    }

    override fun onCharacteristicWrite(char: BluetoothGattCharacteristic, status: Int) {
        val success = status == BluetoothGatt.GATT_SUCCESS
        printData("write finish", char.uuid, char.value)
        // 【解锁】通知任务队列，推进分包状态
        taskQueue.writeCompleted(char.uuid, success)
    }


    private var highSpeedReceiver: HighSpeedReceiver? = null

    override fun onCharacteristicChanged(char: BluetoothGattCharacteristic, value: ByteArray) {
        printData("recv", char.uuid, value)
        // 这是设备主动推送的数据，无需解锁任务，直接回调上层
        // 放入队列
        highSpeedReceiver?.onReceivedData(char.service.uuid, char.uuid, value)
//        _callback?.onReceivedData(char.service.uuid, char.uuid, value)
    }

    override fun onDescriptorWrite(descriptor: BluetoothGattDescriptor, status: Int) {
        val success = status == BluetoothGatt.GATT_SUCCESS
        // 【解锁】通知任务队列
        taskQueue.notificationCompleted(descriptor.characteristic.uuid, success)
    }

    // ==================== 连接操作 ====================

    /**
     * 发起蓝牙连接
     *
     * @return BleDevice 实例，支持链式调用
     * @throws IllegalStateException 如果设备已被释放
     */
    fun connect(): BleDevice {
        checkNotReleased()
        connector.connect()
        return this
    }

    /**
     * 断开蓝牙连接
     *
     * @return BleDevice 实例，支持链式调用
     */
    fun disconnect(): BleDevice {
        connector.disconnect()
        return this
    }

    // ==================== 数据操作 (代理给 TaskQueue) ====================

    /**
     * 异步读取特征值
     *
     * @param uuid 特征值 UUID
     * @return BleDevice 实例，支持链式调用
     * @throws IllegalStateException 如果设备已被释放
     */
    fun read(uuid: UUID): BleDevice {
        checkNotReleased()
        readUUIDs.firstOrNull { it.uuid == uuid }?.let {
            taskQueue.enqueueRead(it.serviceUUID, it.uuid)
        } ?: logger.e("$macAddress not found read service uuid! char: $uuid")
        return this
    }

    /**
     * 异步写入特征值
     *
     * 内部自动处理分包逻辑，支持大数据传输。
     *
     * @param uuid 特征值 UUID
     * @param data 要写入的数据
     * @param withResponse 是否需要响应确认，null 表示使用特征值默认配置
     * @param priority 任务优先级，默认为 [BleTask.PRIORITY_NORMAL]
     * @return BleDevice 实例，支持链式调用
     * @throws IllegalStateException 如果设备已被释放
     */
    fun write(
        uuid: UUID,
        data: ByteArray,
        withResponse: Boolean? = null,
        priority: Int = BleTask.PRIORITY_NORMAL
    ): BleDevice {
        checkNotReleased()
        writeUUIDs.firstOrNull { it.uuid == uuid }?.let { char ->
            val response = withResponse ?: char.canWrite
            // 内部自动完成分包处理
            taskQueue.enqueueWrite(char.serviceUUID, char.uuid, data, response, priority)
        } ?: logger.e("$uuid not found write service uuid!")
        return this
    }

    /**
     * 启用或禁用特征值通知 (Notification)
     *
     * @param uuid 特征值 UUID
     * @param enable true 启用，false 禁用，默认为 true
     * @return BleDevice 实例，支持链式调用
     * @throws IllegalStateException 如果设备已被释放
     */
    fun enableNotification(uuid: UUID, enable: Boolean = true): BleDevice {
        checkNotReleased()
        notifyUUIDs.firstOrNull { it.uuid == uuid }?.let {
            taskQueue.enqueueEnableNotification(it.serviceUUID, uuid, enable, isIndication = false)
        }
        return this
    }

    /**
     * 启用或禁用特征值指示 (Indication)
     *
     * Indication 与 Notification 的区别在于 Indication 需要设备确认收到数据。
     *
     * @param uuid 特征值 UUID
     * @param enable true 启用，false 禁用，默认为 true
     * @return BleDevice 实例，支持链式调用
     * @throws IllegalStateException 如果设备已被释放
     */
    fun enableIndication(uuid: UUID, enable: Boolean = true): BleDevice {
        checkNotReleased()
        notifyUUIDs.firstOrNull { it.uuid == uuid }?.let {
            taskQueue.enqueueEnableNotification(it.serviceUUID, uuid, enable, isIndication = true)
        }
        return this
    }

    /**
     * 请求 MTU 协商
     *
     * @param mtu 请求的 MTU 值，范围 23-517
     * @return BleDevice 实例，支持链式调用
     * @throws IllegalStateException 如果设备已被释放
     */
    fun requestMtu(mtu: Int): BleDevice {
        checkNotReleased()
        taskQueue.enqueueRequestMtu(mtu)
        return this
    }

    /**
     * 请求连接优先级
     *
     * @param priority 连接优先级，默认高优先级
     */
    fun requestConnectionPriority(priority: ConnectionPriority = ConnectionPriority.HIGH): BleDevice {
        connector.requestConnectionPriority(priority)
        return this
    }

    // ==================== 状态查询 ====================

    /**
     * 检查设备是否已连接
     *
     * @return true 如果设备已连接并准备就绪，false 否则
     */
    fun isConnected(): Boolean = connector.connectionState == ConnectionState.READY

    /**
     * 获取当前连接状态
     *
     * @return 当前的连接状态 [ConnectionState]
     */
    fun getConnectionState(): ConnectionState = connector.connectionState

    // ==================== 守护控制与生命周期 ====================

    /**
     * 设置连接守护功能的启用状态
     *
     * 连接守护会在连接断开时自动尝试重连，并在应用回到前台时恢复连接。
     *
     * @param enabled true 启用守护，false 禁用守护
     * @return BleDevice 实例，支持链式调用
     */
    fun setGuardEnabled(enabled: Boolean): BleDevice {
        if (enabled) {
            if (guardian == null) {
                guardian = BleGuardian(config) { connector.connect() }
            }
            guardian?.startGuard()
        } else {
            guardian?.stopGuard()
        }
        return this
    }

    internal fun onAppForegroundChanged(isForeground: Boolean) {
        if (isReleased) return
        if (isForeground) {
            guardian?.resume(connector.connectionState == ConnectionState.READY)
            if (!isConnected() && config.enableGuard) connect()
        } else {
            guardian?.pause()
        }
    }

    internal fun onBluetoothStateChanged(enabled: Boolean) {
        if (isReleased) return
        _callback?.onBluetoothStateChanged(enabled)
        if (enabled && config.enableGuard && !isConnected()) {
            connect()
        }
    }

    internal fun release() {
        if (isReleased) return
        runCatching {
            isReleased = true
            guardian?.stopGuard()
            connector.release()
            taskQueue.clear()
            workThread.quitSafely()
            logger.d("BleDevice released: $macAddress")
        }
    }

    private fun checkNotReleased() {
        check(!isReleased) { "BleDevice has been released" }
    }
}
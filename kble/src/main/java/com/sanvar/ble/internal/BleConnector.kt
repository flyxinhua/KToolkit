package com.sanvar.ble.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sanvar.ble.BleConfig
import com.sanvar.ble.BleError
import com.sanvar.ble.ConnectionState
import com.sanvar.ble.DisconnectReason
import com.sanvar.ble.GattCharacteristic
import com.sanvar.ble.utils.BleLogger
import java.util.UUID
import kotlin.math.min

/**
 * BLE连接器 - 内部类
 *
 * 职责：纯粹的连接执行，不含任何重连逻辑
 */
@SuppressLint("MissingPermission")
internal class BleConnector(
    private val context: Context,
    private val config: BleConfig,
    private val listener: Listener
) {

    private val TAG = "BleConnector"

    /**
     * 连接器回调 - 单一监听者
     */
    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState, device: BluetoothDevice?)
        fun onDisconnected(reason: DisconnectReason, gattStatus: Int, isConnectPhase: Boolean)
        fun onServicesDiscovered(services: List<GattCharacteristic>)
        fun onMtuChanged(mtu: Int)
        fun onNotificationReceived(uuid: UUID, data: ByteArray)
        fun onReadComplete(uuid: UUID, data: ByteArray?, success: Boolean)

        fun onWriteComplete(uuid: UUID, success: Boolean)
        fun onNotificationEnabled(uuid: UUID, enabled: Boolean, success: Boolean)

        fun onError(error: BleError)
    }

    companion object {
        // 标准的服务
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val MTU_HEADER_SIZE = 3
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private var currentMtu = DEFAULT_MTU
    private var safePayload = 20

    private var scanAssistant: ScanAssistant? = null
    private val taskQueue = BleTaskQueue(config.operationTimeout)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectionTimeoutRunnable: Runnable? = null
    private var isUserDisconnect = false

    // ==================== GATT 回调 ====================

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> handleConnected(gatt)
                    BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected(gatt, status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handleServicesDiscovered(gatt)
                } else {
                    BleLogger.e("$TAG Service discovery failed: $status")
                    listener.onError(
                        BleError(
                            BleError.ERROR_SERVICE_NOT_FOUND,
                            "Service discovery failed: $status"
                        )
                    )
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    currentMtu = mtu
                    safePayload = mtu - MTU_HEADER_SIZE
                    BleLogger.d("$TAG MTU: $mtu , payload: $safePayload ")
                    listener.onMtuChanged(mtu)
                }
                taskQueue.taskCompleted(status == BluetoothGatt.GATT_SUCCESS)

                if (connectionState == ConnectionState.DISCOVERING) {
                    updateState(ConnectionState.READY, gatt.device)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            mainHandler.post {
                listener.onReadComplete(
                    characteristic.uuid,
                    if (status == BluetoothGatt.GATT_SUCCESS) value else null,
                    status == BluetoothGatt.GATT_SUCCESS
                )
                taskQueue.taskCompleted(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onCharacteristicRead(
                    gatt,
                    characteristic,
                    characteristic.value ?: ByteArray(0),
                    status
                )
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            mainHandler.post {
                listener.onWriteComplete(characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS)
                taskQueue.taskCompleted(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            mainHandler.post {
                listener.onNotificationReceived(characteristic.uuid, value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            mainHandler.post {
                val task = taskQueue.getCurrentTask()
                if (task is BleTask.EnableNotification) {
                    listener.onNotificationEnabled(
                        descriptor.characteristic.uuid,
                        task.enable,
                        status == BluetoothGatt.GATT_SUCCESS
                    )
                }
                taskQueue.taskCompleted(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
    }

    // ==================== 连接状态处理 ====================

    private fun handleConnected(gatt: BluetoothGatt) {
        cancelTimeout()
        stopAssistScan()

        BleLogger.d("$TAG Connected: ${gatt.device.address}")
        updateState(ConnectionState.CONNECTED, gatt.device)

        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

        updateState(ConnectionState.DISCOVERING, gatt.device)
        mainHandler.postDelayed({
            if (connectionState == ConnectionState.DISCOVERING) {
                gatt.discoverServices()
            }
        }, 50)
    }

    private fun handleDisconnected(gatt: BluetoothGatt, status: Int) {
        BleLogger.d("$TAG Disconnected, status: $status, state: $connectionState")

        val isConnectPhase = connectionState == ConnectionState.CONNECTING

        cancelTimeout()
        stopAssistScan()
        cleanupGatt(gatt)

        val reason = when {
            isUserDisconnect -> DisconnectReason.USER_REQUEST
            isConnectPhase -> DisconnectReason.CONNECT_FAILED
            status != BluetoothGatt.GATT_SUCCESS -> DisconnectReason.ERROR
            else -> DisconnectReason.DEVICE_DISCONNECT
        }

        updateState(ConnectionState.DISCONNECTED, null)
        listener.onDisconnected(reason, status, isConnectPhase)
    }

    private fun handleServicesDiscovered(gatt: BluetoothGatt) {
        BleLogger.d("$TAG Services discovered: ${gatt.services.size}")
        bluetoothGatt = gatt
        taskQueue.setGatt(gatt)

        val characteristicList = arrayListOf<GattCharacteristic>()

        gatt.services.forEach { service ->
            val list = service.characteristics.map { char ->
                GattCharacteristic(
                    serverUUID = service.uuid,
                    uuid = char.uuid,
                    properties = char.properties,
                    canRead = (char.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                    canWrite = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0,
                    canWriteNoResponse = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
                    canNotify = (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
                    canIndicate = (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                )
            }
            characteristicList.addAll(list)
        }

        if (config.autoNegotiateMtu) {
            requestMtu(config.preferredMtu)
        } else {
            updateState(ConnectionState.READY, gatt.device)
        }
        listener.onServicesDiscovered(characteristicList)
    }

    // ==================== 连接操作 ====================

    fun connect() {
        if (connectionState != ConnectionState.DISCONNECTED) {
            BleLogger.w("$TAG Cannot connect, state: $connectionState")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError(
                BleError(
                    BleError.ERROR_BLUETOOTH_NOT_ENABLED,
                    "Bluetooth not enabled"
                )
            )
            return
        }

        isUserDisconnect = false
        updateState(ConnectionState.CONNECTING, null)

        try {
            val device = adapter.getRemoteDevice(config.macAddress)
            BleLogger.d("$TAG Connecting: ${config.macAddress}")

            startTimeout()
            connectGatt(device)

            if (config.enableAssistScan) {
                startAssistScan()
            }
        } catch (e: IllegalArgumentException) {
            BleLogger.e("$TAG Invalid MAC: ${config.macAddress}")
            updateState(ConnectionState.DISCONNECTED, null)
            listener.onError(BleError(BleError.ERROR_DEVICE_NOT_FOUND, "Invalid MAC address"))
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        bluetoothGatt?.close()
        bluetoothGatt = null

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        BleLogger.d("$TAG Disconnect")
        isUserDisconnect = true
        cancelTimeout()
        stopAssistScan()

        bluetoothGatt?.let { gatt ->
            updateState(ConnectionState.DISCONNECTING, gatt.device)
            gatt.disconnect()
        } ?: run {
            updateState(ConnectionState.DISCONNECTED, null)
        }
    }

    fun close() {
        disconnect()
        mainHandler.postDelayed({
            taskQueue.clear()
            taskQueue.setGatt(null)
            bluetoothGatt?.close()
            bluetoothGatt = null
        }, 100)
    }

    private fun cleanupGatt(gatt: BluetoothGatt) {
        taskQueue.clear()
        taskQueue.setGatt(null)
        gatt.close()
        bluetoothGatt = null
        currentMtu = DEFAULT_MTU
    }

    // ==================== 超时 & 辅助扫描 ====================

    private fun startTimeout() {
        cancelTimeout()
        connectionTimeoutRunnable = Runnable {
            if (connectionState == ConnectionState.CONNECTING) {
                BleLogger.e("$TAG Connection timeout")
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                stopAssistScan()
                updateState(ConnectionState.DISCONNECTED, null)
                listener.onError(BleError(BleError.ERROR_TIMEOUT, "$TAG Connection timeout"))
                listener.onDisconnected(DisconnectReason.TIMEOUT, -1, true)
            }
        }
        mainHandler.postDelayed(connectionTimeoutRunnable!!, config.connectionTimeout)
    }

    private fun cancelTimeout() {
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
    }

    private fun startAssistScan() {
        stopAssistScan()
        scanAssistant = ScanAssistant(config.macAddress) { _, rssi ->
            BleLogger.d("$TAG Assist scan found, rssi: $rssi")
        }.apply { start(config.connectionTimeout) }
    }

    private fun stopAssistScan() {
        scanAssistant?.stop()
        scanAssistant = null
    }

    // ==================== 状态管理 ====================

    private fun updateState(state: ConnectionState, device: BluetoothDevice?) {
        if (connectionState == state) return
        BleLogger.d("$TAG State: $connectionState -> $state")
        connectionState = state
        listener.onConnectionStateChanged(state, device)
    }

    // ==================== 数据操作 ====================

    fun read(serviceUuid: UUID, characteristicUuid: UUID) {
        checkReady()
        taskQueue.enqueue(BleTask.Read(serviceUuid, characteristicUuid))
    }


    /**
     * 分包工具方法
     */
    private fun splitIntoChunks(data: ByteArray, chunkSize: Int): List<ByteArray> {
        if (chunkSize <= 0) return listOf(data)
        return data.toList().chunked(chunkSize).map { it.toByteArray() }
    }

    /**
     * 写入数据 - 自动分包
     */
    fun write(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray,
        withResponse: Boolean,
        callback: ((Boolean) -> Unit)? = null,
        progressCallback: ((sentBytes: Int, totalBytes: Int) -> Unit)? = null
    ) {
        checkReady()

        val payloadSize = getPayloadSize()

        if (data.size <= payloadSize) {
            // 单包写入
            val writeType = if (withResponse) {
                BleTask.Write.WriteType.WITH_RESPONSE
            } else {
                BleTask.Write.WriteType.WITHOUT_RESPONSE
            }
            val task = BleTask.Write(serviceUuid, characteristicUuid, data, writeType).apply {
                completionCallback = callback
            }
            taskQueue.enqueue(task)
        } else {
            // 分包写入
            val chunks = splitIntoChunks(data, payloadSize)
            BleLogger.d("$TAG Auto chunked: ${data.size} bytes -> ${chunks.size} chunks")

            val writeType = if (withResponse) {
                BleTask.ChunkedWrite.WriteType.WITH_RESPONSE
            } else {
                BleTask.ChunkedWrite.WriteType.WITHOUT_RESPONSE
            }
            val task = BleTask.ChunkedWrite(
                serviceUuid,
                characteristicUuid,
                chunks,
                writeType,
                data.size
            ).apply {
                completionCallback = callback
                this.progressCallback = progressCallback
            }
            taskQueue.enqueue(task)
        }
    }


    fun write(serviceUuid: UUID, characteristicUuid: UUID, data: ByteArray, withResponse: Boolean) {
        checkReady()
        val writeType = if (withResponse) {
            BleTask.Write.WriteType.WITH_RESPONSE
        } else {
            BleTask.Write.WriteType.WITHOUT_RESPONSE
        }
        // 执行分包逻辑。

        taskQueue.enqueue(BleTask.Write(serviceUuid, characteristicUuid, data, writeType))
    }

    fun enableNotification(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enable: Boolean,
        isIndication: Boolean
    ) {
        checkReady()
        taskQueue.enqueue(
            BleTask.EnableNotification(
                serviceUuid,
                characteristicUuid,
                enable,
                isIndication
            )
        )
    }

    fun requestMtu(mtu: Int) {
        taskQueue.enqueue(BleTask.RequestMtu(mtu))
    }

    private fun checkReady() {
        check(connectionState == ConnectionState.READY) { "Not ready: $connectionState" }
    }

    // ==================== 状态查询 ====================

    fun getConnectionState(): ConnectionState = connectionState
    fun getCurrentMtu(): Int = currentMtu
    fun getPayloadSize(): Int = min(safePayload, currentMtu - MTU_HEADER_SIZE)

    /**
     *  可以自由调整相对小的载荷
     *
     * @param size
     * @return
     */
    fun setPayloadSize(size: Int): Int {
        safePayload = if (size > currentMtu - MTU_HEADER_SIZE || size < 20) {
            currentMtu - MTU_HEADER_SIZE
        } else {
            size
        }
        return safePayload
    }

    fun isConnected(): Boolean = connectionState == ConnectionState.READY
}
package com.sanvar.ble

import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * GATT特征包装
 */
data class GattCharacteristic(
    val serverUUID: UUID,
    val uuid: UUID,
    val properties: Int,
    val canRead: Boolean,
    val canWrite: Boolean,  // 有回复的写 (WRITE)
    val canWriteNoResponse: Boolean, // 无回复的写 (WRITE_NO_RESPONSE)
    val canNotify: Boolean,
    val canIndicate: Boolean
)

/**
 * 蓝牙错误
 */
data class BleError(
    val code: Int,
    val message: String,
    val exception: Throwable? = null
) {
    companion object {
        const val ERROR_BLUETOOTH_NOT_SUPPORTED = 1
        const val ERROR_BLUETOOTH_NOT_ENABLED = 2
        const val ERROR_PERMISSION_DENIED = 3
        const val ERROR_DEVICE_NOT_FOUND = 4
        const val ERROR_CONNECTION_FAILED = 5
        const val ERROR_SERVICE_NOT_FOUND = 6
        const val ERROR_CHARACTERISTIC_NOT_FOUND = 7
        const val ERROR_OPERATION_FAILED = 8
        const val ERROR_TIMEOUT = 9
    }
}

/**
 * 连接状态
 */
enum class ConnectionState {
    DISCONNECTED,      // 已断开
    CONNECTING,        // 连接中
    CONNECTED,         // 已连接
    DISCOVERING,       // 发现服务中
    READY,             // 就绪（已发现服务）
    DISCONNECTING      // 断开中
}

/**
 * 断开原因
 */
enum class DisconnectReason {
    USER_REQUEST,       // 用户主动断开
    DEVICE_DISCONNECT,  // 设备端断开
    CONNECT_FAILED,     // 连接失败（从未成功连接过）
    TIMEOUT,            // 连接超时
    ERROR               // 其他错误
}


/**
 * 蓝牙回调接口
 *  这些接口都有默认实现，可以根据需要实现某一个或者多个方法。
 */
interface BleCallback {

    /** 连接状态变化 */
    fun onConnectionStateChanged(state: ConnectionState, device: BluetoothDevice?) {}

    /** 断开连接 */
    fun onDisconnected(reason: DisconnectReason) {}

    /** MTU协商完成 */
    fun onMtuChanged(mtu: Int) {}

    /** 收到通知数据 */
    fun onNotificationReceived(uuid: UUID, data: ByteArray) {}

    /** 读取完成 */
    fun onReadComplete(uuid: UUID, data: ByteArray?, success: Boolean) {}

    /** 写入完成 */
    fun onWriteComplete(uuid: UUID, success: Boolean) {}

    /** 通知开关状态变化 */
    fun onNotificationEnabled(uuid: UUID, enabled: Boolean, success: Boolean) {}

    /** 错误回调 */
    fun onError(error: BleError) {}

    /** 发现服务完成 */
    fun onServicesDiscovered(characteristic: List<GattCharacteristic>) {}

    /** 蓝牙开关状态变化 */
    fun onBluetoothStateChanged(enabled: Boolean) {}
}
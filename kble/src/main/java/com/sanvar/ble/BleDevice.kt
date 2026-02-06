package com.sanvar.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.sanvar.ble.internal.BleConnector
import com.sanvar.ble.internal.BleGuardian
import com.sanvar.ble.utils.BleLogger
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 蓝牙设备管理类
 */
class BleDevice internal constructor(
    context: Context,
    val config: BleConfig
) {
    val macAddress: String = config.macAddress.uppercase()

    private val callbacks = CopyOnWriteArraySet<BleCallback>()
    private var isReleased = false


    private var writeUUIDs: MutableList<GattCharacteristic> = mutableListOf()
    private var readUUIDs: MutableList<GattCharacteristic> = mutableListOf()
    private var notifyUUIDs: MutableList<GattCharacteristic> = mutableListOf()


    // 连接器监听
    private val connectorListener = object : BleConnector.Listener {
        override fun onConnectionStateChanged(state: ConnectionState, device: BluetoothDevice?) {
            if (state == ConnectionState.READY) {
                guardian?.onConnected()
                if (config.enableNotify) {
                    notifyUUIDs.forEach { char ->
                        if (char.canIndicate) {
                            enableIndication(char.uuid)
                        } else if (char.canNotify) {
                            enableNotification(char.uuid)
                        }
                    }
                }
            }
            dispatchCallback { it.onConnectionStateChanged(state, device) }
        }

        override fun onDisconnected(
            reason: DisconnectReason, gattStatus: Int, isConnectPhase: Boolean
        ) {
            guardian?.onDisconnected(reason, gattStatus, isConnectPhase)
            dispatchCallback { it.onDisconnected(reason) }
        }

        override fun onServicesDiscovered(services: List<GattCharacteristic>) {
            writeUUIDs.clear()
            readUUIDs.clear()
            notifyUUIDs.clear()
            services.forEach { char ->
                if (char.canNotify || char.canIndicate) {
                    notifyUUIDs.add(char)
                }

                if (char.canWrite || char.canWriteNoResponse) {
                    writeUUIDs.add(char)
                }
                if (char.canRead) {
                    readUUIDs.add(char)
                }
            }
            dispatchCallback { it.onServicesDiscovered(services) }

        }

        override fun onMtuChanged(mtu: Int) {
            dispatchCallback { it.onMtuChanged(mtu) }
        }

        override fun onNotificationReceived(uuid: UUID, data: ByteArray) {
            dispatchCallback { it.onNotificationReceived(uuid, data) }
        }

        override fun onReadComplete(uuid: UUID, data: ByteArray?, success: Boolean) {
            dispatchCallback { it.onReadComplete(uuid, data, success) }
        }

        override fun onWriteComplete(uuid: UUID, success: Boolean) {
            dispatchCallback { it.onWriteComplete(uuid, success) }
        }

        override fun onNotificationEnabled(uuid: UUID, enabled: Boolean, success: Boolean) {
            dispatchCallback {
                it.onNotificationEnabled(uuid, enabled, success)
            }
        }

        override fun onError(error: BleError) {
            dispatchCallback { it.onError(error) }
        }
    }

    private val connector = BleConnector(context, config, connectorListener)

    private var guardian: BleGuardian? = if (config.enableGuard) {
        BleGuardian(config) { connector.connect() }.apply { startGuard() }
    } else null

    // ==================== 回调管理 ====================

    fun registerCallback(callback: BleCallback): BleDevice {
        callbacks.add(callback)
        return this
    }

    fun unregisterCallback(callback: BleCallback): BleDevice {
        callbacks.remove(callback)
        return this
    }

    private fun dispatchCallback(action: (BleCallback) -> Unit) {
        callbacks.forEach {
            try {
                action(it)
            } catch (e: Exception) {
                BleLogger.e("Callback error", e)
            }
        }
    }

    // ==================== 连接操作 ====================

    fun connect(): BleDevice {
        checkNotReleased()
        connector.connect()
        return this
    }

    fun disconnect(): BleDevice {
        connector.disconnect()
        return this
    }

    // ==================== 数据操作 ====================

    fun read(uuid: UUID): BleDevice {
        checkNotReleased()
        readUUIDs.firstOrNull { it.uuid == uuid }?.let {
            connector.read(it.serverUUID, it.uuid)
        } ?: run {
            BleLogger.e("not found read server uuid! char: $uuid")
        }
        return this
    }


    fun write(uuid: UUID, data: ByteArray, withResponse: Boolean? = null): BleDevice {
        checkNotReleased()
        writeUUIDs.firstOrNull { it.uuid == uuid }?.let { char ->
            // 智能决策逻辑
            val useNoResponse = when {
                // 1. 如果业务层强行指定了要求 (且硬件支持)，听业务层的
                withResponse == true && char.canWrite -> false // 想要响应，且硬件支持 -> 用 With Response
                withResponse == false && char.canWriteNoResponse -> true // 想要无响应，且硬件支持 -> 用 No Response
                // 2. 如果业务层没指定，或者硬件不支持业务层的要求 -> 走默认自动策略
                // 默认策略：优先用 NoResponse (快)，没有再用 WithResponse
                char.canWriteNoResponse -> true
                char.canWrite -> false
                else -> false // 兜底
            }
            // 把决策结果传给底层
            connector.write(char.serverUUID, char.uuid, data, useNoResponse)
        } ?: run {
            BleLogger.e("not found write server uuid!")
        }
        return this
    }


    fun enableNotification(uuid: UUID, enable: Boolean = true): BleDevice {
        checkNotReleased()
        notifyUUIDs.firstOrNull { it.uuid == uuid }?.let {
            connector.enableNotification(it.serverUUID, uuid, enable, false)
        }
        return this
    }

    fun enableIndication(uuid: UUID, enable: Boolean = true): BleDevice {
        checkNotReleased()
        notifyUUIDs.firstOrNull { it.uuid == uuid }?.let {
            connector.enableNotification(it.serverUUID, uuid, enable, true)
        }
        return this
    }

    fun requestMtu(mtu: Int): BleDevice {
        checkNotReleased()
        connector.requestMtu(mtu)
        return this
    }

    // ==================== 状态查询 ====================

    fun isConnected(): Boolean = connector.isConnected()
    fun getConnectionState(): ConnectionState = connector.getConnectionState()
    fun getCurrentMtu(): Int = connector.getCurrentMtu()
    fun getPayloadSize(): Int = connector.getPayloadSize()

    // ==================== 守护控制 ====================

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

    // ==================== 生命周期（由 BleManager 调用） ====================

    internal fun onAppForegroundChanged(isForeground: Boolean) {
        if (isReleased) return
        if (isForeground) {
            guardian?.resume(connector.isConnected())
            if (!isConnected() && config.enableGuard) connect()
        } else {
            guardian?.pause()
        }
    }

    internal fun onBluetoothStateChanged(enabled: Boolean) {
        if (isReleased) return
        dispatchCallback { it.onBluetoothStateChanged(enabled) }
        if (enabled && config.enableGuard && !isConnected()) connect()
    }

    internal fun release() {
        if (isReleased) return
        isReleased = true
        guardian?.stopGuard()
        connector.close()
        callbacks.clear()
        BleLogger.d("BleDevice released: $macAddress")
    }

    private fun checkNotReleased() {
        check(!isReleased) { "BleDevice has been released" }
    }
}
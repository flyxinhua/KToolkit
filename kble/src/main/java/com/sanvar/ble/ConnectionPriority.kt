package com.sanvar.ble

import android.bluetooth.BluetoothGatt

/**
 * 蓝牙连接优先级枚举
 *
 * 用于控制蓝牙连接的优先级，影响数据传输速率和功耗。
 */
enum class ConnectionPriority(val value: Int) {
    /**
     * 高优先级，低延迟，高功耗
     *
     * 适合实时数据传输场景，如音频流、实时控制等。
     * 会增加电量消耗，但数据传输更及时。
     */
    HIGH(BluetoothGatt.CONNECTION_PRIORITY_HIGH),

    /**
     * 平衡优先级（默认）
     *
     * 兼顾性能和功耗，适合大多数普通数据交互场景。
     */
    BALANCED(BluetoothGatt.CONNECTION_PRIORITY_BALANCED),

    /**
     * 低功耗优先级，高延迟
     *
     * 适合周期性数据同步场景，如定期心跳、状态查询等。
     * 电量消耗较低，但数据传输可能有延迟。
     */
    LOW_POWER(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
}
package com.sanvar.ble.utils

import java.util.UUID

// 这是一个永远存活在池子里的对象，绝不被 GC 回收
class BlePacket {
    // 假设最大的 MTU 负载不会超过 512 字节
    val buffer = ByteArray(512)
    var length: Int = 0

    // UUID 也复用对象！千万别存 String，也别每次 new UUID
    // 大多数情况下，外设发通知的 UUID 是固定的（比如只通过一个通道发数据）
    var serviceUUID: UUID? = null
    var uuid: UUID? = null

    // 清理脏数据标记（不需要清空 buffer 的内容，只要重置 length 即可，极速！）
    fun reset() {
        length = 0
        serviceUUID = null
        uuid = null
    }
}
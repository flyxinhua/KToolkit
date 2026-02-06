package com.sanvar.ble.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 蓝牙任务
 */
internal sealed class BleTask(val priority: Int = PRIORITY_NORMAL) {

    val id: Long = idGenerator.incrementAndGet()
    val createTime: Long = System.currentTimeMillis()
    var retryCount: Int = 0
    open val maxRetry: Int = 3

    /** 任务完成回调 */
    var completionCallback: ((Boolean) -> Unit)? = null

    companion object {
        private val idGenerator = AtomicLong(0)
        const val PRIORITY_HIGH = 0
        const val PRIORITY_NORMAL = 1
        const val PRIORITY_LOW = 2
    }

    data class Read(
        val serviceUuid: UUID,
        val characteristicUuid: UUID
    ) : BleTask()

    /**
     * 单包写入
     */
    data class Write(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val data: ByteArray,
        val writeType: WriteType
    ) : BleTask() {
        enum class WriteType { WITH_RESPONSE, WITHOUT_RESPONSE }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Write) return false
            return serviceUuid == other.serviceUuid &&
                    characteristicUuid == other.characteristicUuid &&
                    data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = serviceUuid.hashCode()
            result = 31 * result + characteristicUuid.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * 分包写入 - 作为一个整体任务，内部顺序执行
     */
    class ChunkedWrite(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val chunks: List<ByteArray>,  // 已分好的包
        val writeType: WriteType,
        val totalBytes: Int
    ) : BleTask() {
        enum class WriteType { WITH_RESPONSE, WITHOUT_RESPONSE }

        // 分包写入不自动重试整体
        override val maxRetry: Int = 0

        // 进度回调
        var progressCallback: ((sentBytes: Int, totalBytes: Int) -> Unit)? = null
    }

    data class EnableNotification(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val enable: Boolean,
        val isIndication: Boolean
    ) : BleTask(PRIORITY_HIGH)

    data class RequestMtu(val mtu: Int) : BleTask(PRIORITY_HIGH)
}
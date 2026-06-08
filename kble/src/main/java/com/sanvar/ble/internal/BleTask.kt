package com.sanvar.ble.internal

import com.sanvar.ble.utils.BleHelper
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal sealed class BleTask(open val priority: Int = PRIORITY_NORMAL) : Comparable<BleTask> {

    val id: Long = idGenerator.incrementAndGet()
    var retryCount: Int = 0
    open val maxRetry: Int = 1

    override fun compareTo(other: BleTask): Int {
        val pDiff = this.priority.compareTo(other.priority)
        if (pDiff != 0) return pDiff
        return this.id.compareTo(other.id)
    }

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
    ) : BleTask() {
        override fun toString() =
            "Read id:$id, priority:$priority, uuid:${
                BleHelper.uuidMap.getOrPut(characteristicUuid) {
                    characteristicUuid.toString().substring(4, 8)
                }
            }"
    }

    /**
     * 统一的写入任务 (单包和分包合并)
     * 即使是单包，也被包装成 size = 1 的 chunks 列表
     */
    class Write(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val chunks: List<ByteArray>,
        val writeType: WriteType,
        val totalBytes: Int,
        override val priority: Int = PRIORITY_NORMAL
    ) : BleTask(priority) {
        enum class WriteType { WITH_RESPONSE, WITHOUT_RESPONSE }

        // 只有单包才允许重试整体，多包中途失败重试成本太高，默认不重试整体
        override val maxRetry: Int = if (chunks.size > 1) 0 else 1

        // 进度回调 (sentBytes, totalBytes)
        var progressCallback: ((Int, Int) -> Unit)? = null

        override fun toString(): String {

            return "Write id:$id, ${
                BleHelper.uuidMap.getOrPut(characteristicUuid) {
                    characteristicUuid.toString().substring(4, 8)
                }
            }, chunks:${chunks.size}, total:$totalBytes ,priority:$priority"
        }
    }

    data class EnableNotification(
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val enable: Boolean,
        val isIndication: Boolean
    ) : BleTask(PRIORITY_HIGH) {
        override fun toString() = "EnableNotify id:$id, uuid:${
            BleHelper.uuidMap.getOrPut(characteristicUuid) {
                characteristicUuid.toString().substring(4, 8)
            }
        }, enable:$enable"
    }

    data class RequestMtu(val mtu: Int) : BleTask(PRIORITY_HIGH)
}
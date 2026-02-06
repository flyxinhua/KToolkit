package com.sanvar.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sanvar.ble.utils.BleLogger
import java.util.UUID
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 蓝牙任务队列
 */
internal class BleTaskQueue(private val operationTimeout: Long) {

    companion object {
        private const val TAG = "BleTaskQueue"
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val lock = ReentrantLock()
    private val queue =
        PriorityBlockingQueue<BleTask>(16, compareBy({ it.priority }, { it.createTime }))

    private var currentTask: BleTask? = null
    private var isProcessing = false
    private var gatt: BluetoothGatt? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    // ==================== 分包写入状态 ====================
    private var chunkedState: ChunkedWriteState? = null

    private class ChunkedWriteState(val task: BleTask.ChunkedWrite) {
        var currentIndex: Int = 0
        var sentBytes: Int = 0

        fun hasNext(): Boolean = currentIndex < task.chunks.size
        fun currentChunk(): ByteArray = task.chunks[currentIndex]

        fun advance() {
            sentBytes += task.chunks[currentIndex].size
            currentIndex++
        }

        fun isComplete(): Boolean = currentIndex >= task.chunks.size
    }

    // ==================== 公共方法 ====================

    fun setGatt(gatt: BluetoothGatt?) {
        lock.withLock {
            this.gatt = gatt
            if (gatt == null) clear()
        }
    }

    fun enqueue(task: BleTask) {
        lock.withLock {
            queue.offer(task)
            BleLogger.d("$TAG Task enqueued: ${task.javaClass.simpleName}, id: ${task.id}")
        }
        processNext()
    }

    fun taskCompleted(success: Boolean) {
        // 如果是分包写入中
        chunkedState?.let { state ->
            handleChunkedResult(state, success)
            return
        }

        // 普通任务完成
        finishCurrentTask(success)
    }

    fun getCurrentTask(): BleTask? = lock.withLock { currentTask }

    fun clear() {
        val pendingTasks: List<BleTask>
        val chunked: ChunkedWriteState?

        lock.withLock {
            cancelTimeout()
            pendingTasks = queue.toList()
            chunked = chunkedState
            queue.clear()
            chunkedState = null
            currentTask = null
            isProcessing = false
        }

        // 通知失败
        mainHandler.post {
            chunked?.task?.completionCallback?.invoke(false)
            pendingTasks.forEach { it.completionCallback?.invoke(false) }
        }
    }

    // ==================== 任务处理 ====================

    private fun processNext() {
        lock.withLock {
            if (isProcessing || queue.isEmpty() || gatt == null) return
            currentTask = queue.poll()
            isProcessing = true
        }

        currentTask?.let { task ->
            startTimeout(task)
            executeTask(task)
        }
    }

    private fun finishCurrentTask(success: Boolean) {
        var task: BleTask?
        lock.withLock {
            cancelTimeout()
            task = currentTask
            currentTask = null
            isProcessing = false
        }

        task?.let {
            BleLogger.d("$TAG Task completed: ${it.javaClass.simpleName}, taskId:${it.id} , success: $success")
            mainHandler.post { it.completionCallback?.invoke(success) }
        }

        processNext()
    }

    @Suppress("MissingPermission")
    private fun executeTask(task: BleTask) {
        val gatt = this.gatt ?: run {
            taskFailed("GATT is null")
            return
        }

        val success = when (task) {
            is BleTask.Read -> executeRead(gatt, task)
            is BleTask.Write -> executeWrite(gatt, task)
            is BleTask.ChunkedWrite -> executeChunkedWrite(task)
            is BleTask.EnableNotification -> executeEnableNotification(gatt, task)
            is BleTask.RequestMtu -> gatt.requestMtu(task.mtu)
        }

        if (!success) {
            taskFailed("Operation start failed")
        }
    }

    // ==================== 分包写入处理 ====================

    private fun executeChunkedWrite(task: BleTask.ChunkedWrite): Boolean {
        if (task.chunks.isEmpty()) {
            mainHandler.post { task.completionCallback?.invoke(true) }
            lock.withLock {
                currentTask = null
                isProcessing = false
            }
            processNext()
            return true
        }

        BleLogger.d("$TAG ChunkedWrite start: ${task.totalBytes} bytes, ${task.chunks.size} chunks")

        chunkedState = ChunkedWriteState(task)
        return sendNextChunk()
    }

    private fun sendNextChunk(): Boolean {
        val state = chunkedState ?: return false
        val gatt = this.gatt ?: return false

        if (!state.hasNext()) {
            finishChunkedWrite(true)
            return true
        }

        val chunk = state.currentChunk()
        BleLogger.d("$TAG Sending chunk ${state.currentIndex + 1}/${state.task.chunks.size}, size: ${chunk.size}")

        startTimeout(state.task)

        return writeInternal(
            gatt,
            state.task.serviceUuid,
            state.task.characteristicUuid,
            chunk,
            state.task.writeType == BleTask.ChunkedWrite.WriteType.WITH_RESPONSE
        )
    }

    private fun handleChunkedResult(state: ChunkedWriteState, success: Boolean) {
        cancelTimeout()

        if (!success) {
            BleLogger.e("$TAG ChunkedWrite failed at chunk ${state.currentIndex + 1}")
            finishChunkedWrite(false)
            return
        }

        // 当前分包成功，推进状态
        state.advance()

        // 回调进度
        state.task.progressCallback?.let { callback ->
            mainHandler.post { callback(state.sentBytes, state.task.totalBytes) }
        }

        if (state.isComplete()) {
            BleLogger.d("$TAG ChunkedWrite completed: ${state.sentBytes} bytes")
            finishChunkedWrite(true)
        } else {
            // 发送下一个分包
            if (!sendNextChunk()) {
                finishChunkedWrite(false)
            }
        }
    }

    private fun finishChunkedWrite(success: Boolean) {
        var task: BleTask.ChunkedWrite?
        lock.withLock {
            cancelTimeout()
            task = chunkedState?.task
            chunkedState = null
            currentTask = null
            isProcessing = false
        }

        task?.let {
            mainHandler.post { it.completionCallback?.invoke(success) }
        }

        processNext()
    }

    // ==================== 具体任务执行 ====================

    @Suppress("MissingPermission")
    private fun executeRead(gatt: BluetoothGatt, task: BleTask.Read): Boolean {
        val char =
            findCharacteristic(gatt, task.serviceUuid, task.characteristicUuid) ?: return false
        return gatt.readCharacteristic(char)
    }


    @Suppress("MissingPermission", "DEPRECATION")
    private fun writeInternal(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray,
        withResponse: Boolean
    ): Boolean {
        val char = findCharacteristic(gatt, serviceUuid, characteristicUuid) ?: return false
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            gatt.writeCharacteristic(char, data, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            char.value = data
            char.writeType = writeType
            gatt.writeCharacteristic(char)
        }
    }


    @Suppress("MissingPermission", "DEPRECATION")
    private fun executeWrite(gatt: BluetoothGatt, task: BleTask.Write): Boolean {
        val char =
            findCharacteristic(gatt, task.serviceUuid, task.characteristicUuid) ?: return false

        // 虽然上层 Wrapper 做了筛选，但底层再防一道更稳健
        val properties = char.properties
        if (task.writeType == BleTask.Write.WriteType.WITHOUT_RESPONSE) {
            if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                // 硬件不支持无回复，强制降级为有回复？或者报错？
                // 这里建议：如果不支持 NoResponse，就降级尝试 Default，或者直接 return false
                BleLogger.w("Hardware not support Write_No_Response, logic error?")
                return false
            }
        }
        val writeType = when (task.writeType) {
            BleTask.Write.WriteType.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            BleTask.Write.WriteType.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, task.data, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            char.value = task.data
            char.writeType = writeType
            gatt.writeCharacteristic(char)
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun executeEnableNotification(
        gatt: BluetoothGatt,
        task: BleTask.EnableNotification
    ): Boolean {
        val char =
            findCharacteristic(gatt, task.serviceUuid, task.characteristicUuid) ?: return false
        // 先设置本地
        if (!gatt.setCharacteristicNotification(char, task.enable)) return false

        val properties = char.properties
        if (task.enable) {
            if (task.isIndication) {
                if ((properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
                    // 业务想开 Indication，但特征不支持，直接返回失败或降级为 Notify
                    return false
                }
            } else {
                if ((properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                    return false
                }
            }
        }

        val descriptor = char.getDescriptor(CCCD_UUID) ?: return false
        val value = when {
            !task.enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            task.isIndication -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        charUuid: UUID
    ): BluetoothGattCharacteristic? {
        return gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
    }

    // ==================== 失败与超时 ====================

    private fun taskFailed(reason: String) {
        // 分包写入中失败
        chunkedState?.let {
            BleLogger.e("$TAG ChunkedWrite failed: $reason")
            finishChunkedWrite(false)
            return
        }

        var task: BleTask?
        var shouldRetry = false

        lock.withLock {
            cancelTimeout()
            task = currentTask

            if (task != null && task.retryCount < task.maxRetry) {
                task.retryCount++
                queue.offer(task)
                shouldRetry = true
                BleLogger.w("$TAG Task retry: ${task.javaClass.simpleName}, attempt: ${task.retryCount}")
            }

            currentTask = null
            isProcessing = false
        }

        if (!shouldRetry) {
            BleLogger.e("$TAG Task failed: ${task?.javaClass?.simpleName}, reason: $reason")
            task?.let { t -> mainHandler.post { t.completionCallback?.invoke(false) } }
        }

        processNext()
    }

    private fun startTimeout(task: BleTask) {
        cancelTimeout()
        timeoutRunnable = Runnable {
            BleLogger.w("$TAG Task timeout: ${task.javaClass.simpleName}")
            taskFailed("Timeout")
        }
        mainHandler.postDelayed(timeoutRunnable!!, operationTimeout)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }
}
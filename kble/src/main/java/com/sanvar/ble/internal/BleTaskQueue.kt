package com.sanvar.ble.internal

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import com.sanvar.ble.BleConfig
import com.sanvar.ble.utils.BleHelper
import com.sanvar.ble.utils.BleLogger
import java.util.UUID
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 蓝牙任务队列管理器
 *
 * 核心职责：
 * 1. **优先级队列管理**：使用 PriorityBlockingQueue 管理 BLE 操作任务，支持优先级调度
 * 2. **分包传输**：自动将大数据分包发送，支持动态 MTU 适配
 * 3. **自动降级机制**：当大包发送失败时，自动降级为 20 字节小包重传
 * 4. **超时处理**：每个任务都有超时机制，超时自动触发失败处理
 * 5. **线程安全**：使用 ReentrantLock 保证多线程访问安全
 *
 * 任务类型：
 * - Read：读取特征值
 * - Write：写入特征值（支持分包）
 * - EnableNotification：启用/禁用通知
 * - RequestMtu：请求 MTU 协商
 *
 * @param context Android 上下文
 * @param config 蓝牙设备配置
 * @param workerHandler 工作线程 Handler，用于执行蓝牙操作
 */
internal class BleTaskQueue(
    private val context: Context,
    private val config: BleConfig,
    val workerHandler: Handler
) {

    /** 日志标签 */
    private val TAG = "BleTaskQueue"

    /** CCCD 描述符 UUID，用于启用通知/指示 */
    private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** MTU 头部大小（操作码 + 句柄），计算有效负载时需要减去 */
    private val MTU_HEADER_SIZE = 3

    /** 日志实例 */
    private val logger = BleLogger.withTag("${TAG}_${config.macAddress}")

    /** 重入锁，保证线程安全 */
    private val lock = ReentrantLock()

    /**
     * 优先级阻塞队列
     * - 优先级高的任务优先执行
     * - 相同优先级按任务 ID 顺序执行（FIFO）
     */
    private val queue = PriorityBlockingQueue<BleTask>(64, compareBy({ it.priority }, { it.id }))

    /** 当前正在执行的任务 */
    private var currentTask: BleTask? = null

    /** 是否正在处理任务 */
    private var isProcessing = false

    /** GATT 连接实例 */
    private var gatt: BluetoothGatt? = null

    /** 超时任务 */
    private var timeoutRunnable: Runnable? = null

    /** 当前写入状态（用于分包传输） */
    private var writeState: WriteState? = null

    /** 当前 MTU 值，初始为最小 MTU（23 字节） */
    private var currentMtu: Int = 23

    /**
     * 写入状态跟踪类
     *
     * 用于跟踪分包写入的进度，确保大数据正确分段发送。
     *
     * @param task 当前写入任务
     */
    private class WriteState(val task: BleTask.Write) {
        /** 当前正在发送的分块索引 */
        var currentIndex: Int = 0

        /** 已发送的总字节数 */
        var sentBytes: Int = 0

        /** 是否还有下一个分块 */
        fun hasNext(): Boolean = currentIndex < task.chunks.size

        /** 获取当前分块数据 */
        fun currentChunk(): ByteArray = task.chunks[currentIndex]

        /** 推进到下一个分块 */
        fun advance() {
            sentBytes += task.chunks[currentIndex].size
            currentIndex++
        }

        /** 是否已完成所有分块发送 */
        fun isComplete(): Boolean = currentIndex >= task.chunks.size
    }

    /**
     * 设置 GATT 连接实例
     *
     * @param gatt GATT 连接实例，null 表示断开连接
     */
    fun setGatt(gatt: BluetoothGatt?) {
        lock.withLock {
            this.gatt = gatt
            // 如果 GATT 为 null，清理所有任务
            if (gatt == null) clear()
        }
    }

    /**
     * 清空所有任务
     *
     * 取消当前任务，清空队列，并通知所有任务失败。
     */
    fun clear() {
        val pendingTasks: List<BleTask>
        val currentT: BleTask?
        lock.withLock {
            // 取消超时任务
            cancelTimeout()
            // 保存待处理任务列表
            pendingTasks = queue.toList()
            // 清空队列
            queue.clear()
            // 保存当前任务
            currentT = currentTask
            // 重置状态
            writeState = null
            currentTask = null
            isProcessing = false
        }
        // 在工作线程通知所有任务失败
        workerHandler.post {
            currentT?.completionCallback?.invoke(false)
            pendingTasks.forEach { it.completionCallback?.invoke(false) }
        }
    }

    /**
     * 任务失败处理（必须在持锁状态下调用）
     *
     * 核心逻辑：
     * 1. **自动降级机制**：如果是写入任务且当前分块大于 20 字节，通知上层失败，
     *    上层重传时会自动降级为 20 字节小包
     * 2. **重试机制**：非降级任务如果重试次数未用尽，重新入队等待重试
     * 3. **失败通知**：重试次数用尽或不可重试的任务，通知上层失败
     *
     * @param reason 失败原因
     */
    private fun taskFailedLocked(reason: String) {
        // 取消超时任务
        cancelTimeout()
        val t = currentTask

        t?.let { task ->
            // ================= 【核心黑科技：原地无缝降级】 =================
            // 当大包发送失败时（如 MTU 协商失败或设备不支持大包），
            // 直接通知上层失败，上层重传时会自动降级为 20 字节小包
            if (task is BleTask.Write && writeState != null) {
                // 如果当前分块大于 20 字节
                if (writeState!!.currentChunk().size > 20) {
                    logger.w("write task failed: id:${task.id} , $reason")
                    // 清理状态
                    currentTask = null
                    writeState = null
                    isProcessing = false

                    // 通知上层失败，上层 OBKCmdSender 收到 false 后会执行重传逻辑
                    // 重传时调用 enqueueWrite 会被自动切成 20 字节的小包
                    workerHandler.post { t.completionCallback?.invoke(false) }
                    workerHandler.post { processNext() }
                    // 标记正在发送大数据
                    BleHelper.setSendingBigData(config.macAddress, true)
                    return
                }
            }
            // =============================================================

            // 以下为常规的失败处理（适用于不可降级的任务）
            var shouldRetry = false
            // 如果重试次数未用尽
            if (task.retryCount < task.maxRetry) {
                task.retryCount++
                // 重新入队
                queue.offer(task)
                shouldRetry = true
                logger.w("Task retry: ${task.javaClass.simpleName}, attempt: ${task.retryCount}, id:${task.id}")
            }

            // 清理状态
            currentTask = null
            writeState = null
            isProcessing = false

            // 如果不需要重试，通知上层失败
            if (!shouldRetry) {
                logger.e("Task failed: ${t.javaClass.simpleName}, reason: $reason")
                workerHandler.post { t.completionCallback?.invoke(false) }
            }

            // 处理下一个任务
            workerHandler.post { processNext() }
        } ?: run {
            // 当前任务为 null，直接清理状态
            currentTask = null
            writeState = null
            isProcessing = false
            workerHandler.post { processNext() }
        }
    }

    /**
     * 任务失败处理（带锁）
     *
     * @param reason 失败原因
     */
    private fun taskFailed(reason: String) {
        lock.withLock { taskFailedLocked(reason) }
    }

    /**
     * 启动任务超时计时
     *
     * @param task 要监控的任务
     */
    private fun startTimeout(task: BleTask) {
        // 先取消之前的超时任务
        cancelTimeout()
        // 创建新的超时任务
        timeoutRunnable = Runnable {
            logger.w("Task timeout: $task")
            taskFailed("Timeout")
        }
        // 延迟执行超时任务
        workerHandler.postDelayed(timeoutRunnable!!, config.operationTimeout)
    }

    /**
     * 取消超时计时
     */
    private fun cancelTimeout() {
        timeoutRunnable?.let { workerHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }


    // ==================== 暴露给 BleDevice 的操作 API ====================

    /**
     * 入队读取任务
     *
     * @param serviceUuid 服务 UUID
     * @param characteristicUuid 特征值 UUID
     */
    fun enqueueRead(serviceUuid: UUID, characteristicUuid: UUID) {
        enqueue(BleTask.Read(serviceUuid, characteristicUuid))
    }

    /**
     * 入队写入任务（支持自动分包）
     *
     * 分包策略：
     * - 如果设备需要安全负载（通过 BleHelper 判断），使用 20 字节分包
     * - 否则使用当前 MTU 计算有效负载（MTU - 3 字节头部）
     *
     * @param serviceUuid 服务 UUID
     * @param characteristicUuid 特征值 UUID
     * @param data 要写入的数据
     * @param withResponse 是否需要响应
     * @param priority 任务优先级
     * @param progressCallback 进度回调（已发送字节数，总字节数）
     * @param completionCallback 完成回调
     */
    fun enqueueWrite(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray,
        withResponse: Boolean,
        priority: Int = BleTask.PRIORITY_NORMAL,
        progressCallback: ((Int, Int) -> Unit)? = null,
        completionCallback: ((Boolean) -> Unit)? = null
    ) {
        // 计算分包大小
        val payloadSize = if (BleHelper.isSafePayloadForMac(context, config.macAddress)) {
            // 安全模式：使用 20 字节小包
            20
        } else {
            // 正常模式：使用当前 MTU
            currentMtu - MTU_HEADER_SIZE
        }

        // 分包处理
        val chunks = if (payloadSize <= 0 || data.size <= payloadSize) {
            // 数据量小于等于一个分块，不需要分包
            listOf(data)
        } else {
            // 数据量大于一个分块，进行分包
            data.toList().chunked(payloadSize).map { it.toByteArray() }
        }

        // 确定写入类型
        val writeType =
            if (withResponse) BleTask.Write.WriteType.WITH_RESPONSE else BleTask.Write.WriteType.WITHOUT_RESPONSE

        // 创建写入任务
        val task =
            BleTask.Write(serviceUuid, characteristicUuid, chunks, writeType, data.size, priority)
                .apply {
                    this.completionCallback = completionCallback
                    this.progressCallback = progressCallback
                }

        // 入队
        enqueue(task)
    }

    /**
     * 入队启用/禁用通知任务
     *
     * @param serviceUuid 服务 UUID
     * @param characteristicUuid 特征值 UUID
     * @param enable true 启用，false 禁用
     * @param isIndication true 表示 Indication，false 表示 Notification
     */
    fun enqueueEnableNotification(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enable: Boolean,
        isIndication: Boolean
    ) {
        enqueue(BleTask.EnableNotification(serviceUuid, characteristicUuid, enable, isIndication))
    }

    /**
     * 入队 MTU 协商任务
     *
     * @param mtu 请求的 MTU 值
     */
    fun enqueueRequestMtu(mtu: Int) {
        enqueue(BleTask.RequestMtu(mtu))
    }

    /**
     * 入队任务（私有方法）
     *
     * @param task 要入队的任务
     */
    private fun enqueue(task: BleTask) {
        lock.withLock { queue.offer(task) }
        processNext()
    }

    // ==================== 暴露给 BleDevice 的任务完成回调 API ====================

    /**
     * 读取任务完成回调
     *
     * @param uuid 特征值 UUID
     * @param success 是否成功
     */
    fun readCompleted(uuid: UUID, success: Boolean) {
        val t = lock.withLock { currentTask }
        // 验证任务类型和 UUID 是否匹配
        if (t is BleTask.Read && t.characteristicUuid == uuid) {
            finishCurrentTask(success)
        }
    }

    /**
     * 写入任务完成回调
     *
     * 处理分包写入的进度，推进到下一个分块或完成任务。
     *
     * @param uuid 特征值 UUID
     * @param success 是否成功
     */
    fun writeCompleted(uuid: UUID, success: Boolean) {
        lock.withLock {
            val state = writeState
            // 验证状态和 UUID 是否匹配
            if (state != null && state.task.characteristicUuid == uuid) {
                handleWriteResultLocked(state, success)
            }
        }
    }

    /**
     * 通知任务完成回调
     *
     * @param characteristicUuid 特征值 UUID
     * @param success 是否成功
     */
    fun notificationCompleted(characteristicUuid: UUID, success: Boolean) {
        val t = lock.withLock { currentTask }
        // 验证任务类型和 UUID 是否匹配
        if (t is BleTask.EnableNotification && t.characteristicUuid == characteristicUuid) {
            finishCurrentTask(success)
        }
    }

    /**
     * MTU 变化回调
     *
     * 更新当前 MTU 值，并完成 MTU 协商任务。
     *
     * @param success 是否成功
     * @param mtu 新的 MTU 值
     */
    fun onMTUChange(success: Boolean, mtu: Int) {
        val t = lock.withLock { currentTask }
        // 如果当前任务是 MTU 请求任务，完成它
        if (t is BleTask.RequestMtu) finishCurrentTask(success)
        // 更新当前 MTU 值（无论成功与否）
        this.currentMtu = mtu
        logger.i("mtu change :$mtu , isSuccess:$success")
    }


    /**
     * 处理写入结果（必须在持锁状态下调用）
     *
     * 处理分包写入的进度：
     * 1. 如果失败，触发任务失败处理
     * 2. 如果成功，推进到下一个分块
     * 3. 如果所有分块发送完成，完成任务
     * 4. 否则继续发送下一个分块
     *
     * @param state 当前写入状态
     * @param success 是否成功
     */
    private fun handleWriteResultLocked(state: WriteState, success: Boolean) {
        // 取消超时任务
        cancelTimeout()

        // 如果写入失败
        if (!success) {
            logger.e("Write failed at chunk ${state.currentIndex + 1}")
            taskFailedLocked("Write chunk failed")
            return
        }

        // 推进到下一个分块
        state.advance()

        // 进度回调（在工作线程执行）
        state.task.progressCallback?.let { callback ->
            workerHandler.post { callback(state.sentBytes, state.task.totalBytes) }
        }

        // 判断是否完成所有分块
        if (state.isComplete()) {
            // 多分包时打印日志
            if (state.task.chunks.size > 1) {
                logger.d("Write completed: ${state.sentBytes} bytes")
            }
            // 标记发送大数据完成
            BleHelper.setSendingBigData(config.macAddress, false)

            // 清理状态并完成任务
            val t = currentTask
            currentTask = null
            writeState = null
            isProcessing = false

            // 在工作线程通知完成并处理下一个任务
            workerHandler.post {
                t?.completionCallback?.invoke(true)
                processNext()
            }
        } else {
            // 继续发送下一个分块
            // 放到 Handler 执行，避免在回调线程里死锁底层 GATT
            workerHandler.post {
                if (!sendNextChunk()) {
                    taskFailed("Failed to send next chunk")
                }
            }
        }
    }


    /**
     * 发送下一个分块
     *
     * 注意：此方法不在锁保护下执行，使用临时变量保存状态以避免死锁。
     *
     * @return true 表示发送成功或已完成，false 表示发送失败
     */
    private fun sendNextChunk(): Boolean {
        // 使用临时变量存状态，提前释放锁，防止 writeInternal 死锁
        val state = lock.withLock { writeState } ?: return false
        val gatt = this.gatt ?: return false

        // 如果没有下一个分块，完成任务
        if (!state.hasNext()) {
            finishCurrentTask(true)
            return true
        }

        // 获取当前分块
        val chunk = state.currentChunk()

        // 多分包时打印进度日志
        if (state.hasNext() && state.task.chunks.size > 1) {
            logger.d("Sending chunk ${state.currentIndex + 1}/${state.task.chunks.size}, size: ${chunk.size}")
        }

        // 启动超时计时
        startTimeout(state.task)

        // 执行实际写入操作
        val result = writeInternal(
            gatt,
            state.task.serviceUuid,
            state.task.characteristicUuid,
            chunk,
            state.task.writeType == BleTask.Write.WriteType.WITH_RESPONSE
        )

        // 如果底层直接返回 false，触发降级机制
        if (!result) {
            taskFailed("WriteInternal return false")
        }

        return result
    }

    /**
     * 查找特征值
     *
     * @param gatt GATT 连接实例
     * @param serviceUuid 服务 UUID
     * @param charUuid 特征值 UUID
     * @return 特征值对象，如果未找到返回 null
     */
    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        charUuid: UUID
    ): BluetoothGattCharacteristic? {
        return gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
    }
    // ==================== 核心调度与执行逻辑 ====================

    /**
     * 处理下一个任务
     *
     * 调度逻辑：
     * 1. 检查是否正在处理、队列是否为空、GATT 是否可用
     * 2. 如果条件满足，从队列获取任务并标记为处理中
     * 3. 根据重试次数计算动态延迟，延迟执行任务
     */
    private fun processNext() {
        val nextTask = lock.withLock {
            // 如果正在处理、队列空或 GATT 不可用，直接返回
            if (isProcessing || queue.isEmpty() || gatt == null) return
            // 标记为处理中
            isProcessing = true
            // 从队列获取任务
            currentTask = queue.poll()
            currentTask
        }

        nextTask?.let { task ->
            // 根据重试次数计算动态延迟（重试次数越多，延迟越长）
            val delayTime = BleDelayStrategy.getDynamicDelay(task.retryCount)
            // 延迟执行任务
            workerHandler.postDelayed({
                startTimeout(task)
                executeTask(task)
            }, delayTime)
        }
    }

    /**
     * 完成当前任务
     *
     * @param success 是否成功
     */
    private fun finishCurrentTask(success: Boolean) {
        val task = lock.withLock {
            // 取消超时任务
            cancelTimeout()
            // 保存当前任务
            val t = currentTask
            // 清理状态
            currentTask = null
            writeState = null
            isProcessing = false
            t
        }
        // 通知任务完成
        task?.let { workerHandler.post { it.completionCallback?.invoke(success) } }
        // 处理下一个任务
        processNext()
    }

    /**
     * 执行写入任务
     *
     * @param task 写入任务
     * @return true 表示任务已启动，false 表示任务已完成
     */
    private fun executeWrite(task: BleTask.Write): Boolean {
        // 如果没有分块数据，直接完成任务
        if (task.chunks.isEmpty()) {
            finishCurrentTask(true)
            return true
        }
        // 创建写入状态
        lock.withLock {
            writeState = WriteState(task)
        }
        // 发送第一个分块
        return sendNextChunk()
    }


    /**
     * 执行蓝牙任务
     *
     * 根据任务类型分发到不同的执行方法：
     * - Read：读取特征值
     * - Write：写入特征值
     * - EnableNotification：启用/禁用通知
     * - RequestMtu：请求 MTU 协商
     *
     * @param task 要执行的任务
     */
    @Suppress("MissingPermission")
    private fun executeTask(task: BleTask) {
        // 检查 GATT 是否可用
        val gatt = this.gatt ?: run { taskFailed("GATT is null"); return }

        // 根据任务类型执行
        val success = when (task) {
            is BleTask.Read -> executeRead(gatt, task)
            is BleTask.Write -> executeWrite(task)
            is BleTask.EnableNotification -> executeEnableNotification(gatt, task)
            is BleTask.RequestMtu -> gatt.requestMtu(task.mtu)
        }

        logger.d("任务 $task 执行结果:$success")

        // 如果执行失败，触发任务失败处理
        if (!success) taskFailed("任务 $task 执行失败!")
    }

    /**
     * 执行读取任务
     *
     * @param gatt GATT 连接实例
     * @param task 读取任务
     * @return true 表示读取操作已启动，false 表示失败
     */
    @Suppress("MissingPermission")
    private fun executeRead(gatt: BluetoothGatt, task: BleTask.Read): Boolean {
        // 查找特征值
        val char =
            findCharacteristic(gatt, task.serviceUuid, task.characteristicUuid) ?: return false
        // 执行读取
        return gatt.readCharacteristic(char)
    }

    /**
     * 执行写入操作（内部方法）
     *
     * 根据特征值属性自动选择合适的写入类型，并处理 Android 版本差异。
     *
     * @param gatt GATT 连接实例
     * @param serviceUuid 服务 UUID
     * @param characteristicUuid 特征值 UUID
     * @param data 要写入的数据
     * @param withResponse 是否需要响应
     * @return true 表示写入操作已启动，false 表示失败
     */
    @OptIn(ExperimentalStdlibApi::class)
    @Suppress("MissingPermission", "DEPRECATION")
    private fun writeInternal(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray,
        withResponse: Boolean
    ): Boolean {
        // 查找特征值
        val char = findCharacteristic(gatt, serviceUuid, characteristicUuid) ?: return false

        // 确定写入类型
        val writeType = if (withResponse) {
            // 需要响应的写入
            if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
                // 特征值不支持 WRITE，降级为 NO_RESPONSE
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        } else {
            // 不需要响应的写入
            if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
                // 特征值不支持 WRITE_NO_RESPONSE，降级为 DEFAULT
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
        }

        // 根据 Android 版本选择写入方式
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用新 API
            gatt.writeCharacteristic(char, data, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            // 旧版本使用传统方式
            char.value = data
            char.writeType = writeType
            gatt.writeCharacteristic(char)
        }
    }

    /**
     * 执行启用/禁用通知任务
     *
     * @param gatt GATT 连接实例
     * @param task 通知任务
     * @return true 表示操作已启动，false 表示失败
     */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun executeEnableNotification(
        gatt: BluetoothGatt,
        task: BleTask.EnableNotification
    ): Boolean {
        // 查找特征值
        val char =
            findCharacteristic(gatt, task.serviceUuid, task.characteristicUuid) ?: return false

        // 设置特征值通知
        if (!gatt.setCharacteristicNotification(char, task.enable)) return false

        // 获取 CCCD 描述符
        val descriptor = char.getDescriptor(CCCD_UUID) ?: return false

        // 设置描述符值
        val value = when {
            !task.enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            task.isIndication -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        // 根据 Android 版本选择写入方式
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用新 API
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            // 旧版本使用传统方式
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }
}
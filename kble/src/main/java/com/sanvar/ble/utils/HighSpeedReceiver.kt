package com.sanvar.ble.utils

import com.sanvar.ble.BleCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue

class HighSpeedReceiver(private val externalCallback: BleCallback) {

    // 1. 无阻塞的数据通道（容量设为 500 个包，防爆内存）
    private val dataChannel = Channel<BlePacket>(capacity = 500)

    // 2. 预先分配好的空闲包裹池（假设有 200 个包裹轮转）
    private val idlePool = ArrayBlockingQueue<BlePacket>(400)

    init {
        // App 启动或连接成功时，一次性把这 200 个包裹 new 出来放进池子
        // 这 200 个对象 + 里面的 ByteArray(512)，总共才占用大约 100KB 内存，且永不 GC！
        for (i in 0 until 400) {
            idlePool.offer(BlePacket())
        }

        // 开启专门的消费者协程去解析和回调
        startConsumer()
    }

    /**
     * 在系统的 Binder 线程中被极其高频地调用 (例如每 20ms 一次)
     */
    fun onReceivedData(serviceUUID: UUID, uuid: UUID, data: ByteArray) {
        // 1. 极速从池子里抓一个空包裹出来
        val packet = idlePool.poll()

        if (packet != null) {
            // 2. 极速深拷贝数据！这解决了底层 byte[] 复用导致的解析乱码问题！
            // 且底层是 C++ memmove，极其快速。
            System.arraycopy(data, 0, packet.buffer, 0, data.size)

            // 3. 赋值元数据
            packet.length = data.size
            packet.serviceUUID = serviceUUID
            packet.uuid = uuid

            // 4. 扔进通道排队，非阻塞，瞬间释放 Binder 线程！
            dataChannel.trySend(packet)

        } else {
            // 【极其罕见的异常】池子被借空了！
            // 说明业务层解析得太慢了，或者 Channel 塞满了。
            // 这种情况下，为了保命，你只能选择丢包，或者临时 new 一个对象（但会破坏 0 GC）。
            val logger = BleLogger.withTag("HighSpeedReceiver")
            logger.e("Buffer pool exhausted! Dropping packet.")
        }
    }

    /**
     * 在专门的业务协程中执行
     */
    private fun startConsumer() = CoroutineScope(Dispatchers.IO).launch {
        for (packet in dataChannel) {
            try {
                // 1. 此时的 packet 数据绝对安全，尽情地去校验包头、包尾吧！
                // 哪怕你在这里耗时 10ms，底层依然能把新收到的包塞进池子的其他空包裹里，互不干扰。
                val safeData = packet.buffer
                val len = packet.length

                // 2. 注意！ 不能把 packet 的引用抛出去，因为马上就要回收它了！
                val finalValidArray = safeData.copyOfRange(0, len)
                externalCallback.onReceivedData(
                    packet.serviceUUID!!,
                    packet.uuid!!,
                    finalValidArray
                )

            } finally {
                // 3. 【最关键的一步：归还包裹】
                // 把包裹里的标记清空，扔回空闲池，等待下一次被复用！
                packet.reset()
                idlePool.offer(packet)
            }
        }
    }
}
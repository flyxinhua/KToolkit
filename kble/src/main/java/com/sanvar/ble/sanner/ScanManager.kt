package com.sanvar.ble.sanner

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import com.sanvar.ble.utils.BleLogger
import java.util.concurrent.CopyOnWriteArraySet

class ScanManager private constructor() {
    private val adapter: BluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    // 使用 CopyOnWriteArraySet 保证遍历时的线程安全，无需额外加锁
    private val callbackSet: MutableSet<ScanCallback> = CopyOnWriteArraySet<ScanCallback>()

    // 所有的逻辑控制都扔到这个 Handler 所在的线程（主线程），避免多线程竞争
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isScanning = false
    private var refCount = 0 // 由于都在 Handler 中操作，不需要 AtomicInteger

    // ================= 公共方法 =================
    fun startScan(cb: ScanCallback?) {
        if (cb == null) return
        // 确保回调添加和引用计数逻辑在同一线程执行
        mainHandler.post(Runnable {
            if (!callbackSet.contains(cb)) {
                callbackSet.add(cb)
                refCount++
                BleLogger.d("scanCount Ref++: $refCount")
                // 只有从 0 变 1 时才启动扫描循环
                if (refCount == 1) {
                    performStartScan()
                }
            }
        })
    }

    fun stopScan(cb: ScanCallback?) {
        if (cb == null) return

        mainHandler.post(Runnable {
            if (callbackSet.contains(cb)) {
                callbackSet.remove(cb)
                refCount--
                BleLogger.d("scanCount Ref--: $refCount")
                // 修正：防止 refCount 变为负数（虽然逻辑上不太可能，但作为防御）
                if (refCount < 0) refCount = 0

                // 归零时，彻底停止
                if (refCount == 0) {
                    performStopScan(true) // true 表示彻底停止，清除所有任务
                }
            }
        })
    }

    // ================= 内部核心逻辑 (仅在 Handler 线程运行) =================
    /**
     * 实际执行开始扫描
     */
    private fun performStartScan() {
        if (adapter == null || !adapter.isEnabled()) {
            BleLogger.w("蓝牙不可用或未开启")
            // 可以在这里做一个延迟重试，或者直接放弃
            return
        }

        if (isScanning) return  // 状态保护


        // 尝试启动扫描 (API check omitted for brevity, but consider using BluetoothLeScanner on API 21+)
        val success = adapter.startLeScan(innerCallback)

        if (success) {
            isScanning = true
            BleLogger.d("扫描器已启动 (Duty Cycle Start)")

            // 设定 "一段时间后暂停" 的任务
            mainHandler.removeCallbacks(stopTask) // 移除旧的
            mainHandler.postDelayed(stopTask, SCAN_DURATION.toLong())
        } else {
            BleLogger.d("启动扫描失败 (底层返回 false)")
            // 即使失败，也要重置状态，或者尝试稍后重试
            isScanning = false
        }
    }

    /**
     * 实际执行停止扫描
     * @param forceStop true: 用户主动停止，清除后续循环; false: 循环中的间歇性暂停
     */
    private fun performStopScan(forceStop: Boolean) {
        // 无论当前是否 isScanning，都移除所有挂起的任务，防止逻辑污染
        mainHandler.removeCallbacks(startTask)
        mainHandler.removeCallbacks(stopTask)

        if (isScanning && adapter != null && adapter.isEnabled()) {
            adapter.stopLeScan(innerCallback)
            isScanning = false
            BleLogger.d("扫描器已暂停/停止")
        }

        if (!forceStop) {
            // 如果不是强制停止（即处于间歇休息模式），则安排稍后重启
            BleLogger.d("进入间歇休息 (Idle for " + IDLE_DURATION + "ms)")
            mainHandler.postDelayed(startTask, IDLE_DURATION.toLong())
        } else {
            BleLogger.d("扫描器彻底关闭")
        }
    }

    // ================= Runnables =================
    // 任务：开始扫描（用于间歇休息结束后的重启）
    private val startTask: Runnable = Runnable { // 双重保险：只有当还有人在监听时才重启
        if (refCount > 0) {
            performStartScan()
        }
    }

    // 任务：停止扫描（用于扫描一段时间后的休息）
    private val stopTask: Runnable = Runnable { // 执行暂停，参数 false 表示这是“间歇性暂停”，不是用户主动停止
        performStopScan(false)
    }

    // ================= Callback =================
    private val innerCallback =
        BluetoothAdapter.LeScanCallback { device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray? ->
            // 不要在回调里做任何耗时操作，也不要加锁，CopyOnWriteArraySet 也是线程安全的
            for (cb in callbackSet) {
                cb.onScanDevice(device, rssi, scanRecord)
            }
        }

    interface ScanCallback {
        fun onScanDevice(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?)
    }


    companion object {
        @Volatile
        private var instance: ScanManager? = null

        fun getInstance(): ScanManager {
            if (instance == null) {
                synchronized(ScanManager::class.java) {
                    if (instance == null) {
                        instance = ScanManager()
                    }
                }
            }
            return instance!!
        }

        private const val SCAN_DURATION = 30_000 // 扫描 30秒
        private const val IDLE_DURATION = 1000 // 休息 1秒
    }
}
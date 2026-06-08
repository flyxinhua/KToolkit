package com.sanvar.ble.sanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.sanvar.ble.utils.BleLogger
import java.util.LinkedList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 工业级低功耗蓝牙扫描管理器 (Kotlin Object 单例)
 *
 * 核心特性：
 * 1. 引用计数：统一调度，避免多个业务模块互相干扰。
 * 2. 射频避让：连接设备时主动暂停扫描 3 秒，保证握手成功率。
 * 3. 热缓存机制：新订阅者瞬间获取 10 秒内的历史扫描结果，实现“秒连”。
 * 4. 防封锁策略：严格绕过 Android 系统“30秒内最多启动5次扫描”的底层限制。
 * 5. 极致兼容：不使用底层 ScanFilter，全量高功率抓取，规避部分手机硬件过滤 Bug。
 */
@SuppressLint("MissingPermission")
object ScanManager {

    private const val TAG = "ScanManager"
    private val logger = BleLogger.withTag(TAG)

    // 强制所有状态变更在主线程排队执行，彻底消灭多线程并发修改导致的异常
    private val mainHandler = Handler(Looper.getMainLooper())

    // 业务层订阅者集合
    private val callbackSet = CopyOnWriteArraySet<ScanCallbackWrapper>()

    // ================= 状态控制变量 =================
    private var isScanning = false
    private var refCount = 0
    private var isPausedByConnection = false // 连接避让状态锁

    // ================= 扫描热缓存 (Hot Cache) =================
    private data class CachedResult(val result: ScanResult, val timestamp: Long)

    private val scanCache = mutableMapOf<String, CachedResult>()

    private const val CACHE_VALID_TIME_MS = 10_000L // 缓存有效期：10秒
    private const val CLEAR_CACHE_DELAY_MS = 5000L  // 无人使用后延迟清空缓存的时间

    // ================= 系统限制防封锁 (Throttling) =================
    private val startTimestamps = LinkedList<Long>()
    private const val MAX_STARTS_IN_WINDOW = 5       // 系统上限 5 次
    private const val WINDOW_MS = 30_000L            // 系统时间窗口 30 秒
    private const val APPLY_DEBOUNCE_MS = 300L       // 扫描启停的防抖时间 (合并高频请求)
    private const val CONNECTION_PAUSE_MS = 2000L    // 连接时的射频避让时间

    // 【新增】：扫描 1 分钟休息 3 秒的配置
    private const val CONTINUOUS_SCAN_MAX_MS = 60_000L // 持续扫描最大时长：1分钟
    private const val CONTINUOUS_SCAN_REST_MS = 3000L  // 强制休息时长：3秒
    private var isResting = false                      // 强制休息状态锁


    @Volatile
    private var isInitialized = false
    private var adapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null

    // 【核心修复】：不要用变量存死 scanner，改用动态的 get() 属性！
    // 这样只要蓝牙一打开，下次调用 scanner?.startScan 时就会自动拿到有效的扫描器。
    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    /**
     * 初始化扫描器 (建议在 Application 或首个 Activity 初始化时调用一次)
     */
    /**
     * 初始化扫描器
     */
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return // 双重检查锁的标准写法
            bluetoothManager =
                (context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            adapter = bluetoothManager!!.adapter
            isInitialized = true
            logger.d("Initialized with adapter=${adapter != null}")
        }
    }


    /**
     *  是否存在系统绑定的列表中
     *
     * @param mac  目标设备地址
     * @return
     */
    fun isInBondedList(mac: String): Boolean {
        return adapter?.bondedDevices?.any { it.address == mac } == true
    }


    /**
     *  获取系统绑定的或者已连接的列表
     */
    fun getSystemDevices(): List<BluetoothDevice> {
        val deviceList = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
            ?: emptyList<BluetoothDevice>()
        // 合并两个列表
        deviceList.plus(adapter?.bondedDevices)
        return deviceList
    }


    // ================= 物理扫描控制策略 =================

    // 【新增】：触发强制休息的任务
    private val enterRestTask = Runnable {
        if (isScanning) {
            logger.w("持续扫描达1分钟，暂停3秒")
            isResting = true
            performStopPhysical() // 立即停止底层物理扫描

            // 安排 3 秒后结束休息
            mainHandler.postDelayed(exitRestTask, CONTINUOUS_SCAN_REST_MS)
        }
    }

    // 【新增】：结束休息的任务
    private val exitRestTask = Runnable {
        logger.i("强制休息结束，准备恢复扫描")
        isResting = false
        performApply() // 重新评估是否需要启动扫描
    }


    // ================= 公共 API =================

    /**
     * 业务层请求启动扫描
     */
    fun startScan(cb: ScanCallbackWrapper) {
        mainHandler.post {
            if (callbackSet.add(cb)) {
                refCount++
                logger.i("[Start] RefCount=$refCount. Subscriber added.")

                // 【核心机制：热缓存回放】
                // 当新的监听器加入时，立刻把最近 10 秒内搜到的设备吐给它。
                // 如果恰好处于“连接避让期”(物理扫描已停)，这个机制能让新任务依然瞬间拿到数据并开始连接！
                pushCacheToCallback(cb)

                // 安排物理扫描启动评估 (带防抖)
                scheduleApply()
            }
        }
    }

    /**
     * 业务层请求停止扫描
     */
    fun stopScan(cb: ScanCallbackWrapper) {
        mainHandler.post {
            if (callbackSet.remove(cb)) {
                refCount--
                logger.i("[Stop] RefCount=$refCount. Subscriber removed.")
                if (refCount <= 0) {
                    refCount = 0
                    // 如果没有任何业务需要扫描了，安排延迟清空缓存释放内存
                    scheduleClearCache()
                }
                scheduleApply()
            }
        }
    }

    /**
     * 【关键信号】连接避让请求
     * 由 BleConnector 在发起 `connectGatt` 之前调用。
     * 作用：强行停止底层扫描 3 秒，把手机唯一的蓝牙天线全部让给连接握手，极大提高连接成功率。
     */
    fun onConnectionSignal() {
        mainHandler.post {
            logger.w("因需要连接，暂停扫描")
            isPausedByConnection = true

            // 【新增】：连接避让本身也是一种休息，直接重置周期扫描的休息状态
            isResting = false
            mainHandler.removeCallbacks(exitRestTask)

            // 立即停止物理层扫描
            performStopPhysical()

            mainHandler.removeCallbacks(resumeTask)
            mainHandler.postDelayed(resumeTask, CONNECTION_PAUSE_MS)
        }
    }

    // ================= 内部调度与缓存逻辑 =================

    /** 将存活期内的缓存结果立刻分发给指定的监听器 */
    private fun pushCacheToCallback(cb: ScanCallbackWrapper) {
        val now = SystemClock.elapsedRealtime()
        val iterator = scanCache.values.iterator()
        while (iterator.hasNext()) {
            val cached = iterator.next()
            if (now - cached.timestamp <= CACHE_VALID_TIME_MS) {
                cb.onScanResult(cached.result)
            } else {
                iterator.remove() // 顺手清理掉过期的缓存
            }
        }
    }

    private val clearCacheTask = Runnable {
        if (refCount == 0) {
            Log.d(TAG, "No active subscribers for a while. Clearing scan cache.")
            scanCache.clear()
        }
    }

    private fun scheduleClearCache() {
        mainHandler.removeCallbacks(clearCacheTask)
        mainHandler.postDelayed(clearCacheTask, CLEAR_CACHE_DELAY_MS)
    }

    // ================= 物理扫描控制策略 =================

    private val applyTask = Runnable { performApply() }

    private val resumeTask = Runnable {
        Log.d(TAG, "Connection pause expired. Checking if scan needs to resume...")
        isPausedByConnection = false
        performApply()
    }

    private fun scheduleApply() {
        mainHandler.removeCallbacks(applyTask)
        mainHandler.postDelayed(applyTask, APPLY_DEBOUNCE_MS)
    }

    /**
     * 评估当前状态，决定是否需要启动/停止物理底层扫描
     */
    private fun performApply() {
        // 1. 如果当前处于连接避让锁定期，禁止启动物理扫描
        if (isPausedByConnection) {
            Log.d(TAG, "Currently in connection pause lock. Ignore physical start.")
            return
        }

        // 【新增】：2. 如果当前处于1分钟后的强制休息期，禁止启动物理扫描
        if (isResting) {
            Log.d(TAG, "Currently in continuous scan rest lock. Ignore physical start.")
            return
        }

        // 3. 如果没有任何订阅者，关闭物理扫描
        if (refCount <= 0) {
            performStopPhysical()
            return
        }

        // 4. 有订阅者且天线空闲，尝试启动物理扫描
        performStartPhysical()
    }

    private fun performStartPhysical() {
        if (isScanning) return // 已经在全量扫了，不需要动
        if (adapter?.isEnabled != true) return
        val currentScanner = scanner ?: adapter?.bluetoothLeScanner ?: return

        // --- 【核心防封锁机制：检查 30秒/5次 限制】 ---
        val now = SystemClock.elapsedRealtime()
        // 清理 30 秒以前的时间戳记录
        while (startTimestamps.isNotEmpty() && now - startTimestamps.first > WINDOW_MS) {
            startTimestamps.removeFirst()
        }

        // 如果在过去 30 秒内已经启动了 4 次，说明遇到了极其频繁的启停（如连续断连重试）
        // 必须强行推迟本次启动，否则系统会抛出 ErrorCode=2 (SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) 彻底封死扫描
        if (startTimestamps.size >= MAX_STARTS_IN_WINDOW - 1) {
            Log.e(
                TAG,
                "WARNING: Reaching Android system scan limit (5 times/30s). Postponing scan for 5 seconds!"
            )
            mainHandler.postDelayed({ performApply() }, 5000L)
            return
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            currentScanner.startScan(null, settings, innerCallback)

            isScanning = true
            startTimestamps.addLast(now)
            logger.i("蓝牙扫描器开始扫描。")

            // 【新增】：成功启动扫描后，安排 1 分钟后进入强制休息
            mainHandler.removeCallbacks(enterRestTask)
            mainHandler.postDelayed(enterRestTask, CONTINUOUS_SCAN_MAX_MS)

        } catch (e: Exception) {
            logger.e("Physical scan start failed: ${e.message}")
        }
    }

    private fun performStopPhysical() {
        // 【新增】：只要物理扫描停止，就立刻取消“1分钟进入休息”的倒计时任务
        mainHandler.removeCallbacks(enterRestTask)

        if (!isScanning) return
        try {
            scanner?.stopScan(innerCallback)
            logger.i("蓝牙扫描器停止扫描")
        } catch (e: Exception) {
            Log.e(TAG, "Physical scan stop failed: ${e.message}")
        } finally {
            isScanning = false
        }
    }

    // ================= 底层回调与分发 =================

    private val innerCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 1. 更新热缓存
            val mac = result.device.address
            scanCache[mac] = CachedResult(result, SystemClock.elapsedRealtime())
            // 2. 实时分发给所有活跃的订阅者
            // 这里不过滤 MAC，过滤逻辑由具体的业务层 (BleConnector / AddDeviceUI) 自己判断
            callbackSet.forEach { it.onScanResult(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed! ErrorCode: $errorCode")
            isScanning = false
        }
    }

    /**
     * 业务层使用的回调接口包装
     */
    interface ScanCallbackWrapper {
        fun onScanResult(result: ScanResult)
    }
}
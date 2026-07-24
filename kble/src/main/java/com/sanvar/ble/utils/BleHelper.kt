package com.sanvar.ble.utils

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙辅助工具类 - Object 单例
 *
 * 核心职责：
 * 1. **大包降级黑名单**：记录因发大包导致频繁断开的设备，后续强制使用 20 字节小包通信
 * 2. **UUID 友好名称缓存**：提供 UUID → 短名称的全局缓存映射
 * 3. **大数据发送状态跟踪**：标记设备当前是否正在发送大数据，辅助判断断开原因
 *
 * 黑名单机制（三次违规法）：
 * - 5 分钟时间窗口内，累计发生 3 次 status=8 断开（连接超时）
 * - 且断开时正在发送大数据 —— 则判定该设备不支持大包传输
 * - 加入黑名单后，后续通信强制使用 20 字节小包
 * - 黑名单数据持久化到 SharedPreferences，应用重启后仍然有效
 */
object BleHelper {

    /** 日志实例 */
    private val logger = BleLogger.withTag("BleHelper")

    /**
     * UUID → 友好短名称的全局缓存
     *
     * 线程安全的 ConcurrentHashMap，多设备并发访问无问题。
     * 用于设备信息回调中的 UUID 名称映射，如 "2A19" 映射为 "BatteryLevel"。
     */
    val uuidMap: ConcurrentHashMap<UUID, String> = ConcurrentHashMap()

    /** SharedPreferences 存储名称 */
    private const val PREFS_NAME = "BleMtuBlacklist"

    /** 触发黑名单所需的违规次数（5 分钟内累计 3 次） */
    private const val MAX_STRIKES = 2

    /** 违规时间窗口（5 分钟），超出此时间则重置计数 */
    private const val WINDOW_MS = 8 * 60 * 1000L

    /**
     * 违规记录
     *
     * @param strikeCount 违规次数
     * @param firstStrikeTime 首次违规时间（elapsedRealtime）
     */
    private class DeathRecord(var strikeCount: Int = 0, var firstStrikeTime: Long = 0L)

    /** 违规记录缓存（MAC 大写 → 记录），线程安全 */
    private val macDeathRecords = ConcurrentHashMap<String, DeathRecord>()

    /** 黑名单内存缓存（MAC 大写 → true），命中即强制使用 20 字节小包 */
    private val blacklistedMacs = ConcurrentHashMap<String, Boolean>()

    /** 设备是否正在发送大数据（MAC 大写 → true/false），用于辅助判断断开原因 */
    private val sendingBigDataFlags = ConcurrentHashMap<String, Boolean>()

    /** 是否已从 SharedPreferences 加载过黑名单磁盘数据 */
    @Volatile
    private var isBlacklistLoaded = false

    // ==================== 黑名单检查 ====================

    /**
     * 检查指定 MAC 地址是否在黑名单中，是否需要使用安全的 20 字节 Payload
     *
     * 首次调用时从 SharedPreferences 懒加载黑名单数据到内存缓存。
     *
     * @param context Android 上下文
     * @param macAddress 设备 MAC 地址
     * @return true 表示命中黑名单，必须使用 20 字节小包通信
     */
    fun isSafePayloadForMac(context: Context, macAddress: String): Boolean {
        val mac = macAddress.uppercase()

        // 双检锁懒加载：首次调用时从磁盘读取黑名单到内存
        if (!isBlacklistLoaded) {
            synchronized(this) {
                if (!isBlacklistLoaded) {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.all.forEach { (key, value) ->
                        // Key 格式为 "MAC_00:1A:7D:XX:XX:XX"
                        if (key.startsWith("MAC_") && value == true) {
                            // 去掉 "MAC_" 前缀，存入内存缓存
                            blacklistedMacs[key.substring(4)] = true
                        }
                    }
                    isBlacklistLoaded = true
                }
            }
        }

        // 纯内存查找，耗时纳秒级
        return blacklistedMacs[mac] == true
    }

    // ==================== 违规报告与黑名单管理 ====================

    /**
     * 报告一次可疑的 status=8 断开事件
     *
     * status=8 是 BLE 错误码，通常表示连接超时或设备不可达。
     * 如果断开时正在发送大数据，则认定为一次违规。
     * 在 WINDOW_MS 分钟窗口内累计 MAX_STRIKES 次违规后，将该 MAC 加入黑名单并持久化。
     *
     * @param context Android 上下文
     * @param macAddress 设备 MAC 地址
     */
    fun reportSuspiciousStatus8(context: Context, macAddress: String) {
        val mac = macAddress.uppercase()
        val now = SystemClock.elapsedRealtime()

        // 只有正在发送大数据时发生的 status=8 才记录
        if (sendingBigDataFlags[mac] != true) {
            logger.i("不是发大数据引起的 status = 8，故不记录。")
            return
        }

        // 获取或创建违规记录
        val record = macDeathRecords.getOrPut(mac) { DeathRecord() }

        synchronized(record) {
            // 超出 5 分钟窗口，重置计数
            if (now - record.firstStrikeTime > WINDOW_MS) {
                record.strikeCount = 1
                record.firstStrikeTime = now
            } else {
                record.strikeCount++
            }

            // 累计 3 次违规，加入黑名单
            if (record.strikeCount >= MAX_STRIKES) {
                // 1. 立即更新内存缓存，后续通信即时生效
                blacklistedMacs[mac] = true

                // 2. 异步写入 SharedPreferences 持久化
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit {
                        putBoolean("MAC_$mac", true)
                    }

                // 3. 清理违规记录，释放内存
                macDeathRecords.remove(mac)

                logger.w("$mac 已加入黑名单，后续通信将强制使用 20 字节小包")
            }
        }
    }

    // ==================== 大数据发送状态管理 ====================

    /**
     * 设置设备是否正在发送大数据
     *
     * @param mac 设备 MAC 地址
     * @param isSending true 表示正在发送，false 表示已完成
     */
    fun setSendingBigData(mac: String, isSending: Boolean) {
        sendingBigDataFlags[mac.uppercase()] = isSending
    }

    /**
     * 查询设备是否正在发送大数据
     *
     * @param mac 设备 MAC 地址
     * @return true 表示正在发送大数据
     */
    fun isSendingBigData(mac: String): Boolean {
        return sendingBigDataFlags[mac.uppercase()] == true
    }
}

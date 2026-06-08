package com.sanvar.ble.internal

import android.os.Build
import java.util.Locale

object BleDelayStrategy {

    // 定义几个延迟档位 (单位: 毫秒)
    private const val DELAY_AGGRESSIVE = 10L   // 激进型：针对顶级旗舰机
    private const val DELAY_NORMAL = 18L      // 正常型：针对绝大多数现代手机 (你目前的10ms在这里)
    private const val DELAY_CONSERVATIVE = 30L// 保守型：针对老旧机型、非高通芯片

    // 缓存计算结果，避免每次发包都去判断字符串
    private val baseDelay: Long by lazy {
        calculateBaseDelay()
    }

    private fun calculateBaseDelay(): Long {
        val apiLevel = Build.VERSION.SDK_INT
        val hardware = Build.HARDWARE.lowercase(Locale.ENGLISH)
        val board = Build.BOARD.lowercase(Locale.ENGLISH)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ENGLISH)

        // 1. 极其老旧的系统 (Android 7.0 及以下)，无脑给最高延迟，保命要紧
        if (apiLevel < Build.VERSION_CODES.O) {
            return DELAY_CONSERVATIVE
        }

        // 2. 判断芯片组 (判断硬件字符串中是否包含特定厂家的特征)
        val isQualcomm = hardware.contains("qcom") || hardware.contains("msm") ||
                hardware.contains("sdm") || hardware.contains("sm") ||
                board.contains("msm")

        val isMediaTek =
            hardware.contains("mt") || board.contains("mt") || hardware.contains("mediatek")
        val isUnisoc =
            hardware.contains("sprd") || hardware.contains("unisoc") || hardware.contains("sc")

        // 3. 综合评级
        return when {
            // Android 10以上 + 高通芯片 = 性能怪兽，全速前进
            apiLevel >= Build.VERSION_CODES.Q && isQualcomm -> DELAY_AGGRESSIVE

            // 联发科 / 展锐芯片 = 稳妥起见，拉长延迟防止丢包
            isMediaTek || isUnisoc -> DELAY_CONSERVATIVE

            // 某些以蓝牙兼容性差著称的品牌旧机型 (可选策略)
            manufacturer == "vivo" || manufacturer == "oppo" || manufacturer == "redmi" -> {
                if (apiLevel <= Build.VERSION_CODES.P) DELAY_CONSERVATIVE else DELAY_NORMAL
            }

            // 默认情况，使用正常延迟 (12ms)
            else -> DELAY_NORMAL
        }
    }

    /**
     * 获取最终的发送延迟时间
     * @param currentMtu 当前连接协商的 MTU 大小
     * @param retryCount 当前逻辑包的重试次数 (极其重要)
     */
    fun getDynamicDelay(retryCount: Int): Long {
        var delay = baseDelay
        // 【核心】退避算法：如果已经发生了重传，说明遇到了网络拥堵或设备端处理瓶颈！
        // 每重试一次，延迟增加 10ms。例如：正常发是 12ms，失败1次后用 22ms 发，失败2次用 32ms 发。
        if (retryCount > 0) {
            delay += (retryCount * 10L)
        }

        return delay
    }
}
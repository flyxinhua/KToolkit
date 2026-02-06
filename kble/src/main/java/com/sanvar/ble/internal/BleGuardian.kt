package com.sanvar.ble.internal

import android.os.Handler
import android.os.Looper
import com.sanvar.ble.BleConfig
import com.sanvar.ble.DisconnectReason
import com.sanvar.ble.utils.BleLogger

/**
 * 连接守护者 - 内部类
 */
internal class BleGuardian(
    private val config: BleConfig,
    private val onReconnect: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "BleGuardian"


    @Volatile private var isGuarding = false
    @Volatile private var isPaused = false
    @Volatile private var isReconnecting = false

    // 快速重试
    private var quickRetryCount = 0
    private val maxQuickRetry = 2
    private val quickRetryDelay = 300L

    // 守护重连
    private var reconnectCount = 0
    private var reconnectRunnable: Runnable? = null

    fun startGuard() {
        if (isGuarding) return
        isGuarding = true
        resetCounters()
        BleLogger.d("$TAG Guardian started")
    }

    fun stopGuard() {
        isGuarding = false
        cancelReconnect()
        resetCounters()
        BleLogger.d("$TAG Guardian stopped")
    }

    fun pause() {
        if (isPaused) return
        isPaused = true
        cancelReconnect()
        BleLogger.d("$TAG Guardian paused")
    }

    fun resume(isConnected: Boolean) {
        if (!isPaused) return
        isPaused = false
        BleLogger.d("$TAG Guardian resumed")

        if (isGuarding && !isConnected && !isReconnecting) {
            scheduleReconnect(0)
        }
    }

    fun onConnected() {
        resetCounters()
        cancelReconnect()
        isReconnecting = false
    }

    fun onDisconnected(reason: DisconnectReason, gattStatus: Int, isConnectPhase: Boolean) {
        isReconnecting = false

        if (reason == DisconnectReason.USER_REQUEST || !isGuarding || isPaused) {
            return
        }

        if (isConnectPhase && shouldQuickRetry(gattStatus)) {
            scheduleQuickRetry()
        } else {
            quickRetryCount = 0
            scheduleReconnect(config.reconnectInterval)
        }
    }

    private fun shouldQuickRetry(status: Int): Boolean {
        if (quickRetryCount >= maxQuickRetry) return false
        return status in listOf(133, 8, 19, 22, 34, 62)
    }

    private fun scheduleQuickRetry() {
        quickRetryCount++
        BleLogger.d("$TAG Quick retry: $quickRetryCount/$maxQuickRetry")

        isReconnecting = true
        handler.postDelayed({
            if (isGuarding && !isPaused) {
                onReconnect()
            } else {
                isReconnecting = false
            }
        }, quickRetryDelay)
    }

    private fun scheduleReconnect(delay: Long) {
        if (config.maxReconnectAttempts >= 0 && reconnectCount >= config.maxReconnectAttempts) {
            BleLogger.w("$TAG Max reconnect reached: $reconnectCount")
            return
        }

        cancelReconnect()
        reconnectCount++
        BleLogger.d("$TAG Reconnect: $reconnectCount in ${delay}ms")

        isReconnecting = true
        reconnectRunnable = Runnable {
            if (isGuarding && !isPaused) {
                onReconnect()
            } else {
                isReconnecting = false
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun resetCounters() {
        quickRetryCount = 0
        reconnectCount = 0
    }

    fun isGuarding() = isGuarding
    fun isPaused() = isPaused
}
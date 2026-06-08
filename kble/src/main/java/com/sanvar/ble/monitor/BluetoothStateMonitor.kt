package com.sanvar.ble.monitor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sanvar.ble.utils.BleLogger
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 蓝牙状态监听器
 *
 * - 单例模式
 * - 引用计数管理广播注册
 * - 有监听者时注册广播，无监听者时取消广播
 */
class BluetoothStateMonitor private constructor() {

    /** 日志标签 */
    private val logger = BleLogger.withTag("BluetoothStateMonitor")

    /**
     * 蓝牙状态变化回调接口
     */
    fun interface Callback {
        fun onBluetoothStateChanged(enabled: Boolean)
    }

    /** 回调集合（线程安全） */
    private val callbacks = CopyOnWriteArraySet<Callback>()

    /** 主线程 Handler，确保回调在主线程执行 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 应用上下文 */
    private var context: Context? = null

    /** 广播接收器是否已注册 */
    private var isReceiverRegistered = false

    /** 单例实例 */
    companion object {
        val instance: BluetoothStateMonitor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            BluetoothStateMonitor()
        }
    }

    /**
     * 蓝牙状态广播接收器
     */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    logger.d("Bluetooth ON")
                    notifyCallbacks(true)
                }

                BluetoothAdapter.STATE_OFF -> {
                    logger.d("Bluetooth OFF")
                    notifyCallbacks(false)
                }
            }
        }
    }

    /**
     * 初始化（在 Application 中调用）
     */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * 注册回调
     * @param callback 回调
     * @param notifyCurrentState 是否立即通知当前蓝牙状态
     */
    fun register(callback: Callback, notifyCurrentState: Boolean = false) {
        mainHandler.post {
            val wasEmpty = callbacks.isEmpty()

            if (callbacks.add(callback)) {
                logger.d("callback registered, count: ${callbacks.size}")

                // 第一个监听者，注册广播
                if (wasEmpty) {
                    registerReceiver()
                }

                // 立即通知当前状态
                if (notifyCurrentState) {
                    callback.onBluetoothStateChanged(isBluetoothEnabled())
                }
            }
        }
    }

    /**
     * 注销回调
     */
    fun unregister(callback: Callback) {
        mainHandler.post {
            if (callbacks.remove(callback)) {
                logger.d("callback unregistered, count: ${callbacks.size}")

                // 没有监听者了，取消广播
                if (callbacks.isEmpty()) {
                    unregisterReceiver()
                }
            }
        }
    }

    private fun registerReceiver() {
        val ctx = context ?: return
        if (isReceiverRegistered) return

        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
            logger.d("receiver registered")
        } catch (e: Exception) {
            logger.e("Register receiver failed", e)
        }
    }

    private fun unregisterReceiver() {
        if (!isReceiverRegistered) return

        try {
            context?.unregisterReceiver(receiver)
            isReceiverRegistered = false
            logger.d("receiver unregistered")
        } catch (e: Exception) {
            logger.e("Unregister receiver failed", e)
        }
    }

    private fun notifyCallbacks(enabled: Boolean) {
        callbacks.forEach {
            try {
                it.onBluetoothStateChanged(enabled)
            } catch (e: Exception) {
                logger.e("Callback error", e)
            }
        }
    }

    /**
     * 蓝牙是否已开启
     */
    fun isBluetoothEnabled(): Boolean {
        return try {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取当前监听者数量
     */
    fun getCallbackCount(): Int = callbacks.size

    /**
     * 广播是否已注册
     */
    fun isReceiverRegistered(): Boolean = isReceiverRegistered
}
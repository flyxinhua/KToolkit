// AppLifecycleMonitor.kt
package com.sanvar.ble.monitor

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sanvar.ble.utils.BleLogger
import java.util.concurrent.CopyOnWriteArraySet

/**
 * App前后台监听器
 *
 * - 单例模式
 * - 引用计数管理生命周期观察
 * - 有监听者时注册观察者，无监听者时移除观察者
 */
class AppLifecycleMonitor private constructor() : LifecycleEventObserver {

    fun interface Callback {
        fun onForegroundChanged(isForeground: Boolean)
    }

    private val callbacks = CopyOnWriteArraySet<Callback>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isForeground = false

    @Volatile
    private var isObserverRegistered = false

    companion object {
        val instance: AppLifecycleMonitor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AppLifecycleMonitor()
        }
    }

    /**
     * 注册回调
     * @param callback 回调
     * @param notifyCurrentState 是否立即通知当前状态
     */
    fun register(callback: Callback, notifyCurrentState: Boolean = true) {
        mainHandler.post {
            val wasEmpty = callbacks.isEmpty()

            if (callbacks.add(callback)) {
                BleLogger.d("AppLifecycleMonitor callback registered, count: ${callbacks.size}")

                // 第一个监听者，注册观察者
                if (wasEmpty) {
                    registerObserver()
                }

                // 立即通知当前状态
                if (notifyCurrentState) {
                    callback.onForegroundChanged(isForeground)
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
                BleLogger.d("AppLifecycleMonitor callback unregistered, count: ${callbacks.size}")

                // 没有监听者了，移除观察者
                if (callbacks.isEmpty()) {
                    unregisterObserver()
                }
            }
        }
    }

    private fun registerObserver() {
        if (isObserverRegistered) return

        try {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            isObserverRegistered = true

            // 获取当前状态
            isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

            BleLogger.d("AppLifecycleMonitor observer registered, isForeground: $isForeground")
        } catch (e: Exception) {
            BleLogger.e("Register observer failed", e)
        }
    }

    private fun unregisterObserver() {
        if (!isObserverRegistered) return

        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
            isObserverRegistered = false
            BleLogger.d("AppLifecycleMonitor observer unregistered")
        } catch (e: Exception) {
            BleLogger.e("Unregister observer failed", e)
        }
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> {
                isForeground = true
                BleLogger.d("App -> Foreground")
                notifyCallbacks(true)
            }
            Lifecycle.Event.ON_STOP -> {
                isForeground = false
                BleLogger.d("App -> Background")
                notifyCallbacks(false)
            }
            else -> {}
        }
    }

    private fun notifyCallbacks(isForeground: Boolean) {
        callbacks.forEach {
            try {
                it.onForegroundChanged(isForeground)
            } catch (e: Exception) {
                BleLogger.e("Callback error", e)
            }
        }
    }

    /**
     * App是否在前台
     */
    fun isForeground(): Boolean = isForeground

    /**
     * 获取当前监听者数量
     */
    fun getCallbackCount(): Int = callbacks.size

    /**
     * 观察者是否已注册
     */
    fun isObserverRegistered(): Boolean = isObserverRegistered
}
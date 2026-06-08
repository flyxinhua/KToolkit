package com.sanvar.ble.utils

import android.util.Log

/**
 * 日志规范接口
 */
interface ILogger {
    fun d(message: String)
    fun i(message: String)
    fun w(message: String)
    fun e(message: String, throwable: Throwable? = null)
}

/**
 * SDK 全局日志输出中心
 */
object BleLogger : ILogger {
    private const val GLOBAL_TAG = "KBle"

    // 默认为 true，且在打印时真正生效
    @Volatile
    var isEnabled = true
        private set

    // 最小日志级别限制，默认为 Log.DEBUG
    @Volatile
    var minPriority = Log.DEBUG
        private set

    // 暴露给外部自定义日志引擎（比如写入文件、上传服务器）
    private var _printer: (priority: Int, tag: String, message: String) -> Unit =
        { priority, tag, message ->
            Log.println(priority, tag, message)
        }

    /**
     *  设置日志打印器
     *
     * @param printer
     */
    fun setLogPrinter(printer: (priority: Int, tag: String, message: String) -> Unit) {
        _printer = printer
    }

    /**
     *  是否打印日志
     *
     * @param enabled
     */
    fun setEnabled(enabled: Boolean) {
        this.isEnabled = enabled
    }

    /**
     * 设置最小日志输出级别（如 Log.INFO）
     */
    fun setMinPriority(priority: Int) {
        this.minPriority = priority
    }

    /**
     * 创建一个带专属 TAG 的局部日志对象
     */
    fun withTag(tag: String): ILogger {
        return TaggedLogger(tag)
    }

    // ================= 全局直调 =================

    override fun d(message: String) = printLog(Log.DEBUG, GLOBAL_TAG, message)
    override fun i(message: String) = printLog(Log.INFO, GLOBAL_TAG, message)
    override fun w(message: String) = printLog(Log.WARN, GLOBAL_TAG, message)
    override fun e(message: String, throwable: Throwable?) =
        printLog(Log.ERROR, GLOBAL_TAG, message, throwable)

    // ================= 内部打印引擎 =================

    internal fun printLog(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        if (!isEnabled) return // 修复 Bug 1
        if (priority < minPriority) return // 过滤低于设定级别的日志

        // 拼接异常的完整堆栈，而不仅仅是 message
        val finalMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        _printer(priority, tag, finalMessage)

    }
}

/**
 * 局部标签日志实现类
 */
private class TaggedLogger(private val localTag: String) : ILogger {
    override fun d(message: String) = BleLogger.printLog(Log.DEBUG, localTag, message)
    override fun i(message: String) = BleLogger.printLog(Log.INFO, localTag, message)
    override fun w(message: String) = BleLogger.printLog(Log.WARN, localTag, message)
    override fun e(message: String, throwable: Throwable?) =
        BleLogger.printLog(Log.ERROR, localTag, message, throwable)
}
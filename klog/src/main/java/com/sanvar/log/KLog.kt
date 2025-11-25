package com.sanvar.log

import android.util.Log

/**
 * KLog is a simple and powerful logger for Android and Kotlin.
 * It provides features like lazy message evaluation, automatic stack trace information (file name and line number),
 * and handling for long log messages.
 *
 * KLog 是一个为 Android 和 Kotlin 设计的简单而强大的日志记录器。
 * 它提供了诸如懒加载消息、自动获取堆栈信息（文件名和行号）以及处理长日志消息等功能。
 */
object KLog {

    private var _priority = Log.DEBUG
    private var _isDebug: Boolean = false
    private var _output: LogPrinter? = null
    private val maxLength = 4000

    /**
     * Initializes the KLog logger with custom settings. This method should be called once, typically in your Application class.
     * 使用自定义设置初始化 KLog 记录器。此方法应仅调用一次，通常在您的 Application 类中。
     *
     * @param isDebug If true, the thread name will be included in the log output. This is useful for debugging multi-threaded applications.
     *                如果为 true，线程名将被包含在日志输出中。这对于调试多线程应用很有用。
     * @param minPriority The minimum priority level for a log message to be printed. Logs with a priority lower than this will be ignored. Defaults to Log.DEBUG.
     *                    日志消息被打印的最低优先级。低于此优先级的日志将被忽略。默认为 Log.DEBUG。
     * @param output The LogPrinter implementation that handles the actual log output. If null, no logs will be printed.
     *               处理实际日志输出的 LogPrinter 实现。如果为 null，则不会打印任何日志。
     */
    fun setup(isDebug: Boolean, minPriority: Int = Log.DEBUG, output: LogPrinter? = null) {
        _priority = minPriority
        _isDebug = isDebug
        _output = output
    }


    private fun buildMsg(obj: Any?): String {
        if (obj is String) {
            return obj
        }
        if (obj == null) {
            return "null"
        }
        if (!obj.javaClass.isArray) {
            return obj.toString()
        }
        if (obj is Array<*> && obj.isArrayOf<Any>()) {
            return obj.contentDeepToString()
        }
        return obj.toString()
    }

    private fun fileName(): String {
        val stack = Throwable().stackTrace.getOrNull(3) ?: return ""
        return if (_isDebug) {
            "${Thread.currentThread().name}|(${stack.fileName}:${stack.lineNumber}) "
        } else {
            "(${stack.fileName}:${stack.lineNumber}) "
        }
    }

    /**
     * Sends a DEBUG log message.
     * The log message will be produced by the provided lambda function only if the log priority is met, which improves performance by avoiding unnecessary string construction.
     *
     * 发送一个 DEBUG 级别的日志消息。
     * 只有当日志优先级满足条件时，才会通过提供的 lambda 函数生成日志消息，这通过避免不必要的字符串构建来提高性能。
     *
     * @param msg A lambda function that returns the log message.
     *            一个返回日志消息的 lambda 函数。
     */
    fun d(msg: () -> Any) {
        if (Log.DEBUG >= _priority) {
            output(Log.DEBUG, msg)
        }
    }

    /**
     * Sends an INFO log message.
     * The log message will be produced by the provided lambda function only if the log priority is met.
     *
     * 发送一个 INFO 级别的日志消息。
     * 只有当日志优先级满足条件时，才会通过提供的 lambda 函数生成日志消息。
     *
     * @param msg A lambda function that returns the log message.
     *            一个返回日志消息的 lambda 函数。
     */
    fun i(msg: () -> Any) {
        if (Log.INFO >= _priority) {
            output(Log.INFO, msg)
        }
    }

    /**
     * Sends a WARN log message.
     * The log message will be produced by the provided lambda function only if the log priority is met.
     *
     * 发送一个 WARN 级别的日志消息。
     * 只有当日志优先级满足条件时，才会通过提供的 lambda 函数生成日志消息。
     *
     * @param msg A lambda function that returns the log message.
     *            一个返回日志消息的 lambda 函数。
     */
    fun w(msg: () -> Any) {
        if (Log.WARN >= _priority) {
            output(Log.WARN, msg)
        }
    }

    /**
     * Sends an ERROR log message with an accompanying throwable.
     * The log message will be produced by the provided lambda function.
     *
     * 发送一个带有附加 throwable 的 ERROR 级别日志消息。
     * 日志消息将通过提供的 lambda 函数生成。
     *
     * @param throwable An optional throwable to be logged with its stack trace.
     *                  一个可选的 throwable，其堆栈轨迹将被一并记录。
     * @param msg A lambda function that returns the log message.
     *            一个返回日志消息的 lambda 函数。
     */
    fun e(throwable: Throwable? = null, msg: () -> Any) {
        if (Log.ERROR >= _priority) {
            fun finalMsg(): String {
                val thMsg = Log.getStackTraceString(throwable)
                return buildMsg(msg()) + " ,throwable:" + thMsg
            }
            output(Log.ERROR) { finalMsg() }
        }
    }

    /**
     * Sends an ERROR log message.
     * The log message will be produced by the provided lambda function only if the log priority is met.
     *
     * 发送一个 ERROR 级别的日志消息。
     * 只有当日志优先级满足条件时，才会通过提供的 lambda 函数生成日志消息。
     *
     * @param msg A lambda function that returns the log message.
     *            一个返回日志消息的 lambda 函数。
     */
    fun e(msg: () -> Any) {
        if (Log.ERROR >= _priority) {
            output(Log.ERROR, msg)
        }
    }


    private fun output(priority: Int, msgFun: () -> Any) {
        _output?.let {
            val msg = fileName() + buildMsg(msgFun())

            if (msg.length < maxLength) {
                it.printer(priority, msg)
                return
            }

            var start = 0
            while (start < msg.length) {
                val end = if (start + maxLength < msg.length) {
                    start + maxLength
                } else {
                    msg.length
                }
                val subMessage = msg.substring(start, end)
                it.printer(priority, subMessage)
                start = end
            }
        }
    }
}

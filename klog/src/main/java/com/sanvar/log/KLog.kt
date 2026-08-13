package com.sanvar.log

import android.util.Log

/**
 * Global entry point for KLog configuration and backward-compatible logging calls.
 * Use [withTag] to create a domain logger whose tag remains stable after R8 obfuscation.
 *
 * KLog 的全局配置入口，并保留原有日志调用。建议通过 [withTag] 创建领域 Logger，
 * 其 TAG 不受 R8 混淆影响。
 */
object KLog {

    private val engine = LogEngine()

    /**
     * Initializes KLog. This method should normally be called once from Application.
     *
     * 初始化 KLog。通常应在 Application 中调用一次。
     *
     * @param isDebug If true, logs include the thread and source location after their domain tag.
     *                If false, logs only include the domain tag and do not collect a stack trace.
     *                为 true 时在领域 TAG 后输出线程和源码位置；为 false 时只输出领域 TAG，且不采集调用堆栈。
     * @param minPriority The minimum priority required for a log to be printed.
     *                    允许输出的最低日志级别。
     * @param output The printer that receives formatted logs. If null, logs are discarded.
     *               接收格式化日志的打印器；为 null 时不输出日志。
     */
    fun setup(isDebug: Boolean, minPriority: Int = Log.DEBUG, output: LogPrinter? = null) {
        engine.setup(isDebug, minPriority, output)
    }

    /**
     * Creates an immutable logger for a business domain.
     * Blank tags fall back to `KLog`.
     *
     * 创建一个不可变的领域 Logger。空白 TAG 会回退为 `KLog`。
     */
    fun withTag(tag: String): Logger = engine.withTag(tag)

    /** Sends a DEBUG log using the default `KLog` domain tag. */
    fun d(msg: () -> Any) {
        engine.d(msg)
    }

    /** Sends an INFO log using the default `KLog` domain tag. */
    fun i(msg: () -> Any) {
        engine.i(msg)
    }

    /** Sends a WARN log using the default `KLog` domain tag. */
    fun w(msg: () -> Any) {
        engine.w(msg)
    }

    /** Sends an ERROR log with an optional throwable using the default `KLog` domain tag. */
    fun e(throwable: Throwable? = null, msg: () -> Any) {
        engine.e(throwable, msg)
    }

    /** Sends an ERROR log using the default `KLog` domain tag. */
    fun e(msg: () -> Any) {
        engine.e(msg)
    }
}

private class LogEngine {

    companion object {
        private const val DEFAULT_TAG = "KLog"
        private const val MAX_LENGTH = 4000
        private const val UNKNOWN_SOURCE = "Unknown Source"
        private const val R8_SOURCE_PREFIX = "r8-map-id-"
    }

    private val internalClassNames = arrayOf(KLog::class.java.name, LogEngine::class.java.name)
    private var priority = Log.DEBUG
    private var isDebug = false
    private var output: LogPrinter? = null

    fun setup(isDebug: Boolean, minPriority: Int, output: LogPrinter?) {
        priority = minPriority
        this.isDebug = isDebug
        this.output = output
    }

    fun withTag(tag: String): Logger = TaggedLogger(normalizeTag(tag))

    fun d(msg: () -> Any) {
        log(Log.DEBUG, DEFAULT_TAG, msg)
    }

    fun i(msg: () -> Any) {
        log(Log.INFO, DEFAULT_TAG, msg)
    }

    fun w(msg: () -> Any) {
        log(Log.WARN, DEFAULT_TAG, msg)
    }

    fun e(throwable: Throwable?, msg: () -> Any) {
        logError(DEFAULT_TAG, throwable, msg)
    }

    fun e(msg: () -> Any) {
        log(Log.ERROR, DEFAULT_TAG, msg)
    }

    private fun normalizeTag(tag: String): String = tag.trim().ifEmpty { DEFAULT_TAG }

    private fun log(priority: Int, tag: String, msg: () -> Any) {
        if (priority >= this.priority) {
            print(priority, tag, msg)
        }
    }

    private fun logError(tag: String, throwable: Throwable?, msg: () -> Any) {
        if (Log.ERROR >= priority) {
            print(Log.ERROR, tag) {
                val stackTrace = Log.getStackTraceString(throwable)
                buildMsg(msg()) + " ,throwable:" + stackTrace
            }
        }
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

    private fun findCallerFrame(): StackTraceElement? {
        return Throwable().stackTrace.firstOrNull { frame ->
            internalClassNames.none { className ->
                frame.className == className || frame.className.startsWith(className + "\$")
            }
        }
    }

    private fun hasValidSourceLocation(frame: StackTraceElement): Boolean {
        val fileName = frame.fileName?.trim().orEmpty()
        return frame.lineNumber > 0 &&
            fileName.isNotEmpty() &&
            fileName != UNKNOWN_SOURCE &&
            fileName != "SourceFile" &&
            !fileName.startsWith(R8_SOURCE_PREFIX)
    }

    private fun buildPrefix(tag: String): String {
        val tagPrefix = "[$tag]"
        if (!isDebug) {
            return "$tagPrefix "
        }

        val threadPrefix = "$tagPrefix ${Thread.currentThread().name}|"
        val frame = findCallerFrame() ?: return "$threadPrefix "
        return if (hasValidSourceLocation(frame)) {
            "$threadPrefix(${frame.fileName}:${frame.lineNumber}) "
        } else {
            "$threadPrefix "
        }
    }

    private fun print(priority: Int, tag: String, msgFun: () -> Any) {
        val printer = output ?: return
        val prefix = buildPrefix(tag)
            .takeIf { it.length < MAX_LENGTH }
            ?: "[$DEFAULT_TAG] "
        val message = buildMsg(msgFun())
        val chunkLength = MAX_LENGTH - prefix.length

        if (message.isEmpty()) {
            printer.printer(priority, prefix)
            return
        }

        var start = 0
        while (start < message.length) {
            val end = minOf(start + chunkLength, message.length)
            printer.printer(priority, prefix + message.substring(start, end))
            start = end
        }
    }

    private inner class TaggedLogger(private val tag: String) : Logger {
        override fun d(msg: () -> Any) {
            log(Log.DEBUG, tag, msg)
        }

        override fun i(msg: () -> Any) {
            log(Log.INFO, tag, msg)
        }

        override fun w(msg: () -> Any) {
            log(Log.WARN, tag, msg)
        }

        override fun e(throwable: Throwable?, msg: () -> Any) {
            logError(tag, throwable, msg)
        }

        override fun e(msg: () -> Any) {
            log(Log.ERROR, tag, msg)
        }
    }
}

package com.sanvar.log

/**
 * A logger bound to a stable business-domain tag.
 * Create instances with [KLog.withTag].
 *
 * 绑定稳定业务领域 TAG 的日志接口。请通过 [KLog.withTag] 创建实例。
 */
interface Logger {
    fun d(msg: () -> Any)

    fun i(msg: () -> Any)

    fun w(msg: () -> Any)

    fun e(throwable: Throwable? = null, msg: () -> Any)

    fun e(msg: () -> Any)
}

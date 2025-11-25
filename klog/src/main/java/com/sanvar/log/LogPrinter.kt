package com.sanvar.log

/**
 * The LogPrinter interface is used to define a standard for printing logs.
 * You can implement this interface to customize the log output behavior, such as printing to the console, a file, or a remote server.
 *
 * LogPrinter 接口用于定义打印日志的标准。
 * 您可以实现此接口来定制日志的输出行为，例如打印到控制台、文件或远程服务器。
 */
interface LogPrinter {
    /**
     * Prints the log message with the given priority.
     * 打印具有给定优先级的日志消息。
     *
     * @param priority The priority of the log message (e.g., Log.DEBUG, Log.ERROR).
     *                 日志消息的优先级（例如，Log.DEBUG, Log.ERROR）。
     * @param message The log message to be printed.
     *                要打印的日志消息。
     */
    fun printer(priority: Int, message: String)
}

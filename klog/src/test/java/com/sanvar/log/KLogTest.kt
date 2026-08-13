package com.sanvar.log

import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class KLogTest {

    private val printer = RecordingPrinter()

    @After
    fun tearDown() {
        KLog.setup(isDebug = false, output = null)
    }

    @Test
    fun taggedDebugLogIncludesDomainThreadAndClickableSourceLocation() {
        KLog.setup(isDebug = true, output = printer)
        val logger = KLog.withTag("BLE")

        logger.d { "scan started" }

        val message = printer.single().message
        assertTrue(message.startsWith("[BLE] ${Thread.currentThread().name}|(KLogTest.kt:"))
        assertTrue(message.matches(Regex("\\[BLE] .+\\|\\(KLogTest\\.kt:[1-9]\\d*\\) scan started")))
    }

    @Test
    fun taggedReleaseLogOnlyIncludesStableDomainTag() {
        val logger = KLog.withTag("Network")
        KLog.setup(isDebug = false, output = printer)

        logger.i { "request completed" }

        assertEquals("[Network] request completed", printer.single().message)
    }

    @Test
    fun legacyCallsRemainAvailableWithDefaultDomainTag() {
        KLog.setup(isDebug = false, output = printer)

        KLog.d { "legacy" }

        assertEquals("[KLog] legacy", printer.single().message)
    }

    @Test
    fun blankTagFallsBackToKLogAndTagIsTrimmed() {
        KLog.setup(isDebug = false, output = printer)

        KLog.withTag("   ").d { "blank" }
        KLog.withTag("  Database  ").d { "trimmed" }

        assertEquals(listOf("[KLog] blank", "[Database] trimmed"), printer.messages())
    }

    @Test
    fun taggedLoggerSupportsAllPrioritiesAndThrowable() {
        KLog.setup(isDebug = false, output = printer)
        val logger = KLog.withTag("Firmware")
        val throwable = IllegalStateException("boom")

        logger.d { "debug" }
        logger.i { "info" }
        logger.w { "warn" }
        logger.e { "error" }
        logger.e(throwable) { "failure" }

        assertEquals(
            listOf(Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ERROR),
            printer.entries.map { it.priority },
        )
        assertTrue(printer.entries.all { it.message.startsWith("[Firmware] ") })
        assertTrue(printer.entries.last().message.contains("failure ,throwable:"))
        assertTrue(printer.entries.last().message.contains("IllegalStateException: boom"))
    }

    @Test
    fun priorityFilterPreservesLazyMessageEvaluation() {
        var evaluations = 0
        KLog.setup(isDebug = false, minPriority = Log.INFO, output = printer)
        val logger = KLog.withTag("BLE")

        logger.d {
            evaluations++
            "filtered"
        }
        logger.i {
            evaluations++
            "printed"
        }

        assertEquals(1, evaluations)
        assertEquals(listOf("[BLE] printed"), printer.messages())
    }

    @Test
    fun longTaggedLogRepeatsPrefixWithoutLosingContentAndEvaluatesOnce() {
        val payload = buildString {
            repeat(9_000) { append(('a'.code + it % 26).toChar()) }
        }
        var evaluations = 0
        KLog.setup(isDebug = false, output = printer)
        val logger = KLog.withTag("Telemetry")

        logger.d {
            evaluations++
            payload
        }

        assertEquals(1, evaluations)
        assertTrue(printer.entries.size > 1)
        assertTrue(printer.entries.all { it.message.startsWith("[Telemetry] ") })
        assertTrue(printer.entries.all { it.message.length <= 4_000 })
        assertEquals(
            payload,
            printer.entries.joinToString(separator = "") { it.message.removePrefix("[Telemetry] ") },
        )
    }

    private fun RecordingPrinter.single(): Entry {
        assertEquals(1, entries.size)
        return entries.single()
    }

    private fun RecordingPrinter.messages(): List<String> = entries.map { it.message }

    private class RecordingPrinter : LogPrinter {
        val entries = mutableListOf<Entry>()

        override fun printer(priority: Int, message: String) {
            entries += Entry(priority, message)
        }
    }

    private data class Entry(val priority: Int, val message: String)
}

package com.lagradost.cloudstream3.utils.diagnostics

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque

/**
 * In-memory, bounded, allow-listed diagnostic events. No stream URLs, request bodies,
 * page titles, exception messages, cookies, tokens or arbitrary extension payloads.
 * Diagnostic history is discarded when the app process ends or Clear is pressed.
 */
object DiagnosticLog {
    private const val MAX_EVENTS = 140
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private var sequence = 0L
    private var latestSession = 0L
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun label(text: String): String = text
        .replace(Regex("[^a-zA-Z0-9 _.=:+-]"), "_").take(50)

    /** New attempt ID. For overlapping provider calls, pass this ID to each stage. */
    fun start(provider: String): Long = synchronized(lock) {
        val id = ++sequence
        latestSession = id
        append("#$id PROVIDER START provider=${label(provider)}")
        id
    }

    private fun append(message: String) {
        val time = clock.format(Date())
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast("$time $message")
        Log.d("CloudStreamDiag", message)
    }

    /** Detail must be a fixed string or a number only, never raw server/extension text. */
    fun event(stage: String, status: String, detail: String = "", session: Long? = null) {
        synchronized(lock) {
            val id = session ?: latestSession
            append("#$id ${label(stage)} ${label(status)} ${label(detail)}")
        }
    }

    /** No throwable.message or stack trace: they frequently contain signed video URLs. */
    fun error(stage: String, cause: Throwable, session: Long? = null, code: Int? = null) {
        val type = label(cause.javaClass.simpleName.ifBlank { "Throwable" })
        val nested = cause.cause?.let { label(it.javaClass.simpleName) }
        val summary = "type=$type" + (nested?.let { " cause=$it" } ?: "") +
            (code?.let { " code=$it" } ?: "")
        event(stage, "FAIL", summary, session)
    }

    fun clear() = synchronized(lock) {
        events.clear()
        latestSession = 0L
    }

    fun report(): String = synchronized(lock) {
        buildString {
            appendLine("CLOUDSTREAM DIAGNOSTIC v1")
            appendLine("=========================")
            appendLine("Local time; in-memory history; latest ${events.size} events")
            appendLine("Only stage/status, provider label, counts, elapsed time, error type/code.")
            appendLine("URLs, titles, HTTP headers, tokens, cookies, exception messages: NOT COLLECTED")
            appendLine("Note: provider internals and HTTP requests are not instrumented in v1.")
            appendLine()
            if (events.isEmpty()) appendLine("No events yet. Load a title and try Play first.")
            else events.forEach { appendLine(it) }
        }
    }
}

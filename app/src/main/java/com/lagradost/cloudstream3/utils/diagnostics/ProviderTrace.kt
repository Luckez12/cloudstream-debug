package com.lagradost.cloudstream3.utils.diagnostics

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Dedicated provider trace. Does not read or alter Android Logcat. */
object ProviderTrace {
    private const val MAX_ENTRIES = 2500
    private data class Entry(val at: String, val op: Long, val level: String, val stage: String, val info: String)
    private data class Pending(val provider: String, val stage: String, val since: Long)
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val pending = linkedMapOf<Long, Pending>()
    private var counter = 0L
    private var dropped = 0L
    private fun clean(value: String): String = value.replace(Regex("[^a-zA-Z0-9 _.=:+()/-]"), "_").take(120)
    private fun stamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    private fun record(op: Long, level: String, stage: String, info: String) = synchronized(lock) {
        if (entries.size >= MAX_ENTRIES) { entries.removeFirst(); dropped++ }
        entries.addLast(Entry(stamp(), op, level, stage, info))
    }

    fun begin(stage: String, provider: String, details: String = ""): Long = synchronized(lock) {
        val id = ++counter
        pending[id] = Pending(clean(provider), clean(stage), SystemClock.elapsedRealtime())
        record(id, "START", clean(stage), "provider=${clean(provider)} ${clean(details)}")
        id
    }

    fun note(op: Long, stage: String, details: String) {
        record(op, "INFO", clean(stage), clean(details))
    }

    fun finish(op: Long, details: String = "") = synchronized(lock) {
        val item = pending.remove(op) ?: return@synchronized
        val elapsed = SystemClock.elapsedRealtime() - item.since
        record(op, "PASS", item.stage, "provider=${item.provider} elapsed=${elapsed}ms ${clean(details)}")
        if (elapsed >= 5000) record(op, "SLOW", item.stage, "elapsed=${elapsed}ms")
    }

    fun failure(op: Long, type: String, details: String = "") = synchronized(lock) {
        val item = pending.remove(op)
        val elapsed = item?.let { SystemClock.elapsedRealtime() - it.since } ?: 0L
        record(op, "FAIL", item?.stage ?: "REQUEST", "provider=${item?.provider ?: "unknown"} type=${clean(type)} elapsed=${elapsed}ms ${clean(details)}")
    }

    /** Never retain throwable messages (which often contain complete signed stream URLs). */
    fun exception(op: Long, cause: Throwable) {
        failure(op, cause.javaClass.simpleName.ifBlank { "Throwable" })
        var t: Throwable? = cause
        var depth = 0
        while (t != null && depth < 3) {
            val current = t
            note(op, "STACK", "cause=$depth type=${clean(current.javaClass.name)}")
            current.stackTrace.take(18).forEach { frame ->
                note(op, "STACK", "at=${clean(frame.className)}.${clean(frame.methodName)}:${frame.lineNumber}")
            }
            t = current.cause
            depth++
        }
    }

    /** Explicit operation IDs keep concurrent homepage/metadata/HTTP requests separate. */
    suspend fun <T> observe(stage: String, provider: String, details: String = "", action: suspend () -> T): T {
        val op = begin(stage, provider, details)
        return try {
            val result = action()
            finish(op)
            result
        } catch (t: Throwable) {
            exception(op, t)
            throw t
        }
    }

    fun clear() = synchronized(lock) {
        entries.clear(); pending.clear(); dropped = 0L
    }

    /** Render unfinished stages with live elapsed time, even if provider is still hanging. */
    fun important(): String = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        buildString {
            appendLine("CLOUDSTREAM PROVIDER DIAGNOSTIC — IMPORTANT")
            appendLine("Active requests: ${pending.size} | events: ${entries.size} | discarded: $dropped")
            pending.forEach { (id, task) ->
                val age = now - task.since
                appendLine("#$id ${task.stage} ${task.provider} WAITING ${age}ms" + if (age >= 5000) " [SLOW]" else "")
            }
            entries.filter { it.level == "FAIL" || it.level == "SLOW" }.takeLast(40).forEach { e ->
                appendLine("${e.at} #${e.op} ${e.level} ${e.stage} ${e.info}")
            }
            if (pending.isEmpty() && entries.none { it.level == "FAIL" || it.level == "SLOW" })
                appendLine("No failures or slow requests recorded yet.")
            appendLine("The log covers instrumented CloudStream paths only; custom extension clients may not be visible.")
        }
    }

    fun full(): String = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        buildString {
            appendLine("CLOUDSTREAM PROVIDER DIAGNOSTIC — FULL TRACE")
            appendLine("Entries: ${entries.size}/$MAX_ENTRIES; discarded=$dropped | active=${pending.size}")
            appendLine("Shared HTTP requests appear by timestamp and own operation ID; not assumed to belong to a provider.")
            appendLine("No HTTP bodies, raw URLs, queries, tokens, cookies or exception messages captured.")
            pending.forEach { (id, task) -> appendLine("PENDING #$id ${task.stage} provider=${task.provider} waiting=${now - task.since}ms") }
            entries.forEach { e -> appendLine("${e.at} #${e.op} ${e.level} ${e.stage} ${e.info}") }
        }
    }
}

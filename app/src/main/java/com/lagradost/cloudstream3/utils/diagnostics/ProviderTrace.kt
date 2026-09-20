package com.lagradost.cloudstream3.utils.diagnostics

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Provider-level telemetry only; never reads or changes Android Logcat. */
object ProviderTrace {
    private const val MAX_ENTRIES = 2500
    private data class Entry(
        val at: String,
        val op: Long,
        val level: String,
        val stage: String,
        val info: String,
        val section: String
    )
    private data class Pending(val provider: String, val stage: String, val since: Long)
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val pending = linkedMapOf<Long, Pending>()
    private var counter = 0L
    private var dropped = 0L

    /** Explicitly exclude URLs, headers and exception messages from stored details. */
    private fun clean(value: String): String = value.replace(Regex("[^a-zA-Z0-9 _.=:+()/-]"), "_").take(120)
    private fun stamp(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    val sections: List<String> = listOf(
        "Overview", "Homepage", "Search", "Metadata", "HTTP / Network",
        "Links / Extractor", "Player", "Other", "Full timeline"
    )

    private fun sectionOf(stage: String): String {
        val upper = stage.uppercase(Locale.US)
        return when {
            upper.startsWith("HOME") -> "Homepage"
            upper.contains("SEARCH") -> "Search"
            upper.contains("META") || upper.contains("DETAIL") -> "Metadata"
            upper.contains("HTTP") || upper.contains("NETWORK") -> "HTTP / Network"
            upper.contains("LINK") || upper.contains("EXTRACT") || upper.contains("SUBTITLE") -> "Links / Extractor"
            upper.contains("PLAYER") || upper.contains("PLAYBACK") || upper.contains("FIRST_FRAME") || upper.contains("BUFFER") -> "Player"
            else -> "Other"
        }
    }

    private fun record(op: Long, level: String, stage: String, info: String) = synchronized(lock) {
        if (entries.size >= MAX_ENTRIES) { entries.removeFirst(); dropped++ }
        // STACK lines inherit the operation's stage so a complete exception stays in one section.
        val owner = if (stage == "STACK") {
            pending[op]?.stage ?: entries.lastOrNull { it.op == op && it.stage != "STACK" }?.stage
        } else {
            null
        }
        entries.addLast(Entry(stamp(), op, level, stage, info, sectionOf(owner ?: stage)))
    }

    fun begin(stage: String, provider: String, details: String = ""): Long = synchronized(lock) {
        val id = ++counter
        pending[id] = Pending(clean(provider), clean(stage), SystemClock.elapsedRealtime())
        val actor = if (stage == "HTTP") "host" else "provider"
        record(id, "START", clean(stage), "$actor=${clean(provider)} ${clean(details)}")
        id
    }

    fun note(op: Long, stage: String, details: String) {
        record(op, "INFO", clean(stage), clean(details))
    }

    fun finish(op: Long, details: String = "") = synchronized(lock) {
        val item = pending.remove(op) ?: return@synchronized
        val elapsed = SystemClock.elapsedRealtime() - item.since
        val actor = if (item.stage == "HTTP") "host" else "provider"
        record(op, "PASS", item.stage, "$actor=${item.provider} elapsed=${elapsed}ms ${clean(details)}")
        if (elapsed >= 5000) record(op, "SLOW", item.stage, "elapsed=${elapsed}ms")
    }

    fun failure(op: Long, type: String, details: String = "") = synchronized(lock) {
        val item = pending.remove(op)
        val elapsed = item?.let { SystemClock.elapsedRealtime() - it.since } ?: 0L
        val actor = if (item?.stage == "HTTP") "host" else "provider"
        record(op, "FAIL", item?.stage ?: "REQUEST", "$actor=${item?.provider ?: "unknown"} type=${clean(type)} elapsed=${elapsed}ms ${clean(details)}")
    }

    /** Avoid Throwable.message: it may contain a credential-bearing URL. */
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

    /** A subsection never guesses that an unrelated HTTP request belongs to a given provider. */
    fun report(section: String, importantOnly: Boolean): String = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        val matchingEntries = entries.filter { section == "Overview" || section == "Full timeline" || it.section == section }
        val matchingPending = pending.filterValues { section == "Overview" || section == "Full timeline" || sectionOf(it.stage) == section }
        buildString {
            appendLine("CLOUDSTREAM PROVIDER DIAGNOSTIC — ${if (importantOnly) "IMPORTANT" else "FULL TRACE"}")
            appendLine("Section: $section | events: ${matchingEntries.size} | active: ${matchingPending.size} | discarded: $dropped")
            appendLine("Provider HTTP events are shared-client requests; host is NOT the provider name.")
            appendLine("Extension-owned HTTP clients or steps without instrumentation are not visible.")
            appendLine("No URL paths, queries, headers, cookies, tokens or raw exception messages stored.")
            if (matchingPending.isNotEmpty()) {
                appendLine()
                appendLine("IN PROGRESS")
                matchingPending.forEach { (id, task) ->
                    val elapsed = now - task.since
                    val actor = if (task.stage == "HTTP") "host" else "provider"
                    appendLine("#$id ${task.stage} $actor=${task.provider} waiting=${elapsed}ms${if (elapsed >= 5000) " [SLOW]" else ""}")
                }
            }
            if (importantOnly) {
                appendLine()
                appendLine("FAILURES / SLOW STAGES")
                val important = matchingEntries.filter { it.level == "FAIL" || it.level == "SLOW" }
                if (important.isEmpty()) appendLine("No failed or slow stages recorded in this section.")
                important.takeLast(80).forEach { e -> appendLine("${e.at} #${e.op} ${e.level} ${e.stage} ${e.info}") }
                appendLine("Open Full trace for the events preceding a failure and its stack trace.")
            } else {
                val categories = if (section == "Overview") sections.subList(1, 8) else listOf(section)
                categories.forEach { category ->
                    val current = if (section == "Full timeline") matchingEntries else matchingEntries.filter { it.section == category }
                    if (current.isNotEmpty() || section != "Overview") {
                        appendLine()
                        appendLine("=== ${category.uppercase(Locale.US)} (${current.size}) ===")
                        if (current.isEmpty()) appendLine("No events recorded.")
                        current.forEach { e -> appendLine("${e.at} #${e.op} ${e.level} ${e.stage} ${e.info}") }
                    }
                }
                if (matchingEntries.isEmpty() && section == "Overview") appendLine("No events recorded yet.")
            }
        }
    }

    // Preserve compatibility with any existing callers.
    fun important(): String = report("Overview", true)
    fun full(): String = report("Full timeline", false)
}

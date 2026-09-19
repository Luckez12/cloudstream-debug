package com.lagradost.cloudstream3.utils.diagnostics

import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** In-memory diagnostic history; only structured, non-sensitive values may enter the log. */
object DiagnosticLog {
    private const val MAX_EVENTS = 600
    private const val MAX_SESSIONS_IN_REPORT = 15
    private data class Entry(
        val session: Long,
        val time: String,
        val elapsedMs: Long,
        val stage: String,
        val status: String,
        val detail: String,
    )
    private val lock = Any()
    private val events = ArrayDeque<Entry>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var sequence = 0L
    private var latestPlaybackSession = 0L
    private var latestSession = 0L

    private fun safeLabel(value: String): String = value
        .replace(Regex("[^a-zA-Z0-9 _.=:+-]"), "_")
        .take(80)

    private fun append(id: Long, stage: String, status: String, detail: String) {
        val entry = Entry(id, clock.format(Date()), SystemClock.elapsedRealtime(),
            safeLabel(stage), safeLabel(status), safeLabel(detail))
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(entry)
        // Mirror only sanitized structured events to Logcat. Never log raw extension text/URLs.
        Log.d("CloudStreamDiag", "#${entry.session} ${entry.stage} ${entry.status} ${entry.detail}")
    }

    fun start(provider: String): Long = synchronized(lock) {
        val id = ++sequence
        latestSession = id
        append(id, "PROVIDER", "START", "provider=${safeLabel(provider)}")
        id
    }

    fun startPlayback(provider: String): Long = synchronized(lock) {
        val id = ++sequence
        latestSession = id
        latestPlaybackSession = id
        append(id, "PLAYBACK", "START", "provider=${safeLabel(provider)}")
        id
    }

    fun currentPlaybackSession(): Long? = synchronized(lock) {
        latestPlaybackSession.takeIf { it != 0L }
    }

    /** Only fixed labels, known enum names, booleans and numbers are allowed as details. */
    fun event(stage: String, status: String, detail: String = "", session: Long? = null) {
        synchronized(lock) {
            val id = session ?: latestPlaybackSession.takeIf { it != 0L } ?: latestSession
            append(id, stage, status, detail)
        }
    }

    /** Exception messages and stack traces can contain stream tokens: keep types/codes only. */
    fun error(stage: String, cause: Throwable, session: Long? = null, code: Int? = null) {
        val type = safeLabel(cause.javaClass.simpleName.ifBlank { "Throwable" })
        val nested = cause.cause?.let { safeLabel(it.javaClass.simpleName) }
        event(stage, "FAIL", "type=$type" +
            (nested?.let { " cause=$it" } ?: "") +
            (code?.let { " code=$it" } ?: ""), session)
    }

    fun clear() = synchronized(lock) {
        events.clear()
        latestPlaybackSession = 0L
        latestSession = 0L
    }

    private fun lastPlaybackEntries(): Pair<Long?, List<Entry>> {
        val id = latestPlaybackSession.takeIf { it != 0L }
        return id to (if (id == null) emptyList() else events.filter { it.session == id })
    }

    /** Short, actionable view of the CURRENT attempt, not errors from older sessions. */
    fun summary(): String = synchronized(lock) {
        val (id, sessionEntries) = lastPlaybackEntries()
        buildString {
            appendLine("CLOUDSTREAM DIAGNOSTIC v3 - IMPORTANT")
            appendLine("====================================")
            appendLine("Playback session: ${id ?: "none"}")
            if (sessionEntries.isEmpty()) {
                appendLine("No playback events yet. Try playing a video first.")
            } else {
                val provider = sessionEntries.firstOrNull { it.stage == "PLAYBACK" && it.status == "START" }
                appendLine(provider?.detail ?: "provider=unknown")
                val failures = sessionEntries.filter { it.status == "FAIL" }
                val latestFailure = failures.lastOrNull()
                if (latestFailure != null) {
                    appendLine("LAST FAILURE: ${latestFailure.stage} ${latestFailure.detail}")
                    appendLine("Failure events: ${failures.size}; see Full log for their order.")
                } else appendLine("LAST FAILURE: None captured in this attempt.")
                val links = sessionEntries.lastOrNull { it.stage == "LINKS" && it.status != "START" }
                appendLine("LINKS: " + (links?.let { "${it.status} ${it.detail}" } ?: "not recorded / cached"))
                val cache = sessionEntries.lastOrNull { it.stage == "LINK_CACHE" }
                if (cache != null) appendLine("CACHE: ${cache.detail}")
                val frame = sessionEntries.lastOrNull { it.stage == "FIRST_FRAME" }
                appendLine("FIRST FRAME: " + (frame?.let { "${it.status} ${it.detail}" } ?: "not observed"))
                val latestState = sessionEntries.lastOrNull { it.stage == "PLAYER_STATE" }
                if (latestState != null) {
                    val pending = if (latestState.detail == "buffering") {
                        " for ${(SystemClock.elapsedRealtime() - latestState.elapsedMs) / 1000}s"
                    } else ""
                    appendLine("PLAYER STATE: ${latestState.detail}$pending")
                } else appendLine("PLAYER STATE: not recorded")
                val http = sessionEntries.lastOrNull { it.stage == "HTTP" && it.status == "FAIL" }
                if (http != null) appendLine("HTTP: ${http.detail}")
                val area = sessionEntries.lastOrNull { it.stage == "FAILURE_AREA" }
                if (area != null) appendLine("POSSIBLE AREA: ${area.detail}")
                if (latestFailure == null && frame == null) appendLine("NOTE: player ready does NOT confirm video rendered.")
            }
            appendLine()
            appendLine("OTHER RECENT SESSIONS: ${events.map { it.session }.distinct().count { it != id }}")
            appendLine("Full log: all recorded stages, status changes, timings and errors.")
            appendLine("Only app-instrumented events; extension internals/HTTP traffic are not captured.")
            appendLine("URLs, headers, cookies, tokens, titles and raw exception messages omitted.")
        }
    }

    /** Entire bounded in-memory history, chronological and separated by request session. */
    fun fullReport(): String = synchronized(lock) {
        val recent = events.toList()
        buildString {
            appendLine("CLOUDSTREAM DIAGNOSTIC v3 - FULL LOG")
            appendLine("====================================")
            appendLine("Events retained: ${recent.size}/$MAX_EVENTS | in-memory, oldest discarded first")
            appendLine("Latest playback session: ${latestPlaybackSession.takeIf { it != 0L } ?: "none"}")
            appendLine("Chronological events by session; concurrent requests may overlap.")
            appendLine("Only instrumented stages; not system Logcat or extension internal HTTP logs.")
            appendLine("URLs, headers, cookies, tokens, titles and raw exception messages omitted.")
            if (recent.isEmpty()) appendLine("\nNo recorded events yet.")
            val ids = recent.map { it.session }.distinct().takeLast(MAX_SESSIONS_IN_REPORT)
            for (id in ids) {
                appendLine()
                appendLine("SESSION #$id" + if (id == latestPlaybackSession) " [LATEST PLAYBACK]" else "")
                for (entry in recent.filter { it.session == id }) {
                    appendLine("${entry.time} ${entry.stage} ${entry.status}" +
                        if (entry.detail.isBlank()) "" else " ${entry.detail}")
                }
            }
            val omittedSessions = recent.map { it.session }.distinct().size - ids.size
            if (omittedSessions > 0) appendLine("\n$omittedSessions older sessions omitted; clear log to capture a fresh attempt.")
        }
    }

    // Backward-compatible API for any existing caller.
    fun report(): String = summary()
}

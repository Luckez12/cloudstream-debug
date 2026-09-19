package com.lagradost.cloudstream3.utils.diagnostics

import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Best-effort capture of THIS APP PROCESS's Logcat output, not unrestricted system Logcat.
 * ADB/READ_LOGS is deliberately NOT requested. A device can still refuse this access.
 * Scrub before retaining; no unsanitized logcat line is saved to the ring buffer.
 */
object FilteredLogcat {
    private const val MAX_LINES = 1500
    private const val MAX_LINE_LENGTH = 700
    private val lock = Any()
    private val lines = ArrayDeque<Record>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var started = false
    private var status = "Not started"
    private var discarded = 0L

    private data class Record(
        val atMs: Long,
        val time: String,
        val level: Char,
        val tag: String,
        val detail: String,
    ) {
        fun render(): String = "$time $level/$tag: $detail"
    }

    private val logcatLine = Regex(
        """^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEAF])\s+([^:]+):\s?(.*)$"""
    )
    private val noisyTags = Regex(
        """(?i)^(?:Choreographer|ViewRootImpl|InputTransport|InsetsController|hwui|OpenGLRenderer|BufferQueue.*)$"""
    )
    private val related = Regex(
        """(?i)cloudstream|cs3|moviebox|plugin|provider|extract|okhttp|http|network|cloudflare|player|media3|exoplayer|mediaCodec|chromium|system\.err|androidruntime|subtitle|source|stream|hls|dash|ssl|dns|socket|ktor|jsoup|webview"""
    )
    private val noteworthyMessage = Regex(
        """(?i)\b(?:request|response|http|extract|provider|timeout|exception|error|failed|failure|episode|loadlinks|stream|manifest|playback|buffering|403|404|429|500|503|redirect|handshake)\b"""
    )

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            status = "Starting app-process collector"
        }
        thread(name = "CloudStream-filtered-logcat", isDaemon = true) {
            var command: java.lang.Process? = null
            try {
                // --pid restricts capture to CloudStream's own process; never capture other apps.
                val running = ProcessBuilder("logcat", "--pid=${Process.myPid()}", "-v", "threadtime", "*:V")
                    .redirectErrorStream(true).start()
                command = running
                synchronized(lock) { status = "Listening to app-process Logcat (best effort)" }
                BufferedReader(InputStreamReader(running.inputStream)).use { reader ->
                    while (true) {
                        val raw = reader.readLine() ?: break
                        val match = logcatLine.matchEntire(raw) ?: continue
                        val level = match.groupValues[1].first()
                        val tag = match.groupValues[2].trim().take(64)
                        if (tag == "CloudStreamDiag" || noisyTags.matches(tag)) continue
                        val message = match.groupValues[3]
                        val relevant = related.containsMatchIn(tag) || noteworthyMessage.containsMatchIn(message)
                        // Keep all warnings/errors from THIS app's process; only related info/debug.
                        if (level !in "WEAF" && !relevant) continue
                        val sanitized = scrub(message).take(MAX_LINE_LENGTH)
                        if (sanitized.isBlank()) continue
                        val entry = Record(SystemClock.elapsedRealtime(), clock.format(Date()), level,
                            tag.replace(Regex("[^a-zA-Z0-9_. /$-]"), "_").take(64), sanitized)
                        synchronized(lock) {
                            if (lines.size == MAX_LINES) {
                                lines.removeFirst()
                                discarded++
                            }
                            lines.addLast(entry)
                        }
                    }
                }
                val exit = running.waitFor()
                synchronized(lock) { status = "Collector stopped (exit $exit; device may restrict Logcat)" }
            } catch (e: Exception) {
                synchronized(lock) { status = "Collector unavailable: ${e.javaClass.simpleName}" }
            } finally {
                command?.destroy()
            }
        }
    }

    /**
     * Remove credential headers, known key/value secrets, URLs' query/fragment/userinfo,
     * and bearer/JWT values. Unusual extension log formats may still leak secrets:
     * review a report before sharing it publicly.
     */
    private fun scrub(input: String): String {
        var s = input.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        s = Regex("""(?i)\bhttps?://[^\s\"'<>]+""").replace(s) { found ->
            val raw = found.value.trimEnd('.', ',', ')', ';')
            val suffix = found.value.substring(raw.length)
            val parsed = try { URI(raw) } catch (_: Exception) { null }
            val host = parsed?.host?.take(120)?.replace(Regex("[^a-zA-Z0-9.:-]"), "_")
            if (host.isNullOrBlank()) "<url redacted>$suffix"
            else {
                val path = parsed?.rawPath.orEmpty().split('/').joinToString("/") { part ->
                    if (part.length > 24 || part.contains('%') || part.contains('@')) "<id>" else part
                }.take(130)
                "${parsed?.scheme}://$host$path" +
                    (if (parsed?.rawQuery != null || parsed?.rawFragment != null) "?<redacted>" else "") + suffix
            }
        }
        s = Regex("""(?i)\b(?:set-cookie|cookie|proxy-authorization|authorization|x-api-key)\b\s*[:=].*$""")
            .replace(s) { m -> m.value.substringBefore(':').substringBefore('=') + ": <redacted>" }
        s = Regex("""(?i)\b(?:Bearer|Basic)\s+[^\s,;]+""").replace(s, "<auth redacted>")
        s = Regex("""(?i)(\b(?:access[_-]?token|refresh[_-]?token|api[_-]?key|session[_-]?id|token|secret|password|signature|sig|jwt)\b[\"']?\s*[:=]\s*)(?:\"[^\"]*\"|'[^']*'|[^\s,;}&]+)""")
            .replace(s) { m -> m.groupValues[1] + "<redacted>" }
        s = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
            .replace(s, "<jwt redacted>")
        return s
    }

    /** Instrument shared OkHttp without recording URLs, credentials, bodies or request headers. */
    fun network(level: Char, detail: String) {
        synchronized(lock) {
            if (lines.size == MAX_LINES) {
                lines.removeFirst()
                discarded++
            }
            lines.addLast(Record(SystemClock.elapsedRealtime(), clock.format(Date()), level,
                "HTTP", scrub(detail).take(MAX_LINE_LENGTH)))
        }
    }

    fun clear() = synchronized(lock) {
        lines.clear()
        discarded = 0L
    }

    fun importantReport(startMs: Long?): String = synchronized(lock) {
        val recent = lines.filter { startMs == null || it.atMs >= startMs }
        val problems = recent.withIndex().filter { it.value.level in "WEAF" }
        buildString {
            appendLine("FILTERED APP LOGCAT — IMPORTANT")
            appendLine("Collector: $status")
            appendLine("Relevant warnings/errors: ${problems.size}")
            if (problems.isEmpty()) appendLine("No Logcat warning/error captured in selected period.")
            // Include the line immediately preceding a problem to keep some context.
            val indexes = problems.takeLast(25).flatMap { listOf(it.index - 1, it.index) }
                .filter { it >= 0 }.distinct()
            indexes.forEach { appendLine(recent[it].render()) }
            if (problems.size > 25) appendLine("${problems.size - 25} older warnings/errors omitted here; see Full log.")
        }
    }

    fun fullReport(): String = synchronized(lock) {
        buildString {
            appendLine("FILTERED APP-PROCESS LOGCAT — FULL")
            appendLine("Collector: $status | retained=${lines.size}/$MAX_LINES | discarded=$discarded")
            appendLine("Process: ${Process.myPid()} | relevant D/I/V + all W/E/F (noise tags excluded)")
            appendLine("Not the system Logcat. Extension steps appear only if they log in this process.")
            appendLine("Credential redaction is best effort; CHECK before sharing any report.")
            if (lines.isEmpty()) appendLine("No matching app-process Logcat lines captured.")
            lines.forEach { appendLine(it.render()) }
        }
    }
}

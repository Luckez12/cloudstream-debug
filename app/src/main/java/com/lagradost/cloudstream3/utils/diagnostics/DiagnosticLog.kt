package com.lagradost.cloudstream3.utils.diagnostics

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Diagnostic v2: bounded, in-memory reports grouped by request/playback attempt.
 * NEVER pass raw server text, response bodies, titles, URLs, headers or throwable.message
 * to event(). Only fixed labels, numeric status/error codes and enum names belong here.
 */
object DiagnosticLog {
    private const val MAX_ATTEMPTS = 12
    private const val MAX_EVENTS_PER_ATTEMPT = 36
    private const val MAX_LINK_IDENTIFIERS = 120
    private val lock = Any()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private data class Attempt(
        val id: Long,
        val provider: String,
        val kind: String,
        val events: ArrayDeque<String> = ArrayDeque(),
        var played: Boolean = false,
        var lastFailure: String? = null,
        var hint: String? = null,
    )

    private val attempts = ArrayDeque<Attempt>()
    // Only salted hashes are retained; source URLs never appear in diagnostic history.
    private val linkToAttempt = LinkedHashMap<String, Long>()
    private var sequence = 0L

    private fun safe(value: String, max: Int = 135): String =
        value.replace(Regex("[^a-zA-Z0-9 _.=:+-]"), "_").take(max)

    private fun add(attempt: Attempt, stage: String, status: String, detail: String = "") {
        val content = "${clock.format(Date())} ${safe(stage, 28)} ${safe(status, 10)} ${safe(detail)}".trimEnd()
        if (attempt.events.size >= MAX_EVENTS_PER_ATTEMPT) attempt.events.removeFirst()
        attempt.events.addLast(content)
        Log.d("CloudStreamDiag", "#${attempt.id} $content")
    }

    fun start(provider: String, kind: String = "metadata"): Long = synchronized(lock) {
        val attempt = Attempt(++sequence, safe(provider, 48), safe(kind, 16))
        if (attempts.size >= MAX_ATTEMPTS) {
            val evicted = attempts.removeFirst()
            linkToAttempt.entries.removeAll { it.value == evicted.id }
        }
        attempts.addLast(attempt)
        add(attempt, "REQUEST", "START", "kind=${attempt.kind}")
        attempt.id
    }

    fun event(stage: String, status: String, detail: String = "", session: Long? = null) {
        synchronized(lock) {
            val attempt = if (session != null) attempts.firstOrNull { it.id == session }
                else attempts.lastOrNull()
            // A callback completing after Clear/eviction must not start a ghost session.
            if (attempt == null) return
            add(attempt, stage, status, detail)
            if (status == "FAIL") {
                attempt.lastFailure = safe("$stage $detail", 125)
                attempt.hint = when {
                    stage == "HTTP" && detail.contains("status=401") ->
                        "Server returned HTTP 401. Check authorization or required headers."
                    stage == "HTTP" && detail.contains("status=403") ->
                        "Server returned HTTP 403. Check access, URL expiry or required headers."
                    stage == "HTTP" && detail.contains("status=404") ->
                        "Server returned HTTP 404. The requested media may be unavailable."
                    stage == "HTTP" && detail.contains("status=429") ->
                        "Server returned HTTP 429. Request rate may be limited."
                    stage == "HTTP" -> "Media server returned an HTTP error; check the status code."
                    stage == "LINKS" || stage == "LINK_FILTER" ->
                        "No usable links / provider link request failed. Provider internals are not captured."
                    stage == "PLAYBACK" || stage == "PLAYER_SETUP" ->
                        "Player failed after source selection. Check error code and sanitized trace."
                    else -> attempt.hint
                }
            }
        }
    }

    /** Only safe Java/Kotlin frame identifiers and line numbers; never exception messages. */
    fun error(stage: String, cause: Throwable, session: Long? = null, code: Int? = null) {
        val root = generateSequence(cause) { it.cause?.takeIf { nested -> nested !== it } }
            .take(4).last()
        event(
            stage, "FAIL",
            "type=${safe(cause.javaClass.simpleName, 50)} root=${safe(root.javaClass.simpleName, 50)}" +
                (code?.let { " code=$it" } ?: ""), session
        )
        // Framework exceptions often have no useful app frames: include a few sanitized frames.
        val frames = (cause.stackTrace.asSequence() + root.stackTrace.asSequence())
            .filter {
                it.className.startsWith("com.lagradost.cloudstream") ||
                    it.className.startsWith("androidx.media3")
            }.take(4).toList()
        frames.forEach { frame ->
            event("TRACE", "INFO", "at=${safe(frame.className, 90)}.${safe(frame.methodName, 35)}:${frame.lineNumber}", session)
        }
    }

    private fun linkIdentifier(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.encodeToString(digest.digest(url.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Call from the link callback, including when a cached link is reused. */
    fun rememberLink(url: String, session: Long) {
        if (url.isBlank()) return
        synchronized(lock) {
            if (attempts.none { it.id == session }) return
            val key = linkIdentifier(url)
            linkToAttempt.remove(key)
            linkToAttempt[key] = session
            while (linkToAttempt.size > MAX_LINK_IDENTIFIERS) {
                linkToAttempt.remove(linkToAttempt.keys.first())
            }
        }
    }

    /** Tie player callbacks to the exact provider attempt that produced its link. */
    fun playerStarted(url: String?, format: String): Long {
        val matched = synchronized(lock) {
            url?.let { linkToAttempt[linkIdentifier(it)] }
                ?.takeIf { id -> attempts.any { it.id == id } }
        }
        val session = matched ?: start(if (url == null) "Offline" else "Unlinked source", "playback")
        synchronized(lock) { attempts.firstOrNull { it.id == session }?.played = true }
        event("PLAYER", "START", "format=${safe(format, 24)}", session)
        return session
    }

    fun clear() = synchronized(lock) {
        attempts.clear()
        linkToAttempt.clear()
        // sequence deliberately not reset: late callbacks cannot reuse cleared IDs.
    }

    fun report(): String = synchronized(lock) {
        val lastPlayer = attempts.lastOrNull { it.played }
        val lastFailedLinks = attempts.lastOrNull { it.kind == "links" && it.lastFailure != null }
        val selected = if (lastFailedLinks != null &&
            (lastPlayer == null || lastFailedLinks.id > lastPlayer.id)
        ) lastFailedLinks else lastPlayer ?: attempts.lastOrNull { it.kind == "links" }
            ?: attempts.lastOrNull()
        buildString {
            appendLine("CLOUDSTREAM DIAGNOSTIC v2")
            appendLine("=========================")
            appendLine("In-memory report | latest failed link request or playback attempt")
            appendLine("No URLs, titles, headers, cookies, tokens or error messages collected.")
            appendLine()
            if (selected == null) {
                appendLine("No events. Open a title, select a source and try Play.")
            } else {
                appendLine("SESSION #${selected.id} | provider=${selected.provider} | ${selected.kind}")
                appendLine("-----------------------------------------")
                selected.events.forEach { appendLine(it) }
                appendLine()
                appendLine("LAST FAILURE: ${selected.lastFailure ?: "None captured"}")
                appendLine("POSSIBLE AREA: ${selected.hint ?: "No failure recorded in this attempt."}")
                appendLine()
                val other = attempts.filter { it.id != selected.id }.takeLast(3)
                if (other.isNotEmpty()) {
                    appendLine("OTHER RECENT REQUESTS (not merged with playback)")
                    other.forEach { attempt ->
                        appendLine("#${attempt.id} ${attempt.provider} ${attempt.kind} | ${attempt.lastFailure ?: "no failure"}")
                    }
                    appendLine()
                }
                appendLine("LIMITATION: internal extension/extractor HTTP steps are not visible unless the extension logs them.")
            }
        }
    }
}

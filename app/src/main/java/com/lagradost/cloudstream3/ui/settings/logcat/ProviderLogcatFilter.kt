package com.lagradost.cloudstream3.ui.settings.logcat

/**
 * UI-only filter: original Logcat is kept untouched and can always be viewed/exported.
 * Keep all messages from the app process except a small, explicit list of known OS noise.
 * Provider/extension tags need not be pre-registered to appear here.
 */
object ProviderLogcatFilter {
    fun keep(pid: Int, appPid: Int, tag: String, message: String): Boolean {
        if (pid != appPid) return false
        if (tag == "WindowOnBackDispatcher" && message.contains("sendCancelIfRunning")) return false
        if (tag.equals("binder", ignoreCase = true) &&
            message.contains("avc: denied") && message.contains("/proc/perfmgr/perf_ioctl")) return false
        return true
    }

    // With 'logcat -v threadtime', a normal line begins with date/time/pid/tid/level/tag.
    // Keep continuation lines of selected entries (e.g. Java stack traces).
    private val threadtime = Regex("""^\s*\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[VDIWEFAS]\s+([^: ]+)\s*:\s*(.*)$""")

    fun filterText(lines: List<String>, appPid: Int): List<String> {
        val result = ArrayList<String>()
        var keepContinuation = false
        lines.forEach { line ->
            val match = threadtime.matchEntire(line)
            if (match != null) {
                keepContinuation = keep(
                    match.groupValues[1].toIntOrNull() ?: -1,
                    appPid,
                    match.groupValues[2],
                    match.groupValues[3]
                )
                if (keepContinuation) result.add(line)
            } else if (keepContinuation && (line.startsWith(" ") || line.startsWith("\t"))) {
                result.add(line)
            } else {
                keepContinuation = false
            }
        }
        return result
    }

    // Best effort: never promise that arbitrary extension logs are credential-free.
    private val secretHeaders = Regex("""(?im)\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key|X-Auth-Token)\s*[:=]\s*[^\r\n]+""")
    private val secretQueries = Regex("""(?i)([?&](?:token|access_token|refresh_token|api_key|apikey|signature|sig|auth|session|jwt)=)[^&\s#]+""")

    fun forSharing(text: String): String = secretQueries.replace(
        secretHeaders.replace(text) { match -> "${match.groupValues[1]}: [REDACTED]" }
    ) { match -> "${match.groupValues[1]}[REDACTED]" }
}

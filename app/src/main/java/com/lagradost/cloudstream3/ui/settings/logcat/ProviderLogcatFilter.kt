package com.lagradost.cloudstream3.ui.settings.logcat

/** Raw mode is unchanged. Filtered mode hides known UI/OS noise without requiring provider tags. */
object ProviderLogcatFilter {
    private val relevant = Regex(
        """(?i)(exception|stack\s*trace|caused\s+by|crash|fatal|\bhttp\b|\b[45]\d\d\b|ssl|tls|timeout|timed\s+out|unknownhost|connection\s+refused|failed\s+to\s+(?:load|connect|fetch|parse|extract|play|resolve)|loadlinks|extractor|provider|playback|mediacodec|exoplayer)"""
    )

    fun keep(pid: Int, appPid: Int, tag: String, message: String): Boolean {
        if (pid != appPid) return false
        val t = tag.lowercase()
        val m = message.lowercase()

        // Specific repetitive messages are harmless even if logged at W/E priority.
        if ("avc: denied" in m && ("/proc/perfmgr" in m || "perf_ioctl" in m)) return false
        if (t.startsWith("transcrolloptimizer")) return false
        if (t.startsWith("windowonbackdispatcher") &&
            ("sendcancelifrunning" in m || "onbackinvokedcallback" in m)) return false
        if (t == "resourcesmanager" && "found a null resourcesimpl" in m) return false
        if (t == "displaymanager" && ("refresh rate" in m || "refresh_rate" in m ||
            "choreographer implicitly" in m)) return false
        if (t == "activitythread" && "failed to find provider info for android.media.tv" in m) return false
        if (t.startsWith("vri[") && ("motionevent" in m || "enqueueinputevent" in m)) return false
        if ("motionevent" in m && ("enqueueinputevent" in m || "processinputevents" in m)) return false
        if (t == "imetracker" && ("onrequesthide" in m || "oncancelled" in m ||
            "onrequestshow" in m || "onshown" in m || "onhidden" in m)) return false
        if (t == "appvisibilityproxy" && ("onappenteredforeground" in m || "onappexitedforeground" in m)) return false
        if (t == "navcomponent" && "navigating to fragment" in m) return false

        // Preserve real failures (including stack traces) regardless of the logging tag.
        if (relevant.containsMatchIn(message)) return true

        // Drop ordinary framework/UI chatter, but do not suppress unknown extension tags.
        if (t.startsWith("windowonbackdispatcher") || t.startsWith("vri[") ||
            t.startsWith("viewrootimpl") || t.startsWith("choreographer") ||
            t.startsWith("inputmethodmanager") || t.startsWith("insetscontroller") ||
            t.startsWith("inputtransport") || t.startsWith("bufferqueue") ||
            t.startsWith("surfaceflinger") || t.startsWith("hwui") ||
            t == "imetracker" || t == "appvisibilityproxy" || t == "navcomponent" ||
            t == "displaymanager" || t == "resourcesmanager") return false

        return true
    }

    private val threadtime = Regex(
        """^\s*\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[VDIWEFAS]\s+(.+?)\s*:\s*(.*)$"""
    )
    private val brief = Regex("""^[VDIWEFAS]/(.+?)\s*\(\s*(\d+)\):\s*(.*)$""")

    fun filterText(lines: List<String>, appPid: Int): List<String> {
        val output = ArrayList<String>()
        var keepPrevious = false
        for (line in lines) {
            val full = threadtime.matchEntire(line)
            val short = if (full == null) brief.matchEntire(line) else null
            when {
                full != null -> {
                    keepPrevious = keep(
                        full.groupValues[1].toIntOrNull() ?: -1, appPid,
                        full.groupValues[2], full.groupValues[3]
                    )
                    if (keepPrevious) output.add(line)
                }
                short != null -> {
                    keepPrevious = keep(
                        short.groupValues[2].toIntOrNull() ?: -1, appPid,
                        short.groupValues[1], short.groupValues[3]
                    )
                    if (keepPrevious) output.add(line)
                }
                keepPrevious && (line.startsWith(" ") || line.startsWith("\t") ||
                    line.startsWith("Caused by:") || line.startsWith("Suppressed:")) -> output.add(line)
                else -> keepPrevious = false
            }
        }
        return output
    }

    /** Copy/Save protection is best effort. Review logs before sharing. */
    private val header = Regex("""(?im)\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key|X-Auth-Token)\s*[:=]\s*[^\r\n]+""")
    private val query = Regex("""(?i)([?&](?:token|access_token|refresh_token|api_key|apikey|signature|sig|auth|session|jwt)=)[^&\s#]+""")
    fun forSharing(text: String): String = query.replace(
        header.replace(text) { "${it.groupValues[1]}: [REDACTED]" }
    ) { "${it.groupValues[1]}[REDACTED]" }
}

package com.lagradost.cloudstream3.ui.settings.logcat

/** UI-only selection: Raw is never modified; Filtered removes known Android noise. */
object ProviderLogcatFilter {
    // Do not require a known provider tag: extensions may log under arbitrary tag names.
    // Only suppress specific known OS/UI noise from the app process.
    fun keep(pid: Int, appPid: Int, tag: String, message: String): Boolean {
        if (pid != appPid) return false
        val t = tag.lowercase()
        val m = message.lowercase()
        if (t.startsWith("windowonbackdispatcher") || t.startsWith("vri[") ||
            t.startsWith("viewrootimpl") || t.startsWith("choreographer") ||
            t.startsWith("inputmethodmanager") || t.startsWith("insetscontroller") ||
            t.startsWith("inputtransport") || t.startsWith("bufferqueue") ||
            t.startsWith("surfaceflinger") || t.startsWith("transcrolloptimizer") ||
            t.startsWith("hwui")) return false
        if (t.startsWith("binder") && "avc: denied" in m &&
            ("/proc/perfmgr" in m || "perf_ioctl" in m)) return false
        if ("avc: denied" in m && ("/proc/perfmgr" in m || "perf_ioctl" in m)) return false
        if ("motionevent" in m && ("enqueueinputevent" in m || "processinputevents" in m)) return false
        if (t == "displaymanager" && "refresh_rate" in m) return false
        if (t == "activitythread" && "android.media.tv" in m) return false
        if (t == "view" && "enqueueinputevent" in m) return false
        if (t == "inputdispatcher" && "motionevent" in m) return false
        return true
    }

    private val threadtime = Regex(
        """^\s*\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+[VDIWEFAS]\s+(.+?)\s*:\s*(.*)$"""
    )
    // Logcat can also emit brief-form lines on some devices.
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
                // Keep attached exception/stack-trace lines for the selected record.
                keepPrevious && (line.startsWith(" ") || line.startsWith("\t") ||
                    line.startsWith("Caused by:") || line.startsWith("Suppressed:")) -> output.add(line)
                else -> keepPrevious = false
            }
        }
        return output
    }

    /** For Copy/Save only. Unknown secret formats may slip through: review before sharing. */
    private val header = Regex("""(?im)\b(Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key|X-Auth-Token)\s*[:=]\s*[^\r\n]+""")
    private val query = Regex("""(?i)([?&](?:token|access_token|refresh_token|api_key|apikey|signature|sig|auth|session|jwt)=)[^&\s#]+""")
    fun forSharing(text: String): String = query.replace(
        header.replace(text) { "${it.groupValues[1]}: [REDACTED]" }
    ) { "${it.groupValues[1]}[REDACTED]" }
}

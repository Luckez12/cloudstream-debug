package com.lagradost.cloudstream3.utils.diagnostics

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.Response

/** Observe requests made via CloudStream's shared OkHttp client; never read response bodies. */
class DiagnosticHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = SystemClock.elapsedRealtime()
        // Host only. Paths, query strings, headers and bodies may contain credentials.
        val host = request.url.host.replace(Regex("[^a-zA-Z0-9.:-]"), "_").take(120)
        val method = request.method.take(12)
        try {
            val response = chain.proceed(request)
            val elapsed = SystemClock.elapsedRealtime() - start
            val code = response.code
            val redirected = if (response.priorResponse != null) " redirected=true" else ""
            FilteredLogcat.network(if (code >= 400) 'E' else 'I',
                "method=$method host=$host status=$code elapsed=${elapsed}ms$redirected")
            return response
        } catch (e: Exception) {
            val elapsed = SystemClock.elapsedRealtime() - start
            FilteredLogcat.network('E',
                "method=$method host=$host elapsed=${elapsed}ms exception=${e.javaClass.simpleName}")
            throw e
        }
    }
}

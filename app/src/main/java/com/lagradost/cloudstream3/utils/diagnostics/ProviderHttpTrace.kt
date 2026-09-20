package com.lagradost.cloudstream3.utils.diagnostics

import okhttp3.Interceptor
import okhttp3.Response

/** Only shared OkHttp client. Do not log URL path/query, headers, body or exception messages. */
class ProviderHttpTrace : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val id = ProviderTrace.begin("HTTP", host, "method=${request.method}")
        return try {
            val response = chain.proceed(request)
            val redirect = if (response.priorResponse != null) "redirect=yes" else "redirect=no"
            if (response.code >= 400) ProviderTrace.failure(id, "HTTP_${response.code}", redirect)
            else ProviderTrace.finish(id, "status=${response.code} $redirect")
            response
        } catch (t: Exception) {
            ProviderTrace.exception(id, t)
            throw t
        }
    }
}

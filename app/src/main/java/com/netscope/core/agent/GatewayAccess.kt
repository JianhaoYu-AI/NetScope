package com.netscope.core.agent

import com.netscope.BuildConfig
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Runtime credentials are memory-only; never serialized, logged, exported or put in BuildConfig. */
class GatewayConnection(val endpoint: String, val accessCode: String) {
    override fun toString(): String = "GatewayConnection(credentials=hidden)"
}

@Singleton
class GatewayAccess @Inject constructor() {
    @Volatile private var connection = GatewayConnection(BuildConfig.NETSCOPE_BACKEND_URL, "")

    fun snapshot(): GatewayConnection = connection

    fun configure(endpoint: String, accessCode: String): Boolean {
        val url = endpoint.trim()
        val code = accessCode.trim()
        if (!validGatewayEndpoint(url) || (code.isNotEmpty() && !Regex("[A-Za-z0-9_-]{32,128}").matches(code))) return false
        val uri = URI(url)
        if (uri.scheme == "https" && code.isEmpty()) return false
        connection = GatewayConnection(url, code)
        return true
    }
}

internal fun validGatewayEndpoint(endpoint: String): Boolean {
    val uri = try { URI(endpoint) } catch (_: Exception) { return false }
    val schemeAllowed = uri.scheme == "https" ||
        (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))
    return schemeAllowed && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.fragment == null && uri.rawQuery == null && uri.path == "/v1/chat/completions" &&
        (uri.port == -1 || uri.port in 1..65535)
}

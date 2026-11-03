package com.example.data.web

import com.example.data.network.MeteredNetworkNotAllowedException
import com.example.data.network.UserNetworkChangedException
import com.example.data.network.UserNetworkUnavailableException
import java.net.UnknownHostException
import java.util.concurrent.RejectedExecutionException

internal data class WebProxyFailure(val code: String, val status: String)

internal fun classifyWebProxyFailure(error: Exception): WebProxyFailure {
    val localCode = when (error) {
        is MeteredNetworkNotAllowedException -> "UNMETERED_REQUIRED"
        is UserNetworkChangedException -> "NETWORK_CHANGED"
        is UserNetworkUnavailableException -> "NO_NETWORK"
        is UnknownHostException -> "DNS_FAILED"
        is RejectedExecutionException -> "DNS_BUSY"
        else -> null
    }
    if (localCode != null) return WebProxyFailure(localCode, "INCONCLUSIVE")
    val code = error.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "TEST_FAILED"
    return WebProxyFailure(code, if (code.startsWith("WEBVIEW_")) "CLIENT_UNAVAILABLE" else "FAILED")
}

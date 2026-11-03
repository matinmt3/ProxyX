package com.example.data.network

import com.example.util.PublicNetworkAddressPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object SourceHttpPolicy {
    fun validate(url: HttpUrl) {
        if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.fragment != null || url.host == "localhost" ||
            url.host.endsWith(".local")) throw IOException("Unsafe source URL")
    }

    fun publicAddresses(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || addresses.any { !PublicNetworkAddressPolicy.isPublic(it) }) {
            throw UnknownHostException("Source must resolve only to public addresses")
        }
        return addresses
    }

    fun readBody(body: ResponseBody, maxBytes: Long): String {
        if (body.contentLength() > maxBytes) throw IOException("Source content is too large")
        val source = body.source()
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) throw IOException("Source content is too large")
        return body.string()
    }

    suspend fun read(call: Call, maxBytes: Long): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("HTTP error code: ${it.code}")
                        readBody(it.body ?: throw IOException("Empty response body"), maxBytes)
                    }
                }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
}

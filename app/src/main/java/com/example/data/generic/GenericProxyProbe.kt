package com.example.data.generic

import com.example.data.network.UserNetworkException
import com.example.data.network.UserNetworkSession
import com.example.data.network.SourceHttpPolicy
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.*
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GenericProbeResult(val status: String, val connectionMs: Long = 0,
    val requestMs: Long = 0, val errorCode: String = "", val network: String = "")

internal class GenericProtocolFailure(val code: String) : IOException(code)

internal object GenericProxyHandshake {
    fun socks(input: InputStream, output: OutputStream, protocol: GenericProtocol, host: String,
        port: Int, ipv4: ByteArray? = null) {
        require(port in 1..65535 && host.matches(Regex("[a-zA-Z0-9.-]{1,253}")))
        val portBytes = byteArrayOf((port shr 8).toByte(), port.toByte())
        if (protocol == GenericProtocol.SOCKS5) {
            output.write(byteArrayOf(5, 1, 0)); output.flush()
            val greeting = read(input, 2)
            if (greeting[0] != 5.toByte()) throw GenericProtocolFailure("HANDSHAKE_FAILED")
            if (greeting[1] != 0.toByte()) throw GenericProtocolFailure("AUTH_FAILED")
            val domain = host.toByteArray(Charsets.US_ASCII)
            output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()) + domain + portBytes); output.flush()
            val reply = read(input, 4)
            if (reply[0] != 5.toByte() || reply[1] != 0.toByte() || reply[2] != 0.toByte())
                throw GenericProtocolFailure("HANDSHAKE_FAILED")
            val length = when (reply[3].toInt() and 255) {
                1 -> 4; 4 -> 16; 3 -> (read(input, 1)[0].toInt() and 255).also {
                    if (it == 0) throw GenericProtocolFailure("HANDSHAKE_FAILED")
                }
                else -> throw GenericProtocolFailure("HANDSHAKE_FAILED")
            }
            read(input, length + 2)
        } else {
            require(protocol == GenericProtocol.SOCKS4 || protocol == GenericProtocol.SOCKS4A)
            val ip = if (protocol == GenericProtocol.SOCKS4A) byteArrayOf(0, 0, 0, 1)
                else requireNotNull(ipv4).also { require(it.size == 4) }
            val domain = if (protocol == GenericProtocol.SOCKS4A) host.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) else byteArrayOf()
            output.write(byteArrayOf(4, 1) + portBytes + ip + byteArrayOf(0) + domain); output.flush()
            val reply = read(input, 8)
            if (reply[0] != 0.toByte() || reply[1] != 90.toByte())
                throw GenericProtocolFailure(if (reply[1].toInt() in 92..93) "AUTH_FAILED" else "HANDSHAKE_FAILED")
        }
    }

    fun responseCode(input: InputStream): Int {
        val bytes = java.io.ByteArrayOutputStream()
        var tail = 0
        while (bytes.size() < 16_384) {
            val next = input.read()
            if (next < 0) throw GenericProtocolFailure("INVALID_RESPONSE")
            bytes.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0d0a0d0a) {
                val status = bytes.toString("US-ASCII").substringBefore("\r\n")
                return Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*)?").matchEntire(status)
                    ?.groupValues?.get(1)?.toInt() ?: throw GenericProtocolFailure("INVALID_RESPONSE")
            }
        }
        throw GenericProtocolFailure("HEADERS_TOO_LARGE")
    }

    private fun read(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count <= 0) throw GenericProtocolFailure("HANDSHAKE_FAILED")
            offset += count
        }
        return result
    }
}

/** Small CONNECT-only SOCKS negotiator: keeps every socket on the captured Android Network. */
internal class GenericProxyProbe {
    suspend fun test(node: GenericProxy, network: UserNetworkSession): GenericProbeResult = withContext(Dispatchers.IO) {
        var phase = "CONNECTION_FAILED"
        var connectionMs = 0L
        var requestMs = 0L
        try {
            val protocol = runCatching { GenericProtocol.valueOf(node.protocol) }.getOrNull()
                ?: return@withContext GenericProbeResult("UNSUPPORTED", errorCode = "UNKNOWN_PROTOCOL")
            withTimeoutOrNull(30_000) {
                val addresses = runInterruptible { SourceHttpPolicy.publicAddresses(network.resolve(node.host).toList()) }
                // SOCKS4 uses local destination DNS; SOCKS4a/5 and CONNECT delegate destination DNS.
                val ipv4 = if (protocol == GenericProtocol.SOCKS4) runInterruptible {
                    SourceHttpPolicy.publicAddresses(network.resolve(TLS_HOST).toList())
                        .filterIsInstance<Inet4Address>().firstOrNull()?.address
                        ?: throw UnknownHostException("No IPv4 destination")
                } else null
                suspend fun request(tls: Boolean) = suspendCancellableCoroutine<Unit> { continuation ->
                    val socket = try { network.createSocket() } catch (e: Exception) {
                        continuation.resumeWithException(e); return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { runCatching { socket.close() } }
                    try {
                        socket.use {
                            phase = "CONNECTION_FAILED"
                            val connectStarted = System.nanoTime()
                            socket.soTimeout = 6000
                            socket.connect(InetSocketAddress(addresses.first(), node.port), 6000)
                            if (tls) connectionMs = elapsed(connectStarted)
                            phase = "HANDSHAKE_FAILED"
                            val host = if (tls) TLS_HOST else HTTP_HOST
                            val port = if (tls) 443 else 80
                            val output = socket.getOutputStream()
                            val input = socket.getInputStream()
                            if (protocol == GenericProtocol.HTTP || protocol == GenericProtocol.HTTP_CONNECT) {
                                if (tls) {
                                    output.write("CONNECT $host:443 HTTP/1.1\r\nHost: $host:443\r\n\r\n".toByteArray(Charsets.US_ASCII)); output.flush()
                                    val code = GenericProxyHandshake.responseCode(input)
                                    if (code == 407) throw GenericProtocolFailure("AUTH_FAILED")
                                    if (code !in 200..299) throw GenericProtocolFailure("HANDSHAKE_FAILED")
                                }
                            } else GenericProxyHandshake.socks(input, output, protocol, host, port, ipv4)
                            val carrier = if (tls) {
                                phase = "TLS_FAILED"
                                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                                    .createSocket(socket, host, port, true).let { it as SSLSocket }.apply {
                                        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                                        soTimeout = 6000
                                        startHandshake()
                                    }
                            } else socket
                            carrier.use {
                                phase = "REQUEST_FAILED"
                                val requestStarted = System.nanoTime()
                                val path = if (tls) "/generate_204" else "http://$host/generate_204"
                                it.getOutputStream().apply {
                                    write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                                }
                                val code = GenericProxyHandshake.responseCode(it.getInputStream())
                                if (code == 407) throw GenericProtocolFailure("AUTH_FAILED")
                                if (code != 204) throw GenericProtocolFailure("REQUEST_FAILED")
                                if (tls) requestMs = elapsed(requestStarted)
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
                if (protocol == GenericProtocol.HTTP) request(false)
                request(true)
                network.ensureCurrent()
                GenericProbeResult("WORKING", connectionMs, requestMs, network = network.identity.fingerprint)
            } ?: GenericProbeResult("TIMEOUT", errorCode = "DEADLINE", network = network.identity.fingerprint)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val changed = runCatching { network.ensureCurrent() }.exceptionOrNull()
            val status = when {
                changed != null || e is UserNetworkException || e is UnknownHostException -> "INCONCLUSIVE"
                e is SocketTimeoutException -> "TIMEOUT"
                e is SSLException -> "TLS_FAILED"
                e is GenericProtocolFailure && e.code == "AUTH_FAILED" -> "AUTH_FAILED"
                else -> phase
            }
            GenericProbeResult(status, errorCode = if (e is GenericProtocolFailure) e.code else status,
                network = network.identity.fingerprint)
        }
    }

    private fun elapsed(started: Long) = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
    private companion object {
        const val TLS_HOST = "www.gstatic.com"
        const val HTTP_HOST = "connectivitycheck.gstatic.com"
    }
}

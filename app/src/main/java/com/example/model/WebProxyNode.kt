package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import android.net.Uri
import androidx.core.net.toUri
import java.net.IDN
import android.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Entity(tableName = "web_proxy_nodes")
data class WebProxyNode(
    @PrimaryKey val url: String,
    val address: String,
    val secret: String,
    val createdAt: Long,
    val testedAt: Long = 0,
    val status: String = "UNTESTED",
    val stage: Int = 0,
    val latencyMs: Long = 0,
    val networkFingerprint: String = "",
    val networkType: String = "",
    val downloadKbps: Int = 0,
    val uploadKbps: Int = 0,
    val transferBytes: Long = 0,
    val transferRounds: Int = 0,
    val source: String = "MANUAL"
) {
    val host: String get() = address.substringBefore('/')
    val path: String get() = address.substringAfter('/', "")
    val hasRealTransfer: Boolean get() = testedAt > 0 && networkFingerprint.isNotBlank() && status == "TRANSFER_VERIFIED" && stage == 4 &&
        transferRounds >= 3 && downloadKbps > 0 && uploadKbps > 0 && transferBytes > 0

    fun bridgeUrl(): String {
        val context = if (path.isEmpty()) "tdesktop-web-proxy-bridge-v1\n$host"
            else "tdesktop-web-proxy-bridge-v2\n$host\n$path"
        val key = secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val capability = encodeBase64(mac.doFinal(context.toByteArray()))
        return "https://$host/${if (path.isEmpty()) "" else "$path/"}?bridge=$capability"
    }

    fun asLocalMtProto(port: Int): MtProtoProxy = MtProtoProxy(
        url = url, server = "127.0.0.1", port = port, secret = secret,
        country = "Global", countryCode = "UN", isWorking = false,
        latency = 0, successRate = 0f, stability = 0f
    )

    companion object {
        fun parse(raw: String, now: Long = System.currentTimeMillis()): WebProxyNode? = runCatching {
            val uri = raw.trim().replace("&amp;", "&").toUri()
            val tg = uri.scheme.equals("tg", true) && uri.host.equals("webproxy", true) && uri.path.orEmpty().isEmpty()
            val web = uri.scheme.equals("https", true) && uri.host.equals("t.me", true) && uri.path == "/webproxy"
            require((tg || web) && uri.userInfo == null && uri.port == -1 && uri.fragment == null)
            val keys = uri.queryParameterNames
            require(keys.all { it in setOf("server", "host", "secret", "bot", "channel") })
            require(keys.count { it == "server" || it == "host" } == 1)
            require(keys.all { uri.getQueryParameters(it).size == 1 })
            val address = normalizeAddress(uri.getQueryParameter("server") ?: uri.getQueryParameter("host")) ?: error("Address")
            var bytes = decodeSecret(uri.getQueryParameter("secret") ?: error("Secret")) ?: error("Secret")
            val marked = bytes.size >= 17 && bytes[0] == 0x70.toByte()
            if (marked) bytes = bytes.copyOfRange(1, bytes.size)
            require(!address.contains('/') || marked)
            require(bytes.size == 16 || (bytes.size == 17 && bytes[0] == 0xdd.toByte()))
            val secret = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            val sharedSecret = if (address.contains('/')) encodeBase64(byteArrayOf(0x70) + bytes) else secret
            val url = Uri.Builder().scheme("tg").authority("webproxy")
                .appendQueryParameter("server", address).appendQueryParameter("secret", sharedSecret).build().toString()
            WebProxyNode(url, address, secret, now)
        }.getOrNull()

        fun parseList(raw: String): List<WebProxyNode> = Regex(
            "(?:tg://webproxy|https://t\\.me/webproxy)\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE
        ).findAll(raw).mapNotNull { parse(it.value.trimEnd(',', ')', ']')) }.distinctBy { it.url }.toList()

        internal fun normalizeAddress(value: String?): String? = runCatching {
            val input = value?.trim() ?: error("Address")
            require(input.length <= 382)
            val host = IDN.toASCII(input.substringBefore('/').trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(host.length in 3..253 && host.contains('.') && !host.matches(Regex("[0-9.]+")))
            require(!host.endsWith(".localhost") && !host.endsWith(".local"))
            require(host.split('.').all { it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-') })
            val path = input.substringAfter('/', "")
            require(!input.contains('/') || path.isNotEmpty())
            require(path.length <= 128)
            require(path.isEmpty() || path.split('/').all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")) })
            if (path.isEmpty()) host else "$host/$path"
        }.getOrNull()

        private fun decodeSecret(value: String): ByteArray? = runCatching {
            require(value.length in 22..48)
            if (value.matches(Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{34}"))) {
                value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                require(value.matches(Regex("[A-Za-z0-9_-]+")))
                val bytes = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
                require(encodeBase64(bytes) == value)
                bytes
            }
        }.getOrNull()

        private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(
            bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }
}

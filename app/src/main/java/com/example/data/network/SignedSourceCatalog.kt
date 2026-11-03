package com.example.data.network

import android.content.Context
import android.util.Base64
import android.util.AtomicFile
import com.example.BuildConfig
import com.example.util.PublicNetworkAddressPolicy
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.Reader
import java.net.InetAddress
import java.net.URI
import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

@JsonClass(generateAdapter = true)
internal data class SignedCatalogEnvelope(
    val payload: String,
    val signature: String
)

@JsonClass(generateAdapter = true)
internal data class SourceCatalogPayload(
    val schemaVersion: Int,
    val issuedAt: Long,
    val expiresAt: Long,
    val mode: String = MODE_MERGE,
    val sources: List<CatalogSource>
) {
    companion object {
        const val MODE_MERGE = "MERGE"
        const val MODE_REPLACE = "REPLACE"
    }
}

@JsonClass(generateAdapter = true)
internal data class CatalogSource(
    val name: String,
    val url: String,
    val description: String,
    val enabled: Boolean = true
)

internal data class VerifiedSourceCatalog(
    val sources: List<ProxySource>,
    val replaceBundled: Boolean,
    val issuedAt: Long,
    val expiresAt: Long
)

internal object SignedSourceCatalogVerifier {
    private val moshi = Moshi.Builder().build()
    private val envelopeAdapter = moshi.adapter(SignedCatalogEnvelope::class.java)
    private val payloadAdapter = moshi.adapter(SourceCatalogPayload::class.java)

    fun verify(
        envelopeJson: String,
        publicKeyBase64: String,
        now: Long = System.currentTimeMillis()
    ): VerifiedSourceCatalog {
        require(envelopeJson.length <= MAX_ENVELOPE_CHARS) { "Catalog envelope is too large" }
        val envelope = requireNotNull(envelopeAdapter.fromJson(envelopeJson)) {
            "Catalog envelope is empty"
        }
        val payloadBytes = decodeBase64(envelope.payload, "payload")
        require(payloadBytes.size <= MAX_PAYLOAD_BYTES) { "Catalog payload is too large" }
        val signatureBytes = decodeBase64(envelope.signature, "signature")
        val publicKeyBytes = decodeBase64(cleanPublicKey(publicKeyBase64), "public key")
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val expectedCurve = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val actualCurve = (publicKey as? ECPublicKey)?.params
        require(actualCurve != null && actualCurve.curve == expectedCurve.curve &&
            actualCurve.generator == expectedCurve.generator && actualCurve.order == expectedCurve.order &&
            actualCurve.cofactor == expectedCurve.cofactor) { "Catalog key must use P-256" }
        val validSignature = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payloadBytes)
            verify(signatureBytes)
        }
        require(validSignature) { "Catalog signature is invalid" }

        val payloadJson = payloadBytes.toString(Charsets.UTF_8)
        val payload = requireNotNull(payloadAdapter.fromJson(payloadJson)) {
            "Catalog payload is empty"
        }
        require(payload.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported catalog schema"
        }
        require(payload.issuedAt in 1..(now + MAX_CLOCK_SKEW_MS)) {
            "Catalog issue time is invalid"
        }
        require(payload.expiresAt > now) { "Catalog has expired" }
        require(payload.expiresAt > payload.issuedAt) { "Catalog expiry is invalid" }
        require(payload.expiresAt - payload.issuedAt <= MAX_VALIDITY_MS) {
            "Catalog validity window is too long"
        }
        val normalizedMode = payload.mode.uppercase(Locale.ROOT)
        require(normalizedMode == SourceCatalogPayload.MODE_MERGE ||
            normalizedMode == SourceCatalogPayload.MODE_REPLACE
        ) { "Catalog mode is invalid" }
        require(payload.sources.size in 1..MAX_CATALOG_SOURCES) {
            "Catalog source count is invalid"
        }

        val seenUrls = HashSet<String>()
        val seenNames = HashSet<String>()
        val sources = payload.sources.map { source ->
            val name = source.name.trim()
            val description = source.description.trim()
            val url = normalizeSourceUrl(source.url)
            require(name.length in 3..MAX_SOURCE_NAME_CHARS) { "Source name is invalid" }
            require(description.length <= MAX_SOURCE_DESCRIPTION_CHARS) {
                "Source description is too long"
            }
            require(seenNames.add(name.lowercase(Locale.ROOT))) {
                "Catalog contains duplicate source names"
            }
            require(seenUrls.add(url)) { "Catalog contains duplicate source URLs" }
            ProxySource(name, url, description, source.enabled)
        }
        return VerifiedSourceCatalog(
            sources = sources,
            replaceBundled = normalizedMode == SourceCatalogPayload.MODE_REPLACE,
            issuedAt = payload.issuedAt,
            expiresAt = payload.expiresAt
        )
    }

    private fun normalizeSourceUrl(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Source URL is invalid")
        require(uri.scheme.equals("https", ignoreCase = true)) { "Source URL must use HTTPS" }
        require(uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443)) {
            "Source URL contains unsupported components"
        }
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(host.isNotBlank() && host != "localhost" && !host.endsWith(".local")) {
            "Source host is invalid"
        }
        parseLiteralAddress(host)?.let { address ->
            require(PublicNetworkAddressPolicy.isPublic(address)) { "Source host is not public" }
        }
        return uri.normalize().toASCIIString()
    }

    private fun parseLiteralAddress(host: String): InetAddress? = when {
        host.matches(IPV4_PATTERN) -> {
            val octets = host.split('.').map { part ->
                part.toIntOrNull()?.takeIf { it in 0..255 }
                    ?: throw IllegalArgumentException("Source host is invalid")
            }
            InetAddress.getByAddress(ByteArray(4) { index -> octets[index].toByte() })
        }
        ':' in host -> runCatching { InetAddress.getByName(host) }
            .getOrElse { throw IllegalArgumentException("Source host is invalid", it) }
        else -> null
    }

    private fun cleanPublicKey(value: String): String = value
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace(WHITESPACE_PATTERN, "")

    private fun decodeBase64(value: String, label: String): ByteArray {
        return runCatching { Base64.decode(value.trim(), Base64.DEFAULT) }
            .getOrElse { throw IllegalArgumentException("Catalog $label is not valid Base64", it) }
            .also { require(it.isNotEmpty()) { "Catalog $label is empty" } }
    }

    private const val SUPPORTED_SCHEMA_VERSION = 1
    private const val MAX_CATALOG_SOURCES = 100
    private const val MAX_SOURCE_NAME_CHARS = 80
    private const val MAX_SOURCE_DESCRIPTION_CHARS = 240
    private const val MAX_ENVELOPE_CHARS = 512 * 1024
    private const val MAX_PAYLOAD_BYTES = 384 * 1024
    private const val MAX_CLOCK_SKEW_MS = 10L * 60L * 1_000L
    private const val MAX_VALIDITY_MS = 90L * 24L * 60L * 60L * 1_000L
    private val IPV4_PATTERN = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private val WHITESPACE_PATTERN = Regex("\\s+")
}

internal data class CatalogLoadResult(
    val catalog: VerifiedSourceCatalog?,
    val origin: SourceCatalogOrigin,
    val error: String = ""
)

internal class SignedSourceCatalogClient(context: Context?) {
    private val preferences = context?.getSharedPreferences("catalog-security", Context.MODE_PRIVATE)
    private val cacheFile = context?.noBackupFilesDir
        ?.resolve("source-catalog")
        ?.resolve("verified-envelope.json")

    val isConfigured: Boolean
        get() = BuildConfig.SOURCE_CATALOG_URL.isNotBlank() &&
            BuildConfig.SOURCE_CATALOG_PUBLIC_KEY.isNotBlank()

    fun load(client: OkHttpClient, networkSession: UserNetworkSession): CatalogLoadResult {
        if (!isConfigured) return CatalogLoadResult(null, SourceCatalogOrigin.BUNDLED)
        val remoteResult = runCatching {
            networkSession.ensureCurrent()
            com.example.data.update.ReleaseManifestVerifier.validateHttpsUrl(BuildConfig.SOURCE_CATALOG_URL)
            val host = URI(BuildConfig.SOURCE_CATALOG_URL).host
            require(networkSession.resolve(host).all(PublicNetworkAddressPolicy::isPublic)) {
                "Catalog host is not public"
            }
            val request = Request.Builder()
                .url(BuildConfig.SOURCE_CATALOG_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "ProxyX-Android/${BuildConfig.VERSION_NAME}")
                .build()
            val envelope = client.newBuilder().followRedirects(false).followSslRedirects(false)
                .build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Catalog HTTP ${response.code}")
                val body = response.body ?: throw IOException("Catalog response is empty")
                val declaredSize = body.contentLength()
                if (declaredSize > MAX_REMOTE_ENVELOPE_CHARS.toLong()) {
                    throw IOException("Catalog response is too large")
                }
                body.charStream().buffered().use { reader ->
                    readBounded(reader, MAX_REMOTE_ENVELOPE_CHARS)
                }
            }
            networkSession.ensureCurrent()
            val verified = SignedSourceCatalogVerifier.verify(
                envelope,
                BuildConfig.SOURCE_CATALOG_PUBLIC_KEY
            )
            require(verified.issuedAt >= (preferences?.getLong("issuedAt", 0) ?: 0)) {
                "Older catalog rejected"
            }
            cacheVerifiedEnvelope(envelope)
            preferences?.edit()?.putLong("issuedAt", verified.issuedAt)?.apply()
            verified
        }
        remoteResult.getOrNull()?.let {
            return CatalogLoadResult(it, SourceCatalogOrigin.SIGNED_REMOTE)
        }

        remoteResult.exceptionOrNull()?.let { error ->
            if (error is UserNetworkException || error is InterruptedException ||
                error is kotlinx.coroutines.CancellationException) throw error
        }

        val remoteError = remoteResult.exceptionOrNull()?.message.orEmpty()
        val cached = runCatching {
            val envelope = readCachedEnvelope()
            SignedSourceCatalogVerifier.verify(
                envelope,
                BuildConfig.SOURCE_CATALOG_PUBLIC_KEY
            ).also { require(it.issuedAt >= (preferences?.getLong("issuedAt", 0) ?: 0)) }
        }.getOrNull()
        return if (cached != null) {
            CatalogLoadResult(cached, SourceCatalogOrigin.SIGNED_CACHE, remoteError)
        } else {
            CatalogLoadResult(null, SourceCatalogOrigin.BUNDLED, remoteError)
        }
    }

    internal fun readCachedEnvelope(): String {
        val file = cacheFile ?: error("No verified catalog cache")
        // openRead restores AtomicFile's backup after an interrupted write.
        return AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
            readBounded(reader, MAX_REMOTE_ENVELOPE_CHARS)
        }
    }

    private fun cacheVerifiedEnvelope(envelope: String) {
        val target = cacheFile ?: return
        target.parentFile?.mkdirs()
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(envelope.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun readBounded(reader: Reader, maxChars: Int): String {
        val result = StringBuilder(minOf(maxChars, 16 * 1024))
        val buffer = CharArray(8 * 1024)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (result.length + count > maxChars) {
                throw IOException("Catalog response is too large")
            }
            result.append(buffer, 0, count)
        }
        return result.toString()
    }

    private companion object {
        const val MAX_REMOTE_ENVELOPE_CHARS = 512 * 1024
    }
}

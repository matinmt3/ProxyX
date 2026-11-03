package com.example.data.repository

import com.example.model.FavoriteProxy
import com.example.model.PrivateProxyNode
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

@JsonClass(generateAdapter = true)
data class ProxyProfileFavorite(
    val url: String,
    val folder: String,
    val tags: String,
    val note: String
)

@JsonClass(generateAdapter = true)
data class ProxyProfilePrivateNode(
    val url: String,
    val label: String,
    val enabled: Boolean
)

@JsonClass(generateAdapter = true)
data class ProxyProfilePayload(
    val formatVersion: Int,
    val exportedAt: Long,
    val favorites: List<ProxyProfileFavorite>,
    val privateNodes: List<ProxyProfilePrivateNode>
)

@JsonClass(generateAdapter = true)
data class ProxyProfileEnvelope(
    val payload: ProxyProfilePayload,
    val sha256: String
)

data class ProxyProfileImport(
    val favorites: List<ProxyProfileFavorite>,
    val privateNodes: List<ProxyProfilePrivateNode>
)

object ProxyProfileCodec {
    private val moshi = Moshi.Builder().build()
    private val payloadAdapter = moshi.adapter(ProxyProfilePayload::class.java)
    private val envelopeAdapter = moshi.adapter(ProxyProfileEnvelope::class.java)

    fun encode(favorites: List<FavoriteProxy>, privateNodes: List<PrivateProxyNode>): String {
        val payload = ProxyProfilePayload(
            formatVersion = FORMAT_VERSION,
            exportedAt = System.currentTimeMillis(),
            favorites = favorites.filter(FavoriteProxy::isActive).map { favorite ->
                ProxyProfileFavorite(
                    url = favorite.url,
                    folder = favorite.folder,
                    tags = favorite.tags,
                    note = favorite.note
                )
            },
            privateNodes = privateNodes.map { node ->
                ProxyProfilePrivateNode(node.url, node.label, node.enabled)
            }
        )
        validateItemCount(payload)
        val canonicalPayload = payloadAdapter.toJson(payload)
        return envelopeAdapter.indent("  ").toJson(
            ProxyProfileEnvelope(payload, sha256(canonicalPayload))
        ).also { validateSize(it.toByteArray(Charsets.UTF_8).size) }
    }

    fun decode(input: InputStream): ProxyProfileImport {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            validateSize(output.size() + count)
            output.write(buffer, 0, count)
        }
        return decode(output.toString(Charsets.UTF_8.name()))
    }

    fun decode(json: String): ProxyProfileImport {
        validateSize(json.toByteArray(Charsets.UTF_8).size)
        val envelope = requireNotNull(envelopeAdapter.fromJson(json)) {
            "The profile is not valid JSON."
        }
        require(envelope.payload.formatVersion == FORMAT_VERSION) {
            "Unsupported profile version."
        }
        val expected = sha256(payloadAdapter.toJson(envelope.payload))
        require(MessageDigest.isEqual(expected.toByteArray(), envelope.sha256.toByteArray())) {
            "The profile integrity check failed."
        }
        validateItemCount(envelope.payload)
        return ProxyProfileImport(
            favorites = envelope.payload.favorites.distinctBy(ProxyProfileFavorite::url),
            privateNodes = envelope.payload.privateNodes.distinctBy(ProxyProfilePrivateNode::url)
        )
    }

    private fun validateSize(bytes: Int) {
        require(bytes <= MAX_PROFILE_BYTES) { "The profile exceeds the 1 MiB size limit." }
    }

    private fun validateItemCount(payload: ProxyProfilePayload) {
        require(payload.favorites.size <= MAX_PROFILE_ITEMS &&
            payload.privateNodes.size <= MAX_PROFILE_ITEMS
        ) { "A profile supports up to $MAX_PROFILE_ITEMS entries of each type." }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    private const val FORMAT_VERSION = 1
    private const val MAX_PROFILE_BYTES = 1_048_576
    private const val MAX_PROFILE_ITEMS = 1_000
}

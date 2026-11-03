package com.example.data.update

import android.util.Base64
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@JsonClass(generateAdapter = true)
data class ReleaseManifest(
    val schemaVersion: Int,
    val channel: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val notes: String = ""
)

@JsonClass(generateAdapter = true)
internal data class ReleaseEnvelope(val payload: String, val signature: String)

internal object ReleaseManifestVerifier {
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 64 * 1024
    private val moshi = Moshi.Builder().build()

    fun verify(json: String, key: String, channel: String, packageName: String,
               sdk: Int, now: Long = System.currentTimeMillis()): ReleaseManifest {
        require(json.length <= MAX_MANIFEST_BYTES) { "Manifest is too large" }
        val envelope = requireNotNull(moshi.adapter(ReleaseEnvelope::class.java).fromJson(json))
        val payload = Base64.decode(envelope.payload, Base64.DEFAULT)
        require(payload.size <= MAX_MANIFEST_BYTES)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payload)
            verify(Base64.decode(envelope.signature, Base64.DEFAULT))
        }) { "Release signature is invalid" }
        val release = requireNotNull(moshi.adapter(ReleaseManifest::class.java)
            .fromJson(payload.toString(Charsets.UTF_8)))
        require(release.schemaVersion == 1 && release.channel == channel && channel in listOf("stable", "beta"))
        require(release.packageName == packageName) { "Wrong application" }
        require(release.versionCode > 0 && release.versionName.length in 1..80)
        require(release.minSdk in 24..sdk) { "This Android version is not supported" }
        require(release.issuedAt in 1..(now + 600_000) && release.expiresAt > now &&
            release.expiresAt > release.issuedAt &&
            release.expiresAt - release.issuedAt <= 90L * 24 * 3600 * 1000) { "Release manifest expired or invalid" }
        require(release.sizeBytes in 1..MAX_APK_BYTES && release.sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(release.notes.length <= 4_000)
        validateHttpsUrl(release.apkUrl)
        return release
    }

    fun validateHttpsUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.port in listOf(-1, 443)) {
            "A public HTTPS URL is required"
        }
    }
}

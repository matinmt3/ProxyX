package com.example.data.update

import android.util.Base64
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
class ReleaseManifestTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val moshi = Moshi.Builder().build()
    private val publicKey = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
    private val valid = ReleaseManifest(1, "stable", "com.example.test", 71, "7.0.1", 24,
        "https://example.com/app.apk", "a".repeat(64), 1024, 1000, 90000)

    @Test fun validSignedReleaseIsAccepted() {
        assertEquals(71L, verify(valid).versionCode)
    }
    @Test fun wrongChannelPackageExpiryAndSizeAreRejected() {
        listOf(valid.copy(channel = "beta"), valid.copy(packageName = "other.app"),
            valid.copy(expiresAt = 1001), valid.copy(sizeBytes = -1),
            valid.copy(sizeBytes = ReleaseManifestVerifier.MAX_APK_BYTES + 1),
            valid.copy(minSdk = 37), valid.copy(apkUrl = "http://example.com/a.apk"),
            valid.copy(sha256 = "wrong")).forEach { release ->
            assertThrows(IllegalArgumentException::class.java) { verify(release) }
        }
    }
    @Test fun changedPayloadAndUnknownSigningKeyAreRejected() {
        val signed = envelope(valid)
        val parsed = moshi.adapter(ReleaseEnvelope::class.java).fromJson(signed)!!
        val changed = parsed.copy(payload = Base64.encodeToString("changed".toByteArray(), Base64.NO_WRAP))
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifestVerifier.verify(moshi.adapter(ReleaseEnvelope::class.java).toJson(changed),
                publicKey, "stable", valid.packageName, 34, 2000)
        }
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseManifestVerifier.verify(signed, Base64.encodeToString(other.public.encoded, Base64.NO_WRAP),
                "stable", valid.packageName, 34, 2000)
        }
    }
    @Test fun unsafeUrlComponentsAreRejected() {
        listOf("https://user:pass@example.com/a", "https://example.com:8080/a", "https://example.com/a#x",
            "file:///a", "http://example.com/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReleaseManifestVerifier.validateHttpsUrl(it) }
        }
    }
    private fun verify(value: ReleaseManifest) = ReleaseManifestVerifier.verify(
        envelope(value), publicKey, "stable", valid.packageName, 34, 2000)
    private fun envelope(value: ReleaseManifest): String {
        val payload = moshi.adapter(ReleaseManifest::class.java).toJson(value).toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(payload); sign() }
        return moshi.adapter(ReleaseEnvelope::class.java).toJson(ReleaseEnvelope(
            Base64.encodeToString(payload, Base64.NO_WRAP), Base64.encodeToString(signature, Base64.NO_WRAP)))
    }
}

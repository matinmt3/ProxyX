package com.example.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.example.BuildConfig
import com.example.data.network.UserNetworkSession
import com.example.data.network.UserNetworkUnavailableException
import com.example.util.PublicNetworkAddressPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdatePhase { IDLE, UNCONFIGURED, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, INSTALL_PERMISSION, FAILED }
data class ReleaseUpdateState(
    val channel: String = "stable",
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: ReleaseManifest? = null,
    val downloadedBytes: Long = 0,
    val error: String = ""
) {
    val busy: Boolean get() = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING
}

class ReleaseUpdateController(
    context: Context,
    private val scope: CoroutineScope,
    private val canUseNetwork: () -> Boolean,
    private val reserveNetwork: () -> Boolean,
    private val releaseNetwork: () -> Unit
) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("release-updates", Context.MODE_PRIVATE)
    private val configured = BuildConfig.RELEASE_MANIFEST_URL.isNotBlank() && BuildConfig.RELEASE_PUBLIC_KEY.isNotBlank()
    private val mutableState = MutableStateFlow(ReleaseUpdateState(
        channel = preferences.getString("channel", "stable").takeIf { it in listOf("stable", "beta") } ?: "stable",
        phase = if (configured) UpdatePhase.IDLE else UpdatePhase.UNCONFIGURED))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    @Volatile private var call: Call? = null
    private val directory get() = File(context.cacheDir, "verified-updates").also { it.mkdirs() }
    private val apk get() = File(directory, "proxyx-update.apk")

    fun selectChannel(channel: String) {
        if (state.value.busy || channel !in listOf("stable", "beta")) return
        preferences.edit().putString("channel", channel).apply()
        mutableState.value = ReleaseUpdateState(channel, if (configured) UpdatePhase.IDLE else UpdatePhase.UNCONFIGURED)
    }

    fun check() = start {
        if (!configured) { mutableState.value = state.value.copy(phase = UpdatePhase.UNCONFIGURED); return@start }
        check(canUseNetwork()) { "Wait for the current network test to finish." }
        val channel = state.value.channel
        mutableState.value = ReleaseUpdateState(channel, UpdatePhase.CHECKING)
        val session = captureNetwork()
        val url = BuildConfig.RELEASE_MANIFEST_URL.replace("{channel}", channel)
        val bytes = fetch(session, url, ReleaseManifestVerifier.MAX_MANIFEST_BYTES.toLong())
        val release = ReleaseManifestVerifier.verify(bytes.toString(Charsets.UTF_8), BuildConfig.RELEASE_PUBLIC_KEY,
            channel, context.packageName, Build.VERSION.SDK_INT)
        require(release.issuedAt >= preferences.getLong("issued-$channel", 0)) { "An older release manifest was rejected" }
        preferences.edit().putLong("issued-$channel", release.issuedAt).apply()
        mutableState.value = ReleaseUpdateState(channel,
            if (release.versionCode > BuildConfig.VERSION_CODE) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT, release)
    }

    fun download() = start {
        val release = state.value.release ?: return@start
        require(release.versionCode > BuildConfig.VERSION_CODE && release.expiresAt > System.currentTimeMillis())
        val partial = File(directory, "download.part")
        check(canUseNetwork() && reserveNetwork()) { "Wait for the current network test to finish." }
        mutableState.value = state.value.copy(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 0, error = "")
        try {
            val session = captureNetwork()
            val request = Request.Builder().url(release.apkUrl).build()
            ReleaseManifestVerifier.validateHttpsUrl(release.apkUrl)
            call = client(session).newCall(request)
            requireNotNull(call).execute().use { response ->
                require(response.isSuccessful) { "Download HTTP ${response.code}" }
                val body = requireNotNull(response.body)
                require(body.contentLength() == -1L || body.contentLength() == release.sizeBytes) { "APK size mismatch" }
                body.byteStream().use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    var lastProgress = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        session.ensureCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= release.sizeBytes) { "APK is larger than declared" }
                        output.write(buffer, 0, count)
                        if (bytes - lastProgress >= 256 * 1024 || bytes == release.sizeBytes) {
                            mutableState.value = state.value.copy(downloadedBytes = bytes)
                            lastProgress = bytes
                        }
                    }
                    output.flush()
                } }
            }
            session.ensureCurrent()
            verifyApk(partial, release)
            require(!apk.exists() || apk.delete())
            require(partial.renameTo(apk)) { "Could not store verified update" }
            mutableState.value = state.value.copy(phase = UpdatePhase.READY)
        } finally { partial.delete(); releaseNetwork() }
    }

    fun install() = start {
        val release = state.value.release ?: return@start
        verifyApk(apk, release)
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                mutableState.value = state.value.copy(phase = UpdatePhase.INSTALL_PERMISSION)
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                mutableState.value = state.value.copy(phase = UpdatePhase.READY)
            }
        }
    }

    fun cancel() {
        call?.cancel()
        job?.cancel()
        mutableState.value = state.value.copy(phase = if (state.value.release != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE)
    }

    private fun start(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            coroutineScope {
                val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call?.cancel() }
                }
                try { block() }
                catch (error: CancellationException) {
                    mutableState.value = state.value.copy(phase = if (state.value.release != null) UpdatePhase.AVAILABLE else UpdatePhase.IDLE)
                    throw error
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    mutableState.value = state.value.copy(phase = UpdatePhase.FAILED, error = error.message.orEmpty().take(180))
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { cancellationWatcher.cancelAndJoin() }
                    call = null
                }
            }
        }
    }

    private fun captureNetwork() = UserNetworkSession.capture(
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager, true)
        ?: throw UserNetworkUnavailableException()

    private fun client(session: UserNetworkSession) = OkHttpClient.Builder()
        .socketFactory(session.socketFactory())
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                session.resolve(hostname).toList().also { addresses ->
                    require(addresses.isNotEmpty() && addresses.all(PublicNetworkAddressPolicy::isPublic)) { "Non-public download host" }
                }
        })
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).build()

    private fun fetch(session: UserNetworkSession, url: String, limit: Long): ByteArray {
        ReleaseManifestVerifier.validateHttpsUrl(url)
        call = client(session).newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
        return requireNotNull(call).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Manifest HTTP ${response.code}")
            val body = requireNotNull(response.body)
            require(body.contentLength() <= limit)
            val output = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    session.ensureCurrent()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= limit)
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, release: ReleaseManifest) {
        require(release.expiresAt > System.currentTimeMillis() && release.versionCode > BuildConfig.VERSION_CODE)
        require(file.isFile && file.length() == release.sizeBytes) { "APK size mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val expected = release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(MessageDigest.isEqual(expected, digest.digest())) { "APK checksum mismatch" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "Invalid APK" }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(archive.packageName == context.packageName && version == release.versionCode) { "APK identity mismatch" }
        fun certificates(info: android.content.pm.PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP) }.toSet()
        }
        val current = certificates(installed)
        require(current.isNotEmpty() && current == certificates(archive)) { "APK signing certificate mismatch" }
    }
}

package com.example.data.network

import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class NetworkSpeedResult(
    val downloadKbps: Int,
    val uploadKbps: Int,
    val measuredAt: Long,
    val downloadSampleBytes: Int,
    val uploadSampleBytes: Int,
    val networkFingerprint: String,
    val networkType: String,
    val isMetered: Boolean
)

/**
 * Measures the active Internet connection directly against Cloudflare's speed-test endpoints.
 * It is deliberately separate from MTProto verification: these bytes do not pass through a
 * Telegram proxy, and no value from this class is assigned to an individual proxy.
 */
class NetworkSpeedMeter(
    private val connectivityManager: ConnectivityManager?
) {
    private data class BandwidthSample(
        val kbps: Int,
        val payloadBytes: Int
    )

    private val measurementMutex = Mutex()

    suspend fun measure(): NetworkSpeedResult? = measurementMutex.withLock {
        val networkSession = UserNetworkSession.capture(
            connectivityManager = connectivityManager,
            requireActiveNetwork = connectivityManager != null
        ) ?: return@withLock null
        val client = createClient(networkSession)
        try {
            val result = withTimeoutOrNull(TOTAL_MEASUREMENT_TIMEOUT_MS) {
                networkSession.ensureCurrent()
                val download = measureDownloadDirection(client) ?: return@withTimeoutOrNull null
                networkSession.ensureCurrent()
                val upload = measureUploadDirection(client) ?: return@withTimeoutOrNull null
                networkSession.ensureCurrent()
                NetworkSpeedResult(
                    downloadKbps = download.kbps,
                    uploadKbps = upload.kbps,
                    measuredAt = System.currentTimeMillis(),
                    downloadSampleBytes = download.payloadBytes,
                    uploadSampleBytes = upload.payloadBytes,
                    networkFingerprint = networkSession.identity.fingerprint,
                    networkType = networkSession.identity.type,
                    isMetered = networkSession.identity.isMetered
                )
            }
            if (result == null) {
                Log.w(TAG, "Active network speed measurement timed out or returned no valid sample.")
            } else {
                Log.d(
                    TAG,
                    "Measured active network: down=${result.downloadKbps} KiB/s, " +
                        "up=${result.uploadKbps} KiB/s, " +
                        "samples=${result.downloadSampleBytes}/${result.uploadSampleBytes}"
                )
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to measure the active Internet connection", e)
            null
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private suspend fun measureDownloadDirection(client: OkHttpClient): BandwidthSample? {
        measureDownload(client, DOWNLOAD_WARMUP_BYTES)
        val initial = measureDownloadWithRetry(client, DOWNLOAD_INITIAL_BYTES) ?: return null
        val sampleBytes = adaptiveSampleBytes(
            measuredKbps = initial.kbps,
            minimumBytes = DOWNLOAD_INITIAL_BYTES,
            maximumBytes = MAX_DOWNLOAD_SAMPLE_BYTES
        )
        val finalSamples = List(FINAL_SAMPLE_COUNT) {
            measureDownloadWithRetry(client, sampleBytes)
        }.filterNotNull()
        return medianSample(finalSamples.ifEmpty { listOf(initial) })
    }

    private suspend fun measureUploadDirection(client: OkHttpClient): BandwidthSample? {
        measureUpload(client, UPLOAD_WARMUP_BYTES)
        val initial = measureUploadWithRetry(client, UPLOAD_INITIAL_BYTES) ?: return null
        val sampleBytes = adaptiveSampleBytes(
            measuredKbps = initial.kbps,
            minimumBytes = UPLOAD_INITIAL_BYTES,
            maximumBytes = MAX_UPLOAD_SAMPLE_BYTES
        )
        val finalSamples = List(FINAL_SAMPLE_COUNT) {
            measureUploadWithRetry(client, sampleBytes)
        }.filterNotNull()
        return medianSample(finalSamples.ifEmpty { listOf(initial) })
    }

    private fun createClient(networkSession: UserNetworkSession): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        networkSession.network?.let { network ->
            builder.socketFactory(network.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return network.getAllByName(hostname).toList()
                }
            })
        }
        return builder.build()
    }

    private suspend fun measureDownloadWithRetry(
        client: OkHttpClient,
        requestedBytes: Int
    ): BandwidthSample? {
        repeat(MEASUREMENT_ATTEMPTS) {
            measureDownload(client, requestedBytes)?.let { return it }
        }
        return null
    }

    private suspend fun measureDownload(
        client: OkHttpClient,
        requestedBytes: Int
    ): BandwidthSample? {
        val request = Request.Builder()
            .url("$DOWNLOAD_URL?bytes=$requestedBytes&nonce=${System.nanoTime()}")
            .header("Cache-Control", "no-store")
            .header("Accept-Encoding", "identity")
            .build()

        return executeMeasurement(client, request, measure@{ response ->
            if (!response.isSuccessful) return@measure null
            val stream = response.body?.byteStream() ?: return@measure null
            val buffer = ByteArray(IO_BUFFER_BYTES)
            var receivedBytes = 0L
            val startedAt = SystemClock.elapsedRealtimeNanos()
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                receivedBytes += count
                if (receivedBytes > requestedBytes * MAX_RESPONSE_MULTIPLIER.toLong()) {
                    return@measure null
                }
            }
            if (receivedBytes < requestedBytes * MIN_RESPONSE_RATIO) return@measure null
            bandwidthKbps(
                bytes = receivedBytes,
                elapsedNanos = SystemClock.elapsedRealtimeNanos() - startedAt
            )?.let { kbps ->
                BandwidthSample(
                    kbps = kbps,
                    payloadBytes = receivedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            }
        })
    }

    private suspend fun measureUploadWithRetry(
        client: OkHttpClient,
        bytes: Int
    ): BandwidthSample? {
        repeat(MEASUREMENT_ATTEMPTS) {
            measureUpload(client, bytes)?.let { return it }
        }
        return null
    }

    private suspend fun measureUpload(
        client: OkHttpClient,
        bytes: Int
    ): BandwidthSample? {
        val body = TimedUploadBody(bytes)
        val request = Request.Builder()
            .url("$UPLOAD_URL?nonce=${System.nanoTime()}")
            .header("Cache-Control", "no-store")
            .post(body)
            .build()

        return executeMeasurement(client, request, measure@{ response ->
            val responseReceivedAtNanos = SystemClock.elapsedRealtimeNanos()
            response.body?.close()
            if (!response.isSuccessful ||
                body.startedAtNanos <= 0L ||
                body.finishedAtNanos <= body.startedAtNanos
            ) {
                return@measure null
            }
            bandwidthKbps(
                bytes = bytes.toLong(),
                elapsedNanos = responseReceivedAtNanos - body.startedAtNanos
            )?.let { kbps ->
                BandwidthSample(kbps = kbps, payloadBytes = bytes)
            }
        })
    }

    private suspend fun executeMeasurement(
        client: OkHttpClient,
        request: Request,
        measure: (Response) -> BandwidthSample?
    ): BandwidthSample? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val result = call.execute().use(measure)
                if (continuation.isActive) continuation.resume(result)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun bandwidthKbps(bytes: Long, elapsedNanos: Long): Int? {
        if (bytes <= 0L || elapsedNanos <= 0L) return null
        val bytesPerSecond = bytes.toDouble() * NANOS_PER_SECOND / elapsedNanos.toDouble()
        if (!bytesPerSecond.isFinite() || bytesPerSecond <= 0.0) return null
        return (bytesPerSecond / BYTES_PER_KILOBYTE)
            .coerceAtMost(Int.MAX_VALUE.toDouble())
            .toInt()
            .coerceAtLeast(1)
    }

    private fun adaptiveSampleBytes(
        measuredKbps: Int,
        minimumBytes: Int,
        maximumBytes: Int
    ): Int {
        return (measuredKbps.toLong() * 1024L * TARGET_SAMPLE_MS / 1_000L)
            .coerceIn(minimumBytes.toLong(), maximumBytes.toLong())
            .toInt()
    }

    private fun medianSample(samples: List<BandwidthSample>): BandwidthSample {
        return samples.sortedBy(BandwidthSample::kbps)[samples.size / 2]
    }

    private class TimedUploadBody(
        private val byteCount: Int
    ) : RequestBody() {
        @Volatile
        var startedAtNanos: Long = 0L
            private set

        @Volatile
        var finishedAtNanos: Long = 0L
            private set

        override fun contentType(): MediaType = BINARY_MEDIA_TYPE

        override fun contentLength(): Long = byteCount.toLong()

        override fun writeTo(sink: BufferedSink) {
            var remaining = byteCount
            val buffer = ByteArray(UPLOAD_BUFFER_BYTES)
            startedAtNanos = SystemClock.elapsedRealtimeNanos()
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size)
                sink.write(buffer, 0, count)
                remaining -= count
            }
            sink.flush()
            finishedAtNanos = SystemClock.elapsedRealtimeNanos()
        }
    }

    private companion object {
        const val TAG = "NetworkSpeedMeter"
        const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
        const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
        const val IO_BUFFER_BYTES = 64 * 1024
        const val UPLOAD_BUFFER_BYTES = 64 * 1024
        const val MEASUREMENT_ATTEMPTS = 2
        const val FINAL_SAMPLE_COUNT = 3
        const val MAX_RESPONSE_MULTIPLIER = 2
        const val MIN_RESPONSE_RATIO = 0.90
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val BYTES_PER_KILOBYTE = 1024.0
        const val DOWNLOAD_WARMUP_BYTES = 64 * 1024
        const val UPLOAD_WARMUP_BYTES = 32 * 1024
        const val DOWNLOAD_INITIAL_BYTES = 512 * 1024
        const val UPLOAD_INITIAL_BYTES = 256 * 1024
        const val MAX_DOWNLOAD_SAMPLE_BYTES = 12 * 1024 * 1024
        const val MAX_UPLOAD_SAMPLE_BYTES = 6 * 1024 * 1024
        const val TARGET_SAMPLE_MS = 2_200L
        const val TOTAL_MEASUREMENT_TIMEOUT_MS = 90_000L
        val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}

package com.example.data.network

import android.os.SystemClock
import com.example.model.MtProtoProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class MtProtoProbeStatus {
    CONFIRMED,
    FAILED,
    UNSUPPORTED
}

internal data class MtProtoProbeResult(
    val status: MtProtoProbeStatus,
    val roundTripMs: Long = 0L
)

/**
 * Performs the smallest useful Telegram-side check: an obfuscated MTProxy session followed by
 * an unauthenticated req_pq_multi request. This validates the secret and the proxy-to-DC route
 * without requiring a Telegram account or changing the TCP reachability result.
 */
internal class MtProtoConnectionProbe(
    private val socketFactory: () -> Socket
) {
    private data class SecretSpec(
        val key: ByteArray,
        val fakeTlsDomain: String?,
        val useRandomPadding: Boolean
    )

    private data class Session(
        val header: ByteArray,
        val encryptCipher: Cipher,
        val decryptCipher: Cipher
    )

    private val secureRandom = SecureRandom()

    suspend fun probe(
        proxy: MtProtoProxy,
        address: InetAddress,
        timeoutMs: Int,
        dcId: Int = DEFAULT_DC_ID
    ): MtProtoProbeResult = withContext(Dispatchers.IO) {
        if (dcId !in MIN_DC_ID..MAX_DC_ID) {
            return@withContext MtProtoProbeResult(MtProtoProbeStatus.UNSUPPORTED)
        }
        val secretSpec = decodeSecret(proxy.secret)
            ?: return@withContext MtProtoProbeResult(MtProtoProbeStatus.UNSUPPORTED)
        withTimeoutOrNull(timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS).toLong() * 2) {
          suspendCancellableCoroutine { continuation ->
            val socket = try {
                socketFactory()
            } catch (error: UserNetworkException) {
                if (continuation.isActive) continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            } catch (error: Exception) {
                if (continuation.isActive) {
                    continuation.resume(MtProtoProbeResult(MtProtoProbeStatus.FAILED))
                }
                return@suspendCancellableCoroutine
            }

            // Closing the same socket from the cancellation handler interrupts both connect and
            // readFully immediately instead of waiting for the protocol timeout.
            continuation.invokeOnCancellation {
                runCatching { socket.close() }
            }

            try {
                val result = socket.use {
                    probeSocket(it, proxy.port, address, secretSpec, timeoutMs, dcId)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (continuation.isActive) {
                    continuation.resume(MtProtoProbeResult(MtProtoProbeStatus.FAILED))
                }
            }
          }
        } ?: MtProtoProbeResult(MtProtoProbeStatus.FAILED)
    }

    private fun probeSocket(
        socket: Socket,
        port: Int,
        address: InetAddress,
        secretSpec: SecretSpec,
        timeoutMs: Int,
        dcId: Int
    ): MtProtoProbeResult {
        val boundedTimeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        socket.tcpNoDelay = true
        socket.soTimeout = boundedTimeout
        socket.connect(
            InetSocketAddress(address, port),
            boundedTimeout
        )
        val startedAt = SystemClock.elapsedRealtimeNanos()

        val session = createSession(
            secret = secretSpec.key,
            useRandomPadding = secretSpec.useRandomPadding,
            dcId = dcId
        )
        val output = socket.getOutputStream()
        val rawInput = socket.getInputStream()
        if (secretSpec.fakeTlsDomain != null) {
            FakeTlsHandshake.perform(
                input = rawInput,
                output = output,
                secret = secretSpec.key,
                domain = secretSpec.fakeTlsDomain
            )
        }
        val input = if (secretSpec.fakeTlsDomain != null) {
            FakeTlsHandshake.applicationDataInput(rawInput)
        } else {
            rawInput
        }

        val nonce = TelegramPlainHandshake.createNonce()
        val requestPayload = TelegramPlainHandshake.createRequestPayload(nonce)
        val requestFrame = TelegramPlainHandshake.createIntermediateFrame(requestPayload)
        val encryptedFrame = session.encryptCipher.update(requestFrame)
            ?: error("MTProto encryptor returned no bytes")

        if (secretSpec.fakeTlsDomain != null) {
            output.write(
                FakeTlsHandshake.wrapApplicationData(
                    payload = session.header + encryptedFrame,
                    firstRecord = true
                )
            )
        } else {
            output.write(session.header)
            output.write(encryptedFrame)
        }
        output.flush()

        val encryptedLength = TelegramPlainHandshake.readFully(input, TRANSPORT_LENGTH_BYTES)
        val lengthBytes = session.decryptCipher.update(encryptedLength)
            ?: error("MTProto decryptor returned no length")
        val responseLength = TelegramPlainHandshake.littleEndianInt(lengthBytes, 0)
        require(responseLength in MIN_RESPONSE_BYTES..MAX_RESPONSE_BYTES)

        val encryptedPayload = TelegramPlainHandshake.readFully(input, responseLength)
        val responsePayload = session.decryptCipher.update(encryptedPayload)
            ?: error("MTProto decryptor returned no payload")
        require(TelegramPlainHandshake.isValidResPq(responsePayload, nonce))

        val roundTripMs = nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
        return MtProtoProbeResult(
            status = MtProtoProbeStatus.CONFIRMED,
            roundTripMs = roundTripMs
        )
    }

    private fun createSession(
        secret: ByteArray,
        useRandomPadding: Boolean,
        dcId: Int
    ): Session {
        val init = generateInitPayload(useRandomPadding, dcId)
        val reversed = init.reversedArray()

        val encryptKey = sha256(init.copyOfRange(8, 40) + secret)
        val encryptIv = init.copyOfRange(40, 56)
        val decryptKey = sha256(reversed.copyOfRange(8, 40) + secret)
        val decryptIv = reversed.copyOfRange(40, 56)

        val encryptCipher = createAesCtrCipher(Cipher.ENCRYPT_MODE, encryptKey, encryptIv)
        val decryptCipher = createAesCtrCipher(Cipher.DECRYPT_MODE, decryptKey, decryptIv)
        val encryptedInit = encryptCipher.update(init)
            ?: error("MTProto encryptor returned no initialization bytes")
        val finalHeader = init.copyOf().apply {
            encryptedInit.copyInto(this, destinationOffset = 56, startIndex = 56, endIndex = 64)
        }

        return Session(finalHeader, encryptCipher, decryptCipher)
    }

    private fun generateInitPayload(useRandomPadding: Boolean, dcId: Int): ByteArray {
        while (true) {
            val init = ByteArray(INIT_BYTES).also(secureRandom::nextBytes)
            val firstInt = TelegramPlainHandshake.littleEndianInt(init, 0)
            val secondInt = TelegramPlainHandshake.littleEndianInt(init, 4)
            if (init[0].toInt() and 0xff == ABRIDGED_MARKER ||
                firstInt in FORBIDDEN_FIRST_INTS ||
                secondInt == 0
            ) {
                continue
            }

            val transportMarker = if (useRandomPadding) {
                PADDED_INTERMEDIATE_MARKER
            } else {
                INTERMEDIATE_MARKER
            }
            init[56] = transportMarker
            init[57] = transportMarker
            init[58] = transportMarker
            init[59] = transportMarker
            init[60] = dcId.toByte()
            init[61] = 0
            return init
        }
    }

    private fun decodeSecret(secretHex: String): SecretSpec? {
        val bytes = secretHex.hexToBytes() ?: return null
        return when {
            bytes.size == BASE_SECRET_BYTES -> SecretSpec(
                key = bytes,
                fakeTlsDomain = null,
                useRandomPadding = false
            )
            bytes.size > PREFIXED_SECRET_BYTES && bytes[0] == FAKE_TLS_PREFIX -> {
                val domain = bytes.copyOfRange(PREFIXED_SECRET_BYTES, bytes.size)
                    .toString(Charsets.US_ASCII)
                SecretSpec(
                    key = bytes.copyOfRange(1, PREFIXED_SECRET_BYTES),
                    fakeTlsDomain = domain,
                    useRandomPadding = true
                )
            }
            bytes.size == PREFIXED_SECRET_BYTES && bytes[0] == PADDED_INTERMEDIATE_MARKER -> {
                SecretSpec(
                    key = bytes.copyOfRange(1, PREFIXED_SECRET_BYTES),
                    fakeTlsDomain = null,
                    useRandomPadding = true
                )
            }
            else -> null
        }
    }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun createAesCtrCipher(mode: Int, key: ByteArray, iv: ByteArray): Cipher {
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private fun nanosToMillis(nanos: Long): Long {
        return (nanos / 1_000_000L).coerceAtLeast(1L)
    }

    companion object {
        private const val INIT_BYTES = 64
        private const val BASE_SECRET_BYTES = 16
        private const val PREFIXED_SECRET_BYTES = 17
        private const val TRANSPORT_LENGTH_BYTES = 4
        private const val MIN_RESPONSE_BYTES = 4
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MIN_TIMEOUT_MS = 1_000
        private const val MAX_TIMEOUT_MS = 5_000
        private const val MIN_DC_ID = 1
        private const val MAX_DC_ID = 5
        private const val DEFAULT_DC_ID = 2
        private const val ABRIDGED_MARKER = 0xef
        private val PADDED_INTERMEDIATE_MARKER = 0xdd.toByte()
        private val INTERMEDIATE_MARKER = 0xee.toByte()
        private val FAKE_TLS_PREFIX = 0xee.toByte()
        private val FORBIDDEN_FIRST_INTS = setOf(
            0x44414548,
            0x54534f50,
            0x20544547,
            0x4954504f,
            0x02010316,
            0xdddddddd.toInt(),
            0xeeeeeeee.toInt()
        )
    }
}

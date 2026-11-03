package com.example.data.network

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Builds and validates Telegram's account-free req_pq_multi handshake. MTProxy verification uses
 * this payload so a successful result always proves a Telegram response.
 */
internal object TelegramPlainHandshake {
    private val secureRandom = SecureRandom()

    fun createNonce(): ByteArray = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)

    fun createRequestPayload(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES)
        return ByteBuffer.allocate(PLAIN_HEADER_BYTES + REQ_PQ_BODY_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0L)
            .putLong(createMessageId())
            .putInt(REQ_PQ_BODY_BYTES)
            .putInt(REQ_PQ_MULTI_CONSTRUCTOR)
            .put(nonce)
            .array()
    }

    fun createIntermediateFrame(payload: ByteArray): ByteArray {
        return ByteBuffer.allocate(TRANSPORT_LENGTH_BYTES + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun readIntermediatePayload(input: InputStream): ByteArray {
        val responseLength = littleEndianInt(readFully(input, TRANSPORT_LENGTH_BYTES), 0)
        require(responseLength in MIN_RESPONSE_BYTES..MAX_RESPONSE_BYTES)
        return readFully(input, responseLength)
    }

    fun isValidResPq(payload: ByteArray, nonce: ByteArray): Boolean {
        if (nonce.size != NONCE_BYTES ||
            payload.size < PLAIN_HEADER_BYTES + RES_PQ_MIN_BODY_BYTES
        ) {
            return false
        }
        if (payload.copyOfRange(0, Long.SIZE_BYTES).any { it.toInt() != 0 }) return false

        val bodyLength = littleEndianInt(payload, MESSAGE_LENGTH_OFFSET)
        if (bodyLength < RES_PQ_MIN_BODY_BYTES ||
            PLAIN_HEADER_BYTES + bodyLength > payload.size
        ) {
            return false
        }
        if (littleEndianInt(payload, PLAIN_HEADER_BYTES) != RES_PQ_CONSTRUCTOR) return false

        val returnedNonceStart = PLAIN_HEADER_BYTES + Int.SIZE_BYTES
        return payload.copyOfRange(
            returnedNonceStart,
            returnedNonceStart + NONCE_BYTES
        ).contentEquals(nonce)
    }

    fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
    }

    fun readFully(input: InputStream, length: Int): ByteArray {
        require(length >= 0)
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            when {
                count < 0 -> error("Unexpected end of Telegram response")
                count > 0 -> offset += count
                else -> {
                    val singleByte = input.read()
                    if (singleByte < 0) error("Unexpected end of Telegram response")
                    result[offset++] = singleByte.toByte()
                }
            }
        }
        return result
    }

    private fun createMessageId(): Long {
        val millis = System.currentTimeMillis()
        val secondsPart = (millis / 1000L) shl 32
        val fractionPart = (millis % 1000L) * 0x1_0000_0000L / 1000L
        return (secondsPart or fractionPart) and -4L
    }

    private const val NONCE_BYTES = 16
    private const val PLAIN_HEADER_BYTES = 20
    private const val REQ_PQ_BODY_BYTES = 20
    private const val RES_PQ_MIN_BODY_BYTES = 56
    private const val TRANSPORT_LENGTH_BYTES = 4
    private const val MESSAGE_LENGTH_OFFSET = 16
    private const val MIN_RESPONSE_BYTES = 4
    private const val MAX_RESPONSE_BYTES = 256 * 1024
    private const val REQ_PQ_MULTI_CONSTRUCTOR = 0xbe7e8ef1.toInt()
    private const val RES_PQ_CONSTRUCTOR = 0x05162463
}

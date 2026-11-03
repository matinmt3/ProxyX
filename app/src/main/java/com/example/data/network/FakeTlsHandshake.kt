package com.example.data.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class FakeTlsClientHello(
    val bytes: ByteArray,
    val authenticatedRandom: ByteArray
)

/** Telegram Fake-TLS framing used by `ee` MTProxy secrets. */
internal object FakeTlsHandshake {
    private val secureRandom = SecureRandom()

    fun perform(
        input: InputStream,
        output: OutputStream,
        secret: ByteArray,
        domain: String
    ) {
        val hello = createClientHello(
            secret = secret,
            domain = domain,
            unixTimeSeconds = System.currentTimeMillis() / 1_000L
        )
        output.write(hello.bytes)
        output.flush()

        val response = readServerHello(input)
        require(response.size >= RANDOM_OFFSET + RANDOM_BYTES)
        val serverRandom = response.copyOfRange(RANDOM_OFFSET, RANDOM_OFFSET + RANDOM_BYTES)
        val responseForHash = response.copyOf().apply {
            fill(0, RANDOM_OFFSET, RANDOM_OFFSET + RANDOM_BYTES)
        }
        val expected = hmacSha256(
            secret,
            hello.authenticatedRandom + responseForHash
        )
        require(MessageDigest.isEqual(serverRandom, expected)) {
            "Fake-TLS server authentication failed"
        }
    }

    fun wrapApplicationData(payload: ByteArray, firstRecord: Boolean): ByteArray {
        require(payload.isNotEmpty())
        val output = ByteArrayOutputStream(payload.size + TLS_RECORD_HEADER_BYTES + 6)
        if (firstRecord) output.write(FIRST_APPLICATION_PREFIX)
        var offset = 0
        while (offset < payload.size) {
            val chunkSize = minOf(MAX_TLS_RECORD_PAYLOAD, payload.size - offset)
            output.write(TLS_APPLICATION_DATA_PREFIX)
            writeUnsignedShort(output, chunkSize)
            output.write(payload, offset, chunkSize)
            offset += chunkSize
        }
        return output.toByteArray()
    }

    fun applicationDataInput(input: InputStream): InputStream {
        return TlsApplicationDataInputStream(input)
    }

    internal fun createClientHello(
        secret: ByteArray,
        domain: String,
        unixTimeSeconds: Long,
        random: SecureRandom = secureRandom
    ): FakeTlsClientHello {
        require(secret.size == SECRET_BYTES)
        val domainBytes = domain.toByteArray(Charsets.US_ASCII)
        require(domainBytes.isNotEmpty() && domainBytes.size <= MAX_DOMAIN_BYTES)
        require(domainBytes.toString(Charsets.US_ASCII) == domain)

        val grease = createGrease(random)
        val extensions = createExtensions(domainBytes, grease, random).toMutableList()
        Collections.shuffle(extensions, random)

        val extensionBlock = join(
            greasePair(grease, 2),
            hex("0000"),
            join(*extensions.toTypedArray()),
            greasePair(grease, 3),
            hex("000100")
        )
        val clientHelloBody = join(
            hex("0303"),
            ByteArray(RANDOM_BYTES),
            byteArrayOf(SESSION_ID_BYTES.toByte()),
            randomBytes(random, SESSION_ID_BYTES),
            hex("0020"),
            greasePair(grease, 0),
            hex("130113021303c02bc02fc02cc030cca9cca8c013c014009c009d002f0035"),
            hex("0100"),
            scope(extensionBlock)
        )
        val handshake = join(hex("0100"), scope(clientHelloBody))
        val hello = join(hex("160301"), scope(handshake))
        require(hello.size >= RANDOM_OFFSET + RANDOM_BYTES)

        val authenticated = hello.copyOf()
        val hash = hmacSha256(secret, authenticated)
        val timestamp = unixTimeSeconds.toInt()
        for (index in 0 until Int.SIZE_BYTES) {
            hash[RANDOM_BYTES - Int.SIZE_BYTES + index] =
                (hash[RANDOM_BYTES - Int.SIZE_BYTES + index].toInt() xor
                    ((timestamp ushr (index * 8)) and 0xff)).toByte()
        }
        hash.copyInto(authenticated, destinationOffset = RANDOM_OFFSET)
        return FakeTlsClientHello(authenticated, hash)
    }

    private fun createExtensions(
        domain: ByteArray,
        grease: ByteArray,
        random: SecureRandom
    ): List<ByteArray> {
        val serverName = join(
            hex("0000"),
            scope(scope(join(byteArrayOf(0), scope(domain))))
        )
        val keyShareEntries = join(
            greasePair(grease, 4),
            hex("000100"),
            hex("11ec"),
            unsignedShort(ML_KEM_KEY_BYTES + X25519_KEY_BYTES),
            mlKem768Key(random),
            x25519LikeKey(random),
            hex("001d0020"),
            x25519LikeKey(random)
        )
        val echPayload = randomBytes(random, 144 + random.nextInt(4) * 32)
        return listOf(
            serverName,
            hex("000500050100000000"),
            join(hex("000a000c000a"), greasePair(grease, 4), hex("11ec001d00170018")),
            hex("000b00020100"),
            hex("000d0012001004030804040105030805050108060601"),
            hex("0010000e000c02683208687474702f312e31"),
            hex("00120000"),
            hex("00170000"),
            hex("001b0003020002"),
            hex("00230000"),
            join(hex("002b000706"), greasePair(grease, 6), hex("03040303")),
            hex("002d00020101"),
            join(hex("0033"), scope(join(unsignedShort(keyShareEntries.size), keyShareEntries))),
            hex("44cd00050003026832"),
            join(
                hex("fe0d"),
                scope(
                    join(
                        hex("0000010001"),
                        randomBytes(random, 1),
                        hex("0020"),
                        randomBytes(random, 32),
                        scope(echPayload)
                    )
                )
            ),
            hex("ff01000100")
        )
    }

    private fun readServerHello(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val firstPrefix = TelegramPlainHandshake.readFully(input, 3)
        require(firstPrefix.contentEquals(TLS_SERVER_HELLO_PREFIX))
        output.write(firstPrefix)
        val firstLengthBytes = TelegramPlainHandshake.readFully(input, 2)
        output.write(firstLengthBytes)
        val firstLength = unsignedShort(firstLengthBytes)
        require(firstLength in 1..MAX_TLS_RECORD_PAYLOAD)
        output.write(TelegramPlainHandshake.readFully(input, firstLength))

        val secondPrefix = TelegramPlainHandshake.readFully(input, SECOND_SERVER_PREFIX.size)
        require(secondPrefix.contentEquals(SECOND_SERVER_PREFIX))
        output.write(secondPrefix)
        val secondLengthBytes = TelegramPlainHandshake.readFully(input, 2)
        output.write(secondLengthBytes)
        val secondLength = unsignedShort(secondLengthBytes)
        require(secondLength in 1..MAX_TLS_RECORD_PAYLOAD)
        output.write(TelegramPlainHandshake.readFully(input, secondLength))
        return output.toByteArray()
    }

    private fun createGrease(random: SecureRandom): ByteArray {
        val values = ByteArray(GREASE_VALUES)
        for (index in values.indices) {
            var value = ((random.nextInt(256) and 0xf0) or 0x0a).toByte()
            if (index > 0 && value == values[index - 1]) {
                value = (value.toInt() xor 0x10).toByte()
            }
            values[index] = value
        }
        return values
    }

    private fun greasePair(grease: ByteArray, index: Int): ByteArray {
        val value = grease[index]
        return byteArrayOf(value, value)
    }

    private fun mlKem768Key(random: SecureRandom): ByteArray {
        val output = ByteArrayOutputStream(ML_KEM_KEY_BYTES)
        repeat(384) {
            val first = random.nextInt(3_329)
            val second = random.nextInt(3_329)
            output.write(first and 0xff)
            output.write((first ushr 8) or ((second and 0x0f) shl 4))
            output.write(second ushr 4)
        }
        output.write(randomBytes(random, 32))
        return output.toByteArray()
    }

    private fun x25519LikeKey(random: SecureRandom): ByteArray {
        return randomBytes(random, X25519_KEY_BYTES).apply {
            this[lastIndex] = (this[lastIndex].toInt() and 0x7f).toByte()
        }
    }

    private fun randomBytes(random: SecureRandom, size: Int): ByteArray {
        return ByteArray(size).also(random::nextBytes)
    }

    private fun scope(content: ByteArray): ByteArray {
        require(content.size <= 0xffff)
        return join(unsignedShort(content.size), content)
    }

    private fun unsignedShort(value: Int): ByteArray {
        require(value in 0..0xffff)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun unsignedShort(bytes: ByteArray): Int {
        require(bytes.size == 2)
        return ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
    }

    private fun writeUnsignedShort(output: OutputStream, value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun join(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(parts.sumOf(ByteArray::size))
        parts.forEach(output::write)
        return output.toByteArray()
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun hmacSha256(secret: ByteArray, data: ByteArray): ByteArray {
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(data)
        }
    }

    private class TlsApplicationDataInputStream(
        private val source: InputStream
    ) : InputStream() {
        private var current = ByteArrayInputStream(ByteArray(0))

        override fun read(): Int {
            ensurePayload()
            return current.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (length == 0) return 0
            ensurePayload()
            return current.read(buffer, offset, length)
        }

        override fun close() {
            source.close()
        }

        private fun ensurePayload() {
            while (current.available() == 0) {
                val header = TelegramPlainHandshake.readFully(source, TLS_RECORD_HEADER_BYTES)
                require(header.copyOfRange(0, 3).contentEquals(TLS_APPLICATION_DATA_PREFIX))
                val length = ((header[3].toInt() and 0xff) shl 8) or
                    (header[4].toInt() and 0xff)
                require(length in 1..MAX_TLS_RECORD_PAYLOAD)
                current = ByteArrayInputStream(
                    TelegramPlainHandshake.readFully(source, length)
                )
            }
        }
    }

    private const val SECRET_BYTES = 16
    private const val RANDOM_OFFSET = 11
    private const val RANDOM_BYTES = 32
    private const val SESSION_ID_BYTES = 32
    private const val ML_KEM_KEY_BYTES = 1_184
    private const val X25519_KEY_BYTES = 32
    private const val GREASE_VALUES = 7
    private const val MAX_DOMAIN_BYTES = 253
    private const val TLS_RECORD_HEADER_BYTES = 5
    private const val MAX_TLS_RECORD_PAYLOAD = 16 * 1024
    private val TLS_SERVER_HELLO_PREFIX = hex("160303")
    private val SECOND_SERVER_PREFIX = hex("140303000101170303")
    private val FIRST_APPLICATION_PREFIX = hex("140303000101")
    private val TLS_APPLICATION_DATA_PREFIX = hex("170303")
}

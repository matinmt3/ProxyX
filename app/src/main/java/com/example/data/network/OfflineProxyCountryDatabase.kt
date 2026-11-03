package com.example.data.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

internal class OfflineProxyCountryDatabase(
    context: Context
) {
    private data class TargetIp(
        val text: String,
        val number: Long
    )

    private val appContext = context.applicationContext

    suspend fun resolve(ips: Collection<String>): Map<String, ResolvedProxyCountry> {
        if (ips.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                resolveFromAsset(ips)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read the offline proxy-country database.", e)
                emptyMap()
            }
        }
    }

    private suspend fun resolveFromAsset(
        ips: Collection<String>
    ): Map<String, ResolvedProxyCountry> {
        val targets = ips.asSequence()
            .distinct()
            .mapNotNull { ip -> ipv4ToLong(ip)?.let { TargetIp(ip, it) } }
            .sortedBy(TargetIp::number)
            .toList()
        if (targets.isEmpty()) return emptyMap()

        val startedAt = SystemClock.elapsedRealtime()
        val resolved = LinkedHashMap<String, ResolvedProxyCountry>(targets.size)
        var targetIndex = 0
        var lineCount = 0
        appContext.assets.open(ASSET_PATH).use { asset ->
            // AAPT recognizes .gz source assets, stores their compressed payload efficiently,
            // and exposes the decompressed file without the .gz suffix at runtime.
            BufferedReader(
                InputStreamReader(asset, Charsets.US_ASCII),
                READER_BUFFER_CHARS
            ).use { reader ->
                while (targetIndex < targets.size) {
                    val line = reader.readLine() ?: break
                    lineCount += 1
                    if (lineCount % CANCELLATION_CHECK_LINES == 0) {
                        currentCoroutineContext().ensureActive()
                    }
                    val range = parseRange(line) ?: continue
                    while (targetIndex < targets.size) {
                        val target = targets[targetIndex]
                        when {
                            target.number < range.start -> targetIndex += 1
                            target.number <= range.end -> {
                                countryFromCode(range.countryCode)?.let { country ->
                                    resolved[target.text] = country
                                }
                                targetIndex += 1
                            }
                            else -> break
                        }
                    }
                }
            }
        }
        Log.d(
            TAG,
            "Offline country database resolved ${resolved.size}/${targets.size} IPv4 endpoints " +
                "in ${SystemClock.elapsedRealtime() - startedAt} ms."
        )
        return resolved
    }

    private data class NumericCountryRange(
        val start: Long,
        val end: Long,
        val countryCode: String
    )

    private fun parseRange(line: String): NumericCountryRange? {
        val firstComma = line.indexOf(',')
        if (firstComma <= 0) return null
        val secondComma = line.indexOf(',', firstComma + 1)
        if (secondComma <= firstComma + 1 || secondComma >= line.lastIndex) return null
        val start = line.substring(0, firstComma).toLongOrNull() ?: return null
        val end = line.substring(firstComma + 1, secondComma).toLongOrNull() ?: return null
        if (start !in 0..MAX_IPV4_VALUE || end !in start..MAX_IPV4_VALUE) return null
        val code = line.substring(secondComma + 1).trim()
        return NumericCountryRange(start = start, end = end, countryCode = code)
    }

    private fun ipv4ToLong(ip: String): Long? {
        var value = 0L
        var octet = 0
        var octetDigits = 0
        var separators = 0
        for (character in ip) {
            when {
                character in '0'..'9' -> {
                    octet = octet * 10 + (character - '0')
                    octetDigits += 1
                    if (octet > 255 || octetDigits > 3) return null
                }
                character == '.' && octetDigits > 0 && separators < 3 -> {
                    value = (value shl 8) or octet.toLong()
                    octet = 0
                    octetDigits = 0
                    separators += 1
                }
                else -> return null
            }
        }
        if (separators != 3 || octetDigits == 0) return null
        return (value shl 8) or octet.toLong()
    }

    private fun countryFromCode(rawCode: String): ResolvedProxyCountry? {
        val code = rawCode
            .uppercase(Locale.ROOT)
            .takeIf { it.length == 2 && it.all { character -> character in 'A'..'Z' } }
            ?.takeUnless { it == UNKNOWN_DATASET_CODE }
            ?: return null
        val name = Locale.Builder()
            .setRegion(code)
            .build()
            .getDisplayCountry(Locale.ENGLISH)
            .takeIf { it.isNotBlank() }
            ?: return null
        return ResolvedProxyCountry(name = name, code = code)
    }

    private companion object {
        const val TAG = "OfflineProxyCountryDb"
        const val ASSET_PATH = "geoip/server-country-ipv4-num.csv"
        const val UNKNOWN_DATASET_CODE = "ZZ"
        const val MAX_IPV4_VALUE = 0xffff_ffffL
        const val READER_BUFFER_CHARS = 64 * 1024
        const val CANCELLATION_CHECK_LINES = 2_048
    }
}

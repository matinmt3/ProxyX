package com.example.data.network

import com.example.model.MtProtoProxy
import com.squareup.moshi.Moshi
import java.util.Locale

internal class ProxyContentParser {
    private val jsonAdapter by lazy {
        Moshi.Builder().build().adapter(Any::class.java)
    }

    fun parse(content: String): List<MtProtoProxy> {
        val candidates = linkedSetOf<String>()
        collectCandidates(content, candidates)

        if (content.firstNonWhitespace() in setOf('{', '[')) {
            runCatching { jsonAdapter.fromJson(content) }
                .getOrNull()
                ?.let { collectJsonStrings(it, candidates) }
        }

        return candidates
            .mapNotNull(MtProtoProxy::parseFromUrl)
            .distinctBy(::proxyKey)
    }

    private fun collectJsonStrings(
        value: Any?,
        candidates: MutableSet<String>
    ) {
        when (value) {
            is String -> collectCandidates(value, candidates)
            is Map<*, *> -> value.values.forEach {
                collectJsonStrings(it, candidates)
            }
            is Iterable<*> -> value.forEach {
                collectJsonStrings(it, candidates)
            }
            is Array<*> -> value.forEach {
                collectJsonStrings(it, candidates)
            }
        }
    }

    private fun collectCandidates(
        text: String,
        candidates: MutableSet<String>
    ) {
        val normalized = ESCAPED_TOKEN_REGEX.replace(text) { token ->
            when (token.value.lowercase(Locale.ROOT)) {
                "\\/", "&#x2f;" -> "/"
                "&quot;" -> "\""
                else -> "&"
            }
        }

        MTPROTO_URL_REGEX.findAll(normalized).forEach { match ->
            candidates += match.value.trimEnd('.', ',', ';', ':', ']')
        }
    }

    private fun String.firstNonWhitespace(): Char? = firstOrNull { !it.isWhitespace() }

    private fun proxyKey(proxy: MtProtoProxy): String {
        return "${proxy.server.lowercase(Locale.ROOT)}:${proxy.port}:" +
            proxy.secret.lowercase(Locale.ROOT)
    }

    private companion object {
        val ESCAPED_TOKEN_REGEX = Regex(
            pattern = """\\/|\\u0026|&amp;|&#38;|&#x26;|&#x2f;|&quot;""",
            option = RegexOption.IGNORE_CASE
        )
        val MTPROTO_URL_REGEX = Regex(
            pattern = """(?:tg://proxy|https?://t\.me/proxy)\?[^\s\"'()<>{},\\]+""",
            option = RegexOption.IGNORE_CASE
        )
    }
}

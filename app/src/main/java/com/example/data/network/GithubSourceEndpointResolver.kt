package com.example.data.network

import java.net.URI
import java.util.Locale

internal object GithubSourceEndpointResolver {
    fun candidates(sourceUrl: String, preferMirror: Boolean): List<String> {
        val githubPath = parseRawGithubUrl(sourceUrl) ?: return listOf(sourceUrl)
        val mirrorUrl = githubPath.toJsDelivrUrl()
        val mediaUrl = githubPath.toMediaUrl()
        return if (preferMirror) {
            listOf(mirrorUrl, mediaUrl, sourceUrl)
        } else {
            listOf(sourceUrl, mirrorUrl, mediaUrl)
        }
    }

    fun isMirror(url: String): Boolean {
        return runCatching { URI(url).host }
            .getOrNull()
            ?.let { host ->
                host.equals(JSDELIVR_HOST, ignoreCase = true) ||
                    host.equals(GITHUB_MEDIA_HOST, ignoreCase = true)
            } == true
    }

    private fun parseRawGithubUrl(sourceUrl: String): GithubPath? {
        val uri = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
        if (!uri.host.equals(RAW_GITHUB_HOST, ignoreCase = true)) return null

        val parts = uri.rawPath
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
        if (parts.size < 4) return null

        val owner = parts[0]
        val repository = parts[1]
        val usesExplicitHeadRef = parts.size >= 6 &&
            parts[2].lowercase(Locale.ROOT) == "refs" &&
            parts[3].lowercase(Locale.ROOT) == "heads"
        val branchIndex = if (usesExplicitHeadRef) 4 else 2
        val contentIndex = branchIndex + 1
        if (parts.size <= contentIndex) return null

        val branch = parts[branchIndex]
        val contentPath = parts.drop(contentIndex).joinToString("/")
        return GithubPath(owner, repository, branch, contentPath)
    }

    private data class GithubPath(
        val owner: String,
        val repository: String,
        val branch: String,
        val contentPath: String
    ) {
        fun toJsDelivrUrl(): String =
            "https://$JSDELIVR_HOST/gh/$owner/$repository@$branch/$contentPath"

        fun toMediaUrl(): String =
            "https://$GITHUB_MEDIA_HOST/media/$owner/$repository/$branch/$contentPath"
    }

    private const val RAW_GITHUB_HOST = "raw.githubusercontent.com"
    private const val JSDELIVR_HOST = "cdn.jsdelivr.net"
    private const val GITHUB_MEDIA_HOST = "media.githubusercontent.com"
}

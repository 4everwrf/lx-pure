package com.lxpure.music.core.userapi

/**
 * Normalizes user-supplied custom source script URLs for manual update checks.
 * This application does not bundle, recommend, or hardcode any third-party source URLs.
 */
object SourceUrlUtils {
    fun buildUpdateCandidateUrls(sourceUrl: String?): List<String> {
        val trimmed = sourceUrl?.trim().orEmpty()
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            listOf(trimmed)
        } else {
            emptyList()
        }
    }
}

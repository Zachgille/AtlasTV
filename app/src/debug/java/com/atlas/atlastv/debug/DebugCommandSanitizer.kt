package com.atlas.atlastv.debug

import com.atlas.atlastv.core.security.UrlRedactor

object DebugCommandSanitizer {
    private val StreamUrlPattern = Regex(
        pattern = """https?://[^\s"']+/(live|movie|series)/(?!REDACTED/REDACTED/)[^\s"']+""",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    fun redactedHost(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return UrlRedactor.redactHostAndSensitiveQuery(value)
            .substringAfter("://", missingDelimiterValue = "REDACTED_HOST")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }
    }

    fun redactedUrl(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return UrlRedactor.redactHostAndSensitiveQuery(value)
    }

    fun sanitizeText(text: String): String =
        StreamUrlPattern.replace(text, "REDACTED_STREAM_URL")
}

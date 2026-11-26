package com.atlas.atlastv.core.security

import java.net.URI

object UrlRedactor {
    private val SensitiveKeys = setOf(
        "username",
        "user",
        "password",
        "pass",
        "token",
        "key",
        "auth",
    )

    fun redact(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url

        val fragmentStart = url.indexOf('#', startIndex = queryStart + 1)
        val queryEnd = if (fragmentStart >= 0) fragmentStart else url.length
        val prefix = url.substring(0, queryStart + 1)
        val query = url.substring(queryStart + 1, queryEnd)
        val suffix = if (fragmentStart >= 0) url.substring(fragmentStart) else ""

        val redactedQuery = query
            .split('&')
            .joinToString("&") { parameter -> redactParameter(parameter) }

        return prefix + redactedQuery + suffix
    }

    fun redactHostAndSensitiveQuery(url: String): String {
        val redacted = redact(url)
        return try {
            val uri = URI(redacted)
            val schemePrefix = uri.scheme?.let { "$it://" } ?: ""
            val path = uri.rawPath.orEmpty()
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
            "${schemePrefix}REDACTED_HOST$path$query$fragment"
        } catch (_: Exception) {
            "REDACTED_URL"
        }
    }

    private fun redactParameter(parameter: String): String {
        val equalsIndex = parameter.indexOf('=')
        if (equalsIndex < 0) {
            return if (isSensitiveKey(parameter)) "$parameter=REDACTED" else parameter
        }

        val key = parameter.substring(0, equalsIndex)
        return if (isSensitiveKey(key)) {
            "$key=REDACTED"
        } else {
            parameter
        }
    }

    private fun isSensitiveKey(key: String): Boolean =
        SensitiveKeys.contains(key.trim().lowercase())
}

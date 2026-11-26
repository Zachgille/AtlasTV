package com.atlas.atlastv.core.config

data class DebugXtreamConfigStatus(
    val hasBaseUrl: Boolean,
    val hasUsername: Boolean,
    val hasPassword: Boolean,
    val redactedBaseUrl: String?,
    val configSource: String,
) {
    val isAvailable: Boolean
        get() = hasBaseUrl && hasUsername && hasPassword
}

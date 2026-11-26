package com.atlas.atlastv.core.config

import com.atlas.atlastv.BuildConfig
import com.atlas.atlastv.core.security.UrlRedactor
import com.atlas.atlastv.xtream.model.XtreamCredentials

object DebugConfig {
    val debugPlaylistUrl: String
        get() = BuildConfig.LUMAIRE_DEBUG_PLAYLIST_URL

    val debugXtreamCredentials: XtreamCredentials?
        get() {
            val baseUrl = BuildConfig.LUMAIRE_DEBUG_XTREAM_BASE_URL
            val username = BuildConfig.LUMAIRE_DEBUG_XTREAM_USERNAME
            val password = BuildConfig.LUMAIRE_DEBUG_XTREAM_PASSWORD
            return if (baseUrl.isBlank() || username.isBlank() || password.isBlank()) {
                null
            } else {
                XtreamCredentials(
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                )
            }
        }

    val debugXtreamConfigStatus: DebugXtreamConfigStatus
        get() {
            val baseUrl = BuildConfig.LUMAIRE_DEBUG_XTREAM_BASE_URL
            val username = BuildConfig.LUMAIRE_DEBUG_XTREAM_USERNAME
            val password = BuildConfig.LUMAIRE_DEBUG_XTREAM_PASSWORD
            return DebugXtreamConfigStatus(
                hasBaseUrl = baseUrl.isNotBlank(),
                hasUsername = username.isNotBlank(),
                hasPassword = password.isNotBlank(),
                redactedBaseUrl = baseUrl.takeIf { it.isNotBlank() }?.let(UrlRedactor::redactHostAndSensitiveQuery),
                configSource = "debug BuildConfig",
            )
        }
}

package com.atlas.atlastv.xtream.model

data class XtreamCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
    val alternateBaseUrls: List<String> = emptyList(),
    val preferredBaseUrl: String? = null,
)

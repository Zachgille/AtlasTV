package com.atlas.atlastv.ui.media

import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class AtlasImageRef(
    val state: AtlasImageState,
    val safeUri: String? = null,
    val providerImageToken: String? = null,
    val redactedSource: String? = null,
) {
    companion object {
        val Missing = AtlasImageRef(AtlasImageState.Missing)
    }
}

enum class AtlasImageState {
    Missing,
    SafeImage,
    ProviderImage,
    ProviderImageHidden,
}

fun atlasImageRefFromRaw(rawValue: String?, rememberProviderAddress: Boolean = true): AtlasImageRef {
    val value = rawValue?.trim()?.takeIf { it.isNotBlank() } ?: return AtlasImageRef.Missing
    val lower = value.lowercase()
    if (lower.startsWith(PROVIDER_IMAGE_TOKEN_PREFIX)) {
        return AtlasImageRef(
            state = AtlasImageState.ProviderImage,
            providerImageToken = value,
            redactedSource = "provider_image",
        )
    }
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return if (value.looksLikeResolvableProviderImageUrl()) {
            AtlasImageRef(
                state = AtlasImageState.ProviderImage,
                providerImageToken = if (rememberProviderAddress) AtlasProviderImageRegistry.register(value) else providerImageToken(value),
                redactedSource = "provider_image",
            )
        } else {
            AtlasImageRef(AtlasImageState.ProviderImageHidden, redactedSource = "provider_image")
        }
    }
    if (lower.contains("/live/") || lower.contains("/movie/") || lower.contains("/series/")) {
        return AtlasImageRef(AtlasImageState.ProviderImageHidden, redactedSource = "provider_image")
    }
    val isSafeLocalRef = lower.startsWith("fixture://poster/") ||
        lower.startsWith("android.resource://") ||
        lower.startsWith("asset://poster/")
    return if (isSafeLocalRef) {
        AtlasImageRef(AtlasImageState.SafeImage, safeUri = value, redactedSource = "safe_image")
    } else {
        AtlasImageRef.Missing
    }
}

fun AtlasImageRef.isDisplayFallback(): Boolean =
    state == AtlasImageState.Missing || state == AtlasImageState.ProviderImageHidden

object AtlasProviderImageRegistry {
    private val rawUrlsByToken = ConcurrentHashMap<String, String>()

    fun register(rawUrl: String): String {
        val token = providerImageToken(rawUrl)
        rawUrlsByToken[token] = rawUrl.trim()
        return token
    }

    fun resolve(token: String?): String? =
        token?.let { rawUrlsByToken[it] }

    fun clearForTests() {
        rawUrlsByToken.clear()
    }
}

internal const val PROVIDER_IMAGE_TOKEN_PREFIX = "provider-image://sha256/"

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
private val playbackExtensions = setOf("ts", "m3u8", "mp4", "mkv", "avi", "mov", "wmv", "mpg", "mpeg")

internal fun String.looksLikeProviderPosterUrl(): Boolean {
    val path = runCatching { URI(this).path.orEmpty() }.getOrDefault(this)
    val extension = path.substringAfterLast('/', "")
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.isNotBlank() }
    return when {
        extension in imageExtensions -> true
        extension in playbackExtensions -> false
        lowercase().contains("/live/") -> false
        else -> true
    }
}

internal fun providerImageToken(value: String): String = PROVIDER_IMAGE_TOKEN_PREFIX + sha256(value.trim())

private fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    val result = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        result[index * 2] = digits[value ushr 4]
        result[index * 2 + 1] = digits[value and 0xf]
    }
    return result.concatToString()
}

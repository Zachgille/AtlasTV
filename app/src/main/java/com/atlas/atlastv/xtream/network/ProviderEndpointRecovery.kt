package com.atlas.atlastv.xtream.network

import com.atlas.atlastv.core.network.TextDownloadFailureReason
import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Primary address remains the account identity. Only explicitly configured addresses can be used. */
object ProviderEndpointAddresses {
    const val MaxBackups = 2
    fun normalize(raw: String): String? = runCatching {
        require(raw.length <= 2048)
        val uri = URI(raw.trim()).normalize()
        val scheme = uri.scheme.lowercase()
        require(scheme == "http" || scheme == "https")
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        var host = requireNotNull(uri.host).lowercase()
        require(host.isNotBlank() && uri.port in -1..65535)
        if (host.contains(':') && !host.startsWith('[')) host = "[$host]"
        val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        "$scheme://$host${port?.let { ":$it" }.orEmpty()}${uri.rawPath.orEmpty().trimEnd('/')}"
    }.getOrNull()

    fun configured(credentials: XtreamCredentials): List<String> =
        (listOf(credentials.baseUrl) + credentials.alternateBaseUrls.take(MaxBackups)).mapNotNull(::normalize).distinct()
}

class ProviderEndpointRecovery {
    private val lock = Any()
    private val preferred = LinkedHashMap<String, String>()
    @Volatile private var persist: ((XtreamCredentials, String) -> Unit)? = null
    fun persistUsing(save: (XtreamCredentials, String) -> Unit) { persist = save }

    private fun key(credentials: XtreamCredentials): String {
        val fields = listOf(ProviderEndpointAddresses.normalize(credentials.baseUrl) ?: credentials.baseUrl,
            credentials.username, credentials.password)
        return MessageDigest.getInstance("SHA-256").digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    fun candidates(credentials: XtreamCredentials): List<String> {
        if (ProviderEndpointAddresses.normalize(credentials.baseUrl) == null) return listOf(credentials.baseUrl)
        val allowed = ProviderEndpointAddresses.configured(credentials)
        if (allowed.isEmpty()) return listOf(credentials.baseUrl)
        if (allowed.size == 1) return allowed
        val runtime = synchronized(lock) { preferred[key(credentials)] }
        return (listOfNotNull(runtime, credentials.preferredBaseUrl?.let(ProviderEndpointAddresses::normalize)) + allowed)
            .filter { it in allowed }.distinct()
    }
    fun activeBaseUrl(credentials: XtreamCredentials): String = if (credentials.alternateBaseUrls.isEmpty())
        credentials.baseUrl.trim().trimEnd('/') else candidates(credentials).first()

    suspend fun rememberVerified(credentials: XtreamCredentials, baseUrl: String) {
        coroutineContext.ensureActive()
        val base = ProviderEndpointAddresses.normalize(baseUrl) ?: return
        val allowed = ProviderEndpointAddresses.configured(credentials)
        if (allowed.size <= 1 || base !in allowed) return
        synchronized(lock) {
            val key = key(credentials)
            preferred.remove(key); preferred[key] = base
            while (preferred.size > 8) preferred.remove(preferred.keys.first())
        }
        try { withContext(Dispatchers.IO) { persist?.invoke(credentials, base) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Verified runtime routing remains usable if optional persistence fails. */ }
    }

    suspend fun <T> run(credentials: XtreamCredentials, succeeded: (T) -> Boolean, retryable: (T) -> Boolean,
        attempt: suspend (XtreamCredentials) -> T): T {
        val choices = candidates(credentials)
        for ((index, base) in choices.withIndex()) {
            coroutineContext.ensureActive()
            // An attempt stays on one host, including every category page in a catalog load.
            val result = attempt(credentials.copy(baseUrl = base, alternateBaseUrls = emptyList(), preferredBaseUrl = null))
            coroutineContext.ensureActive()
            if (succeeded(result)) {
                rememberVerified(credentials, base)
                return result
            }
            if (index == choices.lastIndex || !retryable(result)) return result
        }
        error("A primary endpoint is always present")
    }
    companion object {
        val Shared = ProviderEndpointRecovery()
        fun unavailable(category: XtreamFailureCategory, httpStatus: Int? = null): Boolean = when (category) {
            XtreamFailureCategory.DnsFailure, XtreamFailureCategory.Timeout,
            XtreamFailureCategory.ConnectionRefused, XtreamFailureCategory.EmptyResponse, XtreamFailureCategory.UnknownError -> true
            XtreamFailureCategory.Non2xxStatus -> httpStatus in 500..599 || httpStatus == 408
            else -> false
        }
        fun unavailable(failure: TextDownloadResult.Failure): Boolean = when (failure.reason) {
            TextDownloadFailureReason.DnsFailure, TextDownloadFailureReason.Timeout,
            TextDownloadFailureReason.ConnectionRefused, TextDownloadFailureReason.EmptyResponse, TextDownloadFailureReason.NetworkFailure -> true
            TextDownloadFailureReason.HttpStatus -> failure.httpStatus in 500..599 || failure.httpStatus == 408
            else -> false
        }
    }
}

/** Keeps optional metadata failure reasons inside the networking boundary. */
internal data class ProviderMetadataAttempt<T>(val value: T? = null, val failure: TextDownloadResult.Failure? = null)

package com.atlas.atlastv.ui.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One bounded attempt history per card/account/size; late alternatives never refetch working art. */
internal class PosterArtworkFallback<T : Any> {
    data class Loaded<T>(val value: T, val token: String, val saved: Boolean)
    private val attempted = mutableSetOf<String>()
    private var loaded: Loaded<T>? = null
    suspend fun load(primary: AtlasImageRef, saved: List<AtlasImageRef>, fetch: suspend (String) -> T?): Loaded<T>? {
        loaded?.let { return it }
        // Local fixture/resource presentation retains its established rendering path.
        if (primary.state == AtlasImageState.SafeImage) return null
        val candidates = (listOf(primary) + saved.take(2))
            .filter { it.state == AtlasImageState.ProviderImage && isProviderImageToken(it.providerImageToken.orEmpty()) }
            .distinctBy { it.providerImageToken }
        attempted.retainAll(candidates.mapNotNull { it.providerImageToken }.toSet())
        for (ref in candidates) {
            val token = ref.providerImageToken!!
            if (token in attempted) continue
            val isPrimary = ref == primary
            repeat(if (isPrimary) 2 else 1) { attempt ->
                val value = try { fetch(token) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                currentCoroutineContext().ensureActive()
                if (value != null) return Loaded(value, token, !isPrimary).also { loaded = it }
                if (isPrimary && attempt == 0) delay(250)
            }
            attempted.add(token)
        }
        return null
    }
}

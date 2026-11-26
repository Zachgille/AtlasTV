package com.atlas.atlastv.ui.media

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.min

object AtlasProviderImageLoader {
    const val MaxMemoryBytes = 32L * 1024L * 1024L
    const val MaxConcurrentLoads = 4
    private data class Key(val directory: String?, val token: String, val size: ArtworkSize)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinator = ArtworkLoadCoordinator<Key, Bitmap>(scope, MaxMemoryBytes, MaxConcurrentLoads) { it.allocationByteCount.toLong() }
    private data class PendingWrite(val cache: AtlasProviderImageCache, val token: String, val bytes: ByteArray)
    private val writes = ArtworkWriteQueue<PendingWrite>(scope) { it.cache.write(it.token, it.bytes); Unit }
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private var callbacksRegistered = false

    /** Only application context is retained; cached bitmaps are never recycled while a card can use them. */
    @Synchronized fun initialize(context: Context) {
        if (callbacksRegistered) return
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = coordinator.clearMemory()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) coordinator.clearMemory()
            }
        }
        context.applicationContext.registerComponentCallbacks(callbacks)
        callbacksRegistered = true
    }

    suspend fun load(
        token: String?, filesDir: File?, index: AtlasProviderImageIndex? = null,
        target: ArtworkSize = ArtworkSize.Poster,
        priority: ArtworkPriority = ArtworkPriority.Visible,
        priorityChanges: StateFlow<ArtworkPriority>? = null,
    ): ImageBitmap? {
        val safe = token?.takeIf(::isProviderImageToken) ?: return null
        currentCoroutineContext().ensureActive()
        val key = Key(filesDir?.absoluteFile?.toPath()?.normalize()?.toString(), safe, target)
        coordinator.cachedMatching { it.directory == key.directory && it.token == safe &&
            it.size.width >= target.width && it.size.height >= target.height }?.let { return it.asImageBitmap() }
        val cache = filesDir?.let { AtlasProviderImageCache(File(it, AtlasProviderImageCache.DirectoryName)) }
        return coordinator.load(key, priority = priority, priorityChanges = priorityChanges,
            reusableKey = { it.directory == key.directory && it.token == safe &&
                it.size.width >= target.width && it.size.height >= target.height }, readCached = cached@ {
            try {
                val cached = cache?.read(safe)
                if (cached != null) {
                    decode(cached, target)?.let { return@cached it }
                    cache.remove(safe)
                }
                null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }) fetch@ {
            try {
                currentCoroutineContext().ensureActive()
                val imageIndex = index ?: filesDir?.let { AtlasProviderImageIndex(File(it, AtlasProviderImageIndex.FileName)) }
                val address = imageIndex?.resolveAsync(safe) ?: AtlasProviderImageRegistry.resolve(safe) ?: return@fetch null
                val request = Request.Builder().url(address)
                    .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .header("User-Agent", "AtlasTV/1.0").build()
                val bytes = download(request) ?: return@fetch null
                currentCoroutineContext().ensureActive()
                val bitmap = decode(bytes, target) ?: return@fetch null
                currentCoroutineContext().ensureActive()
                if (cache != null) writes.offer(PendingWrite(cache, safe, bytes))
                bitmap
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }?.asImageBitmap()
    }

    /** Cancellation closes the HTTP call, including a stalled response body. */
    private suspend fun download(request: Request): ByteArray? = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val bytes = try {
                    response.use {
                        if (!continuation.isActive || !it.isSuccessful || it.body.contentLength() > AtlasProviderImageCache.DefaultMaxImageBytes) null
                        else it.body.byteStream().use { stream -> readArtworkBytes(stream, AtlasProviderImageCache.DefaultMaxImageBytes.toInt()) }
                    }
                } catch (_: Exception) { null }
                if (continuation.isActive) continuation.resume(bytes)
            }
        })
    }

    private fun decode(bytes: ByteArray, target: ArtworkSize): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val plan = ArtworkDecodePlan.forSource(bounds.outWidth, bounds.outHeight, target) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = plan.sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        if (bitmap.width.toLong() * bitmap.height > ArtworkDecodePlan.MaxDecodedPixels ||
            maxOf(bitmap.width, bitmap.height) > ArtworkDecodePlan.MaxDecodedSide) { bitmap.recycle(); return null }
        val scale = min(1.0, min(plan.width.toDouble() / bitmap.width, plan.height.toDouble() / bitmap.height))
        val width = maxOf(1, (bitmap.width * scale).toInt())
        val height = maxOf(1, (bitmap.height * scale).toInt())
        if (width == bitmap.width && height == bitmap.height) return bitmap
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    internal fun clearMemoryForTests() = coordinator.clearMemory()
    internal fun cachedMemoryBytes(): Long = coordinator.cachedBytes()
}

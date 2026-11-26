package com.atlas.atlastv.ui.media

import android.content.Context
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.ProviderArtworkRoomStore
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.ConcurrentHashMap

/** Full coverage in Room with one-row lookup. JSON is only a migration/debug compatibility source. */
class AtlasProviderImageIndex(
    private val file: File,
    private val roomStore: ProviderArtworkRoomStore? = null,
) {
    private val identity = file.absoluteFile.toPath().normalize().toString()
    private val lock = locks.computeIfAbsent(identity) { Mutex() }

    fun register(token: String?, rawUrl: String?) = registerAll(listOf(token to rawUrl))
    fun registerAll(entries: Iterable<Pair<String?, String?>>) = runBlocking { registerAllAsync(entries) }
    fun resolve(token: String?): String? = runBlocking { resolveAsync(token) }
    fun clear() = runBlocking { clearAsync() }

    suspend fun registerAllAsync(entries: Iterable<Pair<String?, String?>>): Unit = withContext(Dispatchers.IO) {
        val safe = entries.mapNotNull { (token, address) -> validatedArtworkEntry(token, address) }.toMap()
        if (safe.isEmpty()) return@withContext
        lock.withLock {
            if (roomStore != null) {
                roomStore.register(safe)
            } else {
                val next = loadRaw().toMutableMap().apply { putAll(safe) }
                write(next)
            }
        }
    }

    suspend fun resolveAsync(token: String?): String? = withContext(Dispatchers.IO) {
        val safe = token?.takeIf(::isProviderImageToken) ?: return@withContext null
        lock.withLock {
            if (roomStore != null) {
                var address = roomStore.resolve(safe)
                if (address == null) {
                    importLegacyIfNeeded()
                    address = roomStore.resolve(safe)
                }
                address?.takeIf { validatedArtworkEntry(safe, it) != null }
            } else loadRaw()[safe]
        }
    }

    suspend fun clearAsync(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            roomStore?.clear()
            write(emptyMap())
        }
    }

    private suspend fun importLegacyIfNeeded() {
        if (roomStore?.needsLegacyImport() == true) roomStore.importLegacy(loadRaw())
    }

    private fun loadRaw(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return emptyMap()
        val stored = json.fromJson(text, ProviderImageIndexState::class.java) ?: error("Invalid artwork index")
        return stored.entries.mapNotNull { (token, address) -> validatedArtworkEntry(token, address) }.toMap()
    }

    private fun write(entries: Map<String, String>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.pending")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toJson(ProviderImageIndexState(entries)).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }

    companion object {
        const val FileName = "provider_image_index.json"
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val json = GsonBuilder().disableHtmlEscaping().create()
        fun create(context: Context) = AtlasProviderImageIndex(
            File(context.applicationContext.filesDir, FileName),
            ProviderArtworkRoomStore(AtlasDatabaseProvider.database(context).providerArtworkDao()),
        )
    }
}

/** Immutable token binding prevents an unrelated address replacing a title's poster. */
internal fun validatedArtworkEntry(token: String?, address: String?): Pair<String, String>? {
    val safeToken = token?.takeIf(::isProviderImageToken) ?: return null
    val safeAddress = address?.trim()?.takeIf { it.looksLikeResolvableProviderImageUrl() } ?: return null
    if (safeToken != providerImageToken(safeAddress)) return null
    return safeToken to safeAddress
}

data class ProviderImageIndexState(
    val entries: Map<String, String> = emptyMap(),
)

internal fun isProviderImageToken(token: String): Boolean {
    val hex = token.removePrefix(PROVIDER_IMAGE_TOKEN_PREFIX)
    return token.startsWith(PROVIDER_IMAGE_TOKEN_PREFIX) &&
        hex.length == 64 &&
        hex.all { it in '0'..'9' || it in 'a'..'f' }
}

internal fun String.looksLikeResolvableProviderImageUrl(): Boolean {
    if (length > 4_096) return false
    val uri = runCatching { java.net.URI(this) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && looksLikeProviderPosterUrl()
}

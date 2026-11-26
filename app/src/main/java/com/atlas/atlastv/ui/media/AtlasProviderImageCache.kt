package com.atlas.atlastv.ui.media

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.ConcurrentHashMap

/** Compressed bytes only. Call on IO; all instances for a directory share the same lock. */
class AtlasProviderImageCache(
    private val rootDir: File,
    private val maxBytes: Long = DefaultMaxImageBytes,
    private val maxTotalBytes: Long = DefaultMaxTotalBytes,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private class DirectoryState {
        val lock = Any()
        var maintainedAt: Long? = null
        var policy: Pair<Long, Long>? = null
    }
    private val state = directories.computeIfAbsent(rootDir.absoluteFile.toPath().normalize().toString()) { DirectoryState() }
    private val lock = state.lock
    init { require(maxBytes in 1 until Int.MAX_VALUE.toLong() && maxTotalBytes > 0) }

    fun read(token: String?): ByteArray? = synchronized(lock) {
        val file = fileForToken(token) ?: return@synchronized null
        runCatching {
            // A read must not scan/stat/sort the entire cache before each poster or HTTP miss.
            // Share startup/periodic maintenance across the loader's short-lived cache instances.
            val now = clockNanos()
            val prior = state.maintainedAt
            if (prior == null || state.policy != (maxBytes to maxTotalBytes) || now - prior >= ReadMaintenanceIntervalNanos) {
                trim()
                maintained(now)
            }
            if (!safeFile(file) || file.length() <= 0 || file.length() > maxBytes) return@runCatching null
            val bytes = file.inputStream().use { readArtworkBytes(it, maxBytes.toInt()) }
            if (bytes != null) file.setLastModified(System.currentTimeMillis())
            bytes
        }.getOrNull()
    }

    fun write(token: String?, bytes: ByteArray): Boolean = synchronized(lock) {
        if (bytes.isEmpty() || bytes.size.toLong() > minOf(maxBytes, maxTotalBytes)) return@synchronized false
        val file = fileForToken(token) ?: return@synchronized false
        val pending = File(rootDir, "${file.name}.pending")
        if (Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(pending.toPath())) return@synchronized false
        val written = try {
            if (!rootDir.isDirectory && !rootDir.mkdirs()) return@synchronized false
            FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            file.setLastModified(System.currentTimeMillis())
            true
        } catch (_: Exception) { false }
        finally { pending.delete() }
        // Every write still enforces the full disk budget immediately, including failed writes.
        runCatching {
            trim(protected = file.takeIf { safeFile(it) && it.length() in 1..minOf(maxBytes, maxTotalBytes) })
            maintained(clockNanos())
            written
        }.getOrDefault(false)
    }

    fun remove(token: String?) = synchronized(lock) { fileForToken(token)?.takeIf(::safeFile)?.delete(); Unit }
    fun fileNameForToken(token: String?): String? = token?.takeIf(::isProviderImageToken)
        ?.removePrefix(PROVIDER_IMAGE_TOKEN_PREFIX)?.let { "$it.img" }
    private fun fileForToken(token: String?): File? = fileNameForToken(token)?.let { File(rootDir, it) }
    private fun safeFile(file: File): Boolean = !Files.isSymbolicLink(file.toPath()) && file.isFile
    private fun maintained(now: Long) { state.maintainedAt = now; state.policy = maxBytes to maxTotalBytes }

    /** Never touch unrelated files or follow a symbolic link outside this cache directory. */
    private fun trim(protected: File? = null) {
        val entries = rootDir.listFiles().orEmpty().filter { it.name.matches(CacheFilePattern) && safeFile(it) }
        entries.filter { it.name.endsWith(".pending") || it.length() !in 1..maxBytes }.forEach { it.delete() }
        val valid = entries.filter { it.name.endsWith(".img") && it.exists() }
        var total = valid.sumOf { it.length() }
        for (file in valid.sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            if (file == protected) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    companion object {
        const val DirectoryName = "provider-image-cache"
        const val DefaultMaxImageBytes = 8L * 1024L * 1024L
        const val DefaultMaxTotalBytes = 128L * 1024L * 1024L
        private val CacheFilePattern = Regex("[0-9a-f]{64}\\.img(?:\\.pending)?")
        private const val ReadMaintenanceIntervalNanos = 30_000_000_000L
        private val directories = ConcurrentHashMap<String, DirectoryState>()
    }
}

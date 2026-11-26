package com.atlas.atlastv.ui.livetv

import java.io.File

class LiveTvCachedCatalogStore(
    filesDir: File,
) {
    private val cacheFile = File(filesDir, FileName)

    fun exists(): Boolean = cacheFile.exists()

    fun read(): String? = if (cacheFile.exists()) cacheFile.readText(Charsets.UTF_8) else null

    fun write(json: String) {
        cacheFile.writeText(json, Charsets.UTF_8)
    }

    fun cacheAgeMs(nowEpochMs: Long = System.currentTimeMillis()): Long? =
        if (cacheFile.exists()) (nowEpochMs - cacheFile.lastModified()).coerceAtLeast(0L) else null

    companion object {
        const val FileName = "debug_catalog_summary.json"
    }
}

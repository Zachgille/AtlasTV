package com.atlas.atlastv.debug

import android.content.Context
import java.io.File

class DebugCommandFileStore(
    private val context: Context,
) {
    private val filesDir: File
        get() = context.filesDir

    fun writeResult(result: DebugCommandResult) {
        write(DebugCommandFiles.CommandResult, DebugCommandJson.resultToJson(result))
    }

    fun writeState(snapshot: DebugStateSnapshot) {
        write(DebugCommandFiles.State, DebugCommandJson.stateToJson(snapshot))
    }

    fun writeCatalog(snapshot: DebugCatalogSnapshot) {
        write(DebugCommandFiles.CatalogSummary, DebugCommandJson.catalogToJson(snapshot))
    }

    fun writeLastError(error: DebugLastError) {
        write(DebugCommandFiles.LastError, DebugCommandJson.errorToJson(error))
    }

    fun writeLiveTvState(dump: DebugLiveTvStateDump) {
        write(DebugCommandFiles.LiveTvState, DebugCommandJson.liveTvStateToJson(dump))
    }

    fun writeMoviesState(dump: DebugMoviesStateDump) {
        write(DebugCommandFiles.MoviesState, DebugCommandJson.moviesStateToJson(dump))
    }

    fun writeWatchProgressState(dump: DebugWatchProgressStateDump) {
        write(DebugCommandFiles.WatchProgressState, DebugCommandJson.watchProgressStateToJson(dump))
    }

    fun writeSafeCatalogCacheSnapshot(snapshot: DebugSafeCatalogCacheSnapshot) {
        write(DebugCommandFiles.SafeCatalogCacheSnapshot, DebugCommandJson.safeCatalogCacheSnapshotToJson(snapshot))
    }

    fun writeCatalogAudit(audit: DebugCatalogCoverageAudit) {
        write(DebugCommandFiles.CatalogAudit, DebugCommandJson.catalogAuditToJson(audit))
    }

    fun writeSearchReachability(report: DebugSearchReachabilityReport) {
        write(DebugCommandFiles.SearchReachability, DebugCommandJson.searchReachabilityToJson(report))
    }

    fun readCatalog(): DebugCatalogSnapshot? =
        read(DebugCommandFiles.CatalogSummary)?.let { json ->
            runCatching { DebugCommandJson.gson.fromJson(json, DebugCatalogSnapshot::class.java) }.getOrNull()
        }

    fun readSafeCatalogCacheSnapshot(): DebugSafeCatalogCacheSnapshot? =
        read(DebugCommandFiles.SafeCatalogCacheSnapshot)?.let { json ->
            runCatching { DebugCommandJson.gson.fromJson(json, DebugSafeCatalogCacheSnapshot::class.java) }.getOrNull()
        }

    fun readState(): DebugStateSnapshot? =
        read(DebugCommandFiles.State)?.let { json ->
            runCatching { DebugCommandJson.gson.fromJson(json, DebugStateSnapshot::class.java) }.getOrNull()
        }

    fun cacheAgeMs(nowEpochMs: Long): Long? {
        val file = File(filesDir, DebugCommandFiles.CatalogSummary)
        if (!file.exists()) return null
        return (nowEpochMs - file.lastModified()).coerceAtLeast(0L)
    }

    fun clearDebugCache() {
        listOf(
            DebugCommandFiles.CommandResult,
            DebugCommandFiles.State,
            DebugCommandFiles.CatalogSummary,
            DebugCommandFiles.LastError,
            DebugCommandFiles.LiveTvState,
            DebugCommandFiles.MoviesState,
            DebugCommandFiles.WatchProgressState,
            DebugCommandFiles.CatalogAudit,
            DebugCommandFiles.SearchReachability,
        ).forEach { name -> File(filesDir, name).delete() }
    }

    fun clearSafeCatalogCacheSnapshot() {
        File(filesDir, DebugCommandFiles.SafeCatalogCacheSnapshot).delete()
    }

    private fun write(name: String, text: String) {
        File(filesDir, name).writeText(DebugCommandSanitizer.sanitizeText(text), Charsets.UTF_8)
    }

    private fun read(name: String): String? {
        val file = File(filesDir, name)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }
}

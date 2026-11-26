package com.atlas.atlastv.debug

import com.google.gson.Gson
import com.google.gson.GsonBuilder

object DebugCommandJson {
    val gson: Gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    fun resultToJson(result: DebugCommandResult): String =
        gson.toJson(result)

    fun catalogToJson(snapshot: DebugCatalogSnapshot): String =
        gson.toJson(snapshot)

    fun stateToJson(snapshot: DebugStateSnapshot): String =
        gson.toJson(snapshot)

    fun errorToJson(error: DebugLastError): String =
        gson.toJson(error)

    fun liveTvStateToJson(dump: DebugLiveTvStateDump): String =
        gson.toJson(dump)

    fun moviesStateToJson(dump: DebugMoviesStateDump): String =
        gson.toJson(dump)

    fun watchProgressStateToJson(dump: DebugWatchProgressStateDump): String =
        gson.toJson(dump)

    fun safeCatalogCacheSnapshotToJson(snapshot: DebugSafeCatalogCacheSnapshot): String =
        gson.toJson(snapshot)

    fun catalogCacheSnapshotStateToJson(state: DebugCatalogCacheSnapshotState): String =
        gson.toJson(state)

    fun catalogAuditToJson(audit: DebugCatalogCoverageAudit): String =
        gson.toJson(audit)

    fun searchReachabilityToJson(report: DebugSearchReachabilityReport): String =
        gson.toJson(report)
}

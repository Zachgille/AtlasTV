package com.atlas.atlastv.data.catalog

import com.atlas.atlastv.ui.settings.ProviderSyncStatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Scheduling policy is independent of Android, credentials and transport. */
object ScheduledCatalogRefresh {
    const val IntervalMs = 24L * 60 * 60 * 1000
    const val MaxRetries = 2
    fun due(lastSuccess: Long?, now: Long): Boolean =
        lastSuccess == null || lastSuccess > now || now - lastSuccess >= IntervalMs

    fun retry(attempt: Int, ran: Boolean, status: ProviderSyncStatusSnapshot, statusSaved: Boolean = true): Boolean {
        if (attempt >= MaxRetries) return false
        if (!ran || !statusSaved) return true // Busy or unavailable storage: check again after backoff.
        return status.status == CatalogSyncStatus.Failure && status.errorCategory in setOf(
            "DnsFailure", "Timeout", "ConnectionRefused", "Non2xxStatus", "EmptyResponse",
            "MalformedJson", "MappingFailure", "UnknownError", "SyncTimeout", "SyncInterrupted",
            "RefusedEmptyCatalog")
    }
}

data class CatalogRefreshSignal(val revision: Long = 0, val completedRevision: Long = 0)
object CatalogRefreshSignals {
    private val updates = MutableStateFlow(CatalogRefreshSignal())
    val changes = updates.asStateFlow()
    fun changed(finished: Boolean) { updates.update {
        CatalogRefreshSignal(it.revision + 1, if (finished) it.revision + 1 else it.completedRevision)
    } }
}

package com.atlas.atlastv.data.catalog

import com.atlas.atlastv.ui.settings.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScheduledCatalogRefreshTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun onlyMissingOldOrInvalidFutureSuccessIsDue() {
        val now = ScheduledCatalogRefresh.IntervalMs * 3
        assertTrue(ScheduledCatalogRefresh.due(null, now))
        assertTrue(ScheduledCatalogRefresh.due(now - ScheduledCatalogRefresh.IntervalMs, now))
        assertFalse(ScheduledCatalogRefresh.due(now - ScheduledCatalogRefresh.IntervalMs + 1, now))
        assertFalse(ScheduledCatalogRefresh.due(now, now))
        assertTrue(ScheduledCatalogRefresh.due(now + 1, now))
    }
    @Test fun retriesAreBoundedAndPermanentConfigurationErrorsWaitForTheNextPeriod() {
        for (category in listOf("DnsFailure", "Timeout", "ConnectionRefused", "Non2xxStatus", "SyncTimeout", "RefusedEmptyCatalog")) {
            val status = ProviderSyncStatusSnapshot(CatalogSyncStatus.Failure, errorCategory = category)
            assertTrue(ScheduledCatalogRefresh.retry(0, true, status))
            assertTrue(ScheduledCatalogRefresh.retry(1, true, status))
            assertFalse(ScheduledCatalogRefresh.retry(2, true, status))
        }
        for (category in listOf("InvalidConfig", "InvalidUrl", "CleartextBlocked", "TlsFailure"))
            assertFalse(ScheduledCatalogRefresh.retry(0, true, ProviderSyncStatusSnapshot(CatalogSyncStatus.Failure, errorCategory = category)))
        assertFalse(ScheduledCatalogRefresh.retry(0, true, ProviderSyncStatusSnapshot(CatalogSyncStatus.Success)))
        assertTrue(ScheduledCatalogRefresh.retry(0, true, ProviderSyncStatusSnapshot(CatalogSyncStatus.Success), statusSaved = false))
        assertFalse(ScheduledCatalogRefresh.retry(2, true, ProviderSyncStatusSnapshot(CatalogSyncStatus.Success), statusSaved = false))
        assertTrue(ScheduledCatalogRefresh.retry(0, false, ProviderSyncStatusSnapshot()))
        assertFalse(ScheduledCatalogRefresh.retry(2, false, ProviderSyncStatusSnapshot()))
    }
    private fun store(name: String) = ProviderSettingsStore(File(temp.root, name)).apply {
        saveSettings(ProviderSettingsSnapshot("http://example.invalid", "opaque-user", "opaque password"))
    }
    private fun holder(store: ProviderSettingsStore, scope: CoroutineScope, gate: CatalogRefreshGate,
        sync: suspend () -> CatalogImportResult) = ProviderSettingsStateHolder(store, scope,
        { ProviderConnectionResult(true) }, { _, _ -> sync() }, refreshGate = gate)
    private fun result() = CatalogImportResult(CatalogSyncStatus.Success, 1, 2, 7, 8, 0, 0)

    @Test fun independentManualAndWorkerHoldersCannotRunTogetherAndProviderEditsWait() = runBlocking {
        val gate = CatalogRefreshGate(); val store = store("gate.json")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val first = holder(store, this, gate) { calls++; entered.complete(Unit); release.await(); result() }
        // A second holder exists before the first publishes Loading, as can happen on another entry point.
        val second = holder(store, this, gate) { calls++; result() }
        val running = async { first.syncMoviesAndShowsNow() }; entered.await()
        assertFalse(second.syncMoviesAndShowsNow()); assertEquals(1, calls)
        second.updateBaseUrl("http://other.invalid"); second.updateUsername("different"); second.updatePassword("new password")
        val edit = async { second.saveProviderSettingsNow() }; yield()
        assertFalse(edit.isCompleted); assertEquals("opaque-user", store.load().settings.username)
        release.complete(Unit); assertTrue(running.await()); edit.await()
        assertEquals("different", store.load().settings.username)
        assertEquals(7, store.load().syncStatus.moviesImported)
    }
    @Test fun cancellationPreservesLastGoodStatusAndReleasesTheGateForRetry() = runBlocking {
        val gate = CatalogRefreshGate(); val store = store("cancel.json")
        store.saveSyncStatus(ProviderSyncStatusSnapshot(CatalogSyncStatus.Success, lastSuccessfulSyncAtMs = 5, moviesImported = 7, seriesImported = 8))
        val entered = CompletableDeferred<Unit>()
        val first = holder(store, this, gate) { entered.complete(Unit); awaitCancellation() }
        val running = launch { first.syncMoviesAndShowsNow() }; entered.await(); running.cancelAndJoin()
        val status = store.load().syncStatus
        assertEquals("SyncInterrupted", status.errorCategory); assertEquals(5L, status.lastSuccessfulSyncAtMs)
        assertEquals(7, status.moviesImported); assertEquals(8, status.seriesImported)
        assertTrue(holder(store, this, gate) { result() }.syncMoviesAndShowsNow())
    }
    @Test fun unexpectedTransportExceptionsBecomeSafeRetryableFailures() = runBlocking {
        val store = store("failure.json"); val gate = CatalogRefreshGate()
        val first = holder(store, this, gate) { throw IllegalStateException("sensitive fixture diagnostics") }
        assertTrue(first.syncMoviesAndShowsNow())
        assertEquals("UnknownError", first.state.syncStatus.errorCategory)
        assertFalse(first.state.actionMessage.orEmpty().contains("sensitive"))
        assertTrue(holder(store, this, gate) { result() }.syncMoviesAndShowsNow())
    }
    @Test fun stageUpdatesCannotEraseAnUnobservedCompletionSignal() {
        CatalogRefreshSignals.changed(true)
        val completed = CatalogRefreshSignals.changes.value.completedRevision
        CatalogRefreshSignals.changed(false)
        assertEquals(completed, CatalogRefreshSignals.changes.value.completedRevision)
        assertTrue(CatalogRefreshSignals.changes.value.revision > completed)
    }
}

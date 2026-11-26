package com.atlas.atlastv.data.catalog

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.atlas.atlastv.ui.settings.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Fake transport/private cache fixtures. Never forces the real provider or alters personal catalog data. */
class ScheduledCatalogRefreshConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun actualWorkerSkipsFreshDataAndRetriesFailuresWithoutLosingLastGoodCounts() = runBlocking(Dispatchers.IO) {
        val file = File(context.cacheDir, "scheduled-refresh-${UUID.randomUUID()}.json")
        val store = ProviderSettingsStore(file)
        store.saveSettings(ProviderSettingsSnapshot("http://example.invalid", "opaque user", "opaque password"))
        val now = System.currentTimeMillis(); var calls = 0
        val gate = CatalogRefreshGate()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker =
                CatalogRefreshWorker(appContext, parameters) { _, scope -> ProviderSettingsStateHolder(store, scope,
                    { ProviderConnectionResult(true) }, { _, stage ->
                        calls++; stage(com.atlas.atlastv.xtream.network.XtreamLoadStage.ImportingCatalog)
                        CatalogImportResult(CatalogSyncStatus.Failure, now, now + 1, 0, 0, 0, 0, "DnsFailure")
                    }, refreshGate = gate)
                }
        }
        fun worker(attempt: Int) = TestListenableWorkerBuilder<CatalogRefreshWorker>(context)
            .setWorkerFactory(factory).setRunAttemptCount(attempt).build()
        try {
            store.saveSyncStatus(ProviderSyncStatusSnapshot(CatalogSyncStatus.Success, lastSuccessfulSyncAtMs = now, moviesImported = 12, seriesImported = 9))
            assertEquals(ListenableWorker.Result.success(), worker(1).doWork()); assertEquals(0, calls)
            store.saveSyncStatus(store.load().syncStatus.copy(lastSuccessfulSyncAtMs = 1))
            assertEquals(ListenableWorker.Result.retry(), worker(0).doWork())
            assertEquals(ListenableWorker.Result.retry(), worker(1).doWork())
            assertEquals(ListenableWorker.Result.success(), worker(2).doWork()); assertEquals(3, calls)
            val saved = store.load().syncStatus
            assertEquals(12, saved.moviesImported); assertEquals(9, saved.seriesImported)
            assertEquals(1L, saved.lastSuccessfulSyncAtMs); assertEquals("DnsFailure", saved.errorCategory)
            store.clear()
            assertEquals(ListenableWorker.Result.success(), worker(0).doWork()); assertEquals(3, calls)
        } finally { store.clear(); file.delete(); File(file.parentFile, "${file.name}.lastgood").delete() }
    }

    @Test fun dailyWorkIsUniqueNetworkConstrainedDelayedAndHasNoCredentialsInItsInput() = runBlocking(Dispatchers.IO) {
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(context.applicationContext is Configuration.Provider)
        val scheduler = WorkManager.getInstance(context)
        CatalogRefreshScheduler.ensure(context).result.get(15, TimeUnit.SECONDS)
        val first = scheduler.getWorkInfosForUniqueWork(CatalogRefreshScheduler.WorkName).get(15, TimeUnit.SECONDS).single()
        CatalogRefreshScheduler.ensure(context).result.get(15, TimeUnit.SECONDS)
        val second = scheduler.getWorkInfosForUniqueWork(CatalogRefreshScheduler.WorkName).get(15, TimeUnit.SECONDS).single()
        assertEquals(first.id, second.id); assertEquals(WorkInfo.State.ENQUEUED, second.state)
        assertEquals(NetworkType.CONNECTED, second.constraints.requiredNetworkType)
        assertTrue(second.nextScheduleTimeMillis > System.currentTimeMillis() + TimeUnit.HOURS.toMillis(23))
        val request = CatalogRefreshScheduler.request()
        assertTrue(request.workSpec.input.keyValueMap.isEmpty())
        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(24), request.workSpec.initialDelay)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
    }
}

package com.atlas.atlastv.data.catalog

import android.content.Context
import androidx.work.*
import com.atlas.atlastv.ui.settings.ProviderSettingsStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CatalogRefreshWorker internal constructor(context: Context, parameters: WorkerParameters,
    private val holderFactory: (Context, CoroutineScope) -> ProviderSettingsStateHolder) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, ProviderSettingsStateHolder::create)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        var attempted = false
        try {
            // Read the current saved account at execution, never put credentials in WorkManager data.
            val holder = holderFactory(applicationContext, CoroutineScope(coroutineContext))
            // Each daily tick refreshes. Only retries skip data refreshed manually during backoff;
            // skipping a regular tick could otherwise leave a 23-hour-old catalog untouched for another day.
            if (holder.savedCredentialsOrNull() == null || (runAttemptCount > 0 &&
                !ScheduledCatalogRefresh.due(holder.state.syncStatus.lastSuccessfulSyncAtMs, System.currentTimeMillis())))
                return@withContext Result.success()
            attempted = true
            val ran = holder.syncMoviesAndShowsNow()
            if (ScheduledCatalogRefresh.retry(runAttemptCount, ran, holder.state.syncStatus, !holder.state.syncStatusSaveFailed)) Result.retry()
            else Result.success() // A bad account/config waits for the next daily run, without a retry loop.
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < ScheduledCatalogRefresh.MaxRetries) Result.retry() else Result.success() }
        finally { if (attempted) CatalogRefreshSignals.changed(finished = true) }
    }
}

object CatalogRefreshScheduler {
    const val WorkName = "daily-movies-shows-refresh-v1"
    fun request(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(24, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
        .addTag(WorkName)
        .build()

    fun ensure(context: Context): androidx.work.Operation = WorkManager.getInstance(context.applicationContext)
        .enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.KEEP, request())
}

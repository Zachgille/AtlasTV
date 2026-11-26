package com.atlas.atlastv.ui.settings

import com.atlas.atlastv.data.catalog.CatalogImportResult
import com.atlas.atlastv.data.catalog.CatalogSyncTimings
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.xtream.network.XtreamLoadStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProviderSettingsStateHolderTest {
    @Test
    fun testConnectionReturnsSafeStatus() = runBlocking {
        val holder = holder(connection = { ProviderConnectionResult(success = true) })
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.testConnectionNow()

        assertEquals("success", holder.state.connectionStatus)
        assertFalse(holder.state.actionMessage.orEmpty().contains("user"))
        assertFalse(holder.state.actionMessage.orEmpty().contains("pass"))
    }

    @Test
    fun failedSyncShowsRedactedErrorCategory() = runBlocking {
        val holder = holder(
            sync = {
                CatalogImportResult(
                    status = CatalogSyncStatus.Failure,
                    startedAtMs = 10L,
                    completedAtMs = 20L,
                    moviesImported = 0,
                    seriesImported = 0,
                    excludedCategories = 0,
                    excludedItems = 0,
                    errorCategory = "DnsFailure",
                )
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.syncMoviesAndShowsNow()

        assertEquals(CatalogSyncStatus.Failure, holder.state.syncStatus.status)
        assertEquals("DnsFailure", holder.state.syncStatus.errorCategory)
        assertFalse(holder.state.actionMessage.orEmpty().contains("provider.local"))
        assertFalse(holder.state.actionMessage.orEmpty().contains("user"))
        assertFalse(holder.state.actionMessage.orEmpty().contains("pass"))
    }

    @Test
    fun manualSyncUpdatesCountsAndPersistsStatus() = runBlocking {
        val file = tempFile()
        val holder = holder(
            file = file,
            sync = {
                CatalogImportResult(
                    status = CatalogSyncStatus.Success,
                    startedAtMs = 100L,
                    completedAtMs = 140L,
                    moviesImported = 12,
                    seriesImported = 9,
                    excludedCategories = 1,
                    excludedItems = 2,
                )
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.saveProviderSettingsNow()
        holder.syncMoviesAndShowsNow()

        val persisted = ProviderSettingsStore(file).load()
        assertEquals(CatalogSyncStatus.Success, persisted.syncStatus.status)
        assertEquals(12, persisted.syncStatus.moviesImported)
        assertEquals(9, persisted.syncStatus.seriesImported)
        assertEquals(3, persisted.syncStatus.excludedTotal)
        assertEquals(140L, persisted.syncStatus.lastSuccessfulSyncAtMs)
    }

    @Test
    fun failedSyncPreservesLastSuccessfulSyncTimeAndCounts() = runBlocking {
        val file = tempFile()
        val store = ProviderSettingsStore(file)
        store.saveSyncStatus(
            ProviderSyncStatusSnapshot(
                status = CatalogSyncStatus.Success,
                lastCompletedAtMs = 200L,
                lastSuccessfulSyncAtMs = 200L,
                moviesImported = 7,
                seriesImported = 8,
                excludedCategories = 1,
                excludedItems = 1,
            ),
        )
        val holder = holder(
            file = file,
            sync = {
                CatalogImportResult(
                    status = CatalogSyncStatus.Failure,
                    startedAtMs = 300L,
                    completedAtMs = 320L,
                    moviesImported = 0,
                    seriesImported = 0,
                    excludedCategories = 0,
                    excludedItems = 0,
                    errorCategory = "DnsFailure",
                )
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.syncMoviesAndShowsNow()

        val status = ProviderSettingsStore(file).load().syncStatus
        assertEquals(CatalogSyncStatus.Failure, status.status)
        assertEquals(200L, status.lastSuccessfulSyncAtMs)
        assertEquals(320L, status.lastFailedSyncAtMs)
        assertEquals(7, status.moviesImported)
        assertEquals(8, status.seriesImported)
        assertEquals(2, status.excludedTotal)
    }

    @Test
    fun cachedStartupDoesNotStartSyncOnHolderCreation() {
        var syncCalls = 0
        holder(sync = {
            syncCalls++
            CatalogImportResult(CatalogSyncStatus.Success, 1L, 2L, 0, 0, 0, 0)
        })

        assertEquals(0, syncCalls)
    }

    @Test
    fun duplicateRefreshIsPreventedWhileSyncing() = runBlocking {
        val file = tempFile()
        ProviderSettingsStore(file).saveSyncStatus(
            ProviderSyncStatusSnapshot(
                status = CatalogSyncStatus.Loading,
                lastStartedAtMs = System.currentTimeMillis(),
                moviesImported = 3,
                seriesImported = 4,
            ),
        )
        var syncCalls = 0
        val holder = holder(
            file = file,
            sync = {
                syncCalls++
                CatalogImportResult(CatalogSyncStatus.Success, 1L, 2L, 1, 1, 0, 0)
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.syncMoviesAndShowsNow()

        assertEquals(0, syncCalls)
        assertEquals(CatalogSyncStatus.Loading, holder.state.syncStatus.status)
        assertEquals("Movies and Shows sync is already running.", holder.state.actionMessage)
    }

    @Test
    fun asyncRefreshShowsLoadingPreventsDuplicateAndUpdatesCounts() = runBlocking {
        val syncStarted = CompletableDeferred<Unit>()
        val allowSyncToFinish = CompletableDeferred<Unit>()
        var syncCalls = 0
        val holder = holder(
            sync = {
                syncCalls++
                syncStarted.complete(Unit)
                allowSyncToFinish.await()
                CatalogImportResult(
                    status = CatalogSyncStatus.Success,
                    startedAtMs = 400L,
                    completedAtMs = 450L,
                    moviesImported = 22,
                    seriesImported = 11,
                    excludedCategories = 2,
                    excludedItems = 3,
                )
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.syncMoviesAndShowsAsync()
        syncStarted.await()
        holder.syncMoviesAndShowsNow()
        allowSyncToFinish.complete(Unit)
        eventually { holder.state.syncStatus.status == CatalogSyncStatus.Success }

        assertEquals(1, syncCalls)
        assertEquals(22, holder.state.syncStatus.moviesImported)
        assertEquals(11, holder.state.syncStatus.seriesImported)
        assertEquals(5, holder.state.syncStatus.excludedTotal)
        assertEquals("Sync complete: Movies and Shows synced.", holder.state.actionMessage)
    }

    @Test
    fun syncReportsStageAndPersistsSafeTimingBreakdown() = runBlocking {
        val file = tempFile()
        val syncStarted = CompletableDeferred<Unit>()
        val allowSyncToFinish = CompletableDeferred<Unit>()
        val holder = holder(
            file = file,
            syncWithStage = { onStage ->
                onStage(XtreamLoadStage.VodStreams)
                syncStarted.complete(Unit)
                allowSyncToFinish.await()
                CatalogImportResult(
                    status = CatalogSyncStatus.Success,
                    startedAtMs = 100L,
                    completedAtMs = 180L,
                    moviesImported = 5,
                    seriesImported = 6,
                    excludedCategories = 1,
                    excludedItems = 0,
                    timings = CatalogSyncTimings(
                        totalDurationMs = 80L,
                        vodCategoriesDurationMs = 10L,
                        vodStreamsDurationMs = 20L,
                        seriesCategoriesDurationMs = 15L,
                        seriesDurationMs = 25L,
                        filteringDurationMs = 4L,
                        roomWriteDurationMs = 6L,
                        vodCategoryCount = 2,
                        vodStreamCount = 5,
                        seriesCategoryCount = 3,
                        seriesCount = 6,
                    ),
                )
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.syncMoviesAndShowsAsync()
        syncStarted.await()
        assertEquals("Loading VOD streams", holder.state.syncStatus.currentStage)
        allowSyncToFinish.complete(Unit)
        eventually { holder.state.syncStatus.status == CatalogSyncStatus.Success }

        val persisted = ProviderSettingsStore(file).load().syncStatus
        assertEquals(80L, persisted.totalDurationMs)
        assertEquals(20L, persisted.vodStreamsDurationMs)
        assertEquals(25L, persisted.seriesDurationMs)
        assertEquals(6L, persisted.roomWriteDurationMs)
        assertFalse(persisted.errorCategory.orEmpty().contains("provider.local"))
        assertFalse(persisted.currentStage.orEmpty().contains("provider.local"))
    }

    @Test
    fun syncTimeoutFailsWithRedactedStatus() = runBlocking {
        val holder = holder(
            syncTimeoutMs = 20L,
            syncWithStage = { onStage ->
                onStage(XtreamLoadStage.Series)
                delay(200L)
                CatalogImportResult(CatalogSyncStatus.Success, 1L, 2L, 1, 1, 0, 0)
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.syncMoviesAndShowsNow()

        assertEquals(CatalogSyncStatus.Failure, holder.state.syncStatus.status)
        assertEquals("SyncTimeout", holder.state.syncStatus.errorCategory)
        assertFalse(holder.state.actionMessage.orEmpty().contains("provider.local"))
        assertFalse(holder.state.actionMessage.orEmpty().contains("user"))
        assertFalse(holder.state.actionMessage.orEmpty().contains("pass"))
    }

    @Test
    fun syncTimeoutPersistsWhenImportIgnoresCancellation() = runBlocking {
        val file = tempFile()
        val syncFinished = CompletableDeferred<Unit>()
        val holder = holder(
            file = file,
            scope = CoroutineScope(Dispatchers.Default),
            syncTimeoutMs = 30L,
            syncWithStage = { onStage ->
                onStage(XtreamLoadStage.ImportingCatalog)
                withContext(NonCancellable) {
                    delay(120L)
                }
                syncFinished.complete(Unit)
                CatalogImportResult(CatalogSyncStatus.Success, 1L, 2L, 1, 1, 0, 0)
            },
        )
        holder.updateBaseUrl("http://provider.local")
        holder.updateUsername("user")
        holder.updatePassword("pass")

        holder.syncMoviesAndShowsAsync()

        eventually {
            ProviderSettingsStore(file).load().syncStatus.errorCategory == "SyncTimeout"
        }
        syncFinished.await()
        delay(20L)

        val status = ProviderSettingsStore(file).load().syncStatus
        assertEquals(CatalogSyncStatus.Failure, status.status)
        assertEquals("SyncTimeout", status.errorCategory)
        assertFalse(status.currentStage.orEmpty().contains("provider.local"))
    }

    @Test
    fun stalePersistedLoadingStatusBecomesTimeoutOnStartup() {
        val file = tempFile()
        ProviderSettingsStore(file).saveSyncStatus(
            ProviderSyncStatusSnapshot(
                status = CatalogSyncStatus.Loading,
                lastStartedAtMs = 1L,
                lastSuccessfulSyncAtMs = 2L,
                moviesImported = 31,
                seriesImported = 10,
                currentStage = "Saving catalog",
            ),
        )

        val holder = holder(file = file, syncTimeoutMs = 20L)

        assertEquals(CatalogSyncStatus.Failure, holder.state.syncStatus.status)
        assertEquals("SyncTimeout", holder.state.syncStatus.errorCategory)
        assertEquals(31, holder.state.syncStatus.moviesImported)
        assertEquals(10, holder.state.syncStatus.seriesImported)
        assertFalse(holder.state.syncStatus.currentStage.orEmpty().contains("Saving catalog"))
        assertEquals(CatalogSyncStatus.Failure, ProviderSettingsStore(file).load().syncStatus.status)
    }

    @Test
    fun savedCredentialsAreNotRehydratedIntoNormalUiInputs() {
        val file = tempFile()
        ProviderSettingsStore(file).saveSettings(
            ProviderSettingsSnapshot(
                baseUrl = "http://provider.local",
                username = "user",
                password = "pass",
            ),
        )

        val holder = holder(file = file)

        assertEquals("", holder.state.baseUrlInput)
        assertEquals("", holder.state.usernameInput)
        assertEquals("", holder.state.passwordInput)
        assertEquals("http://REDACTED_HOST", holder.state.redactedBaseUrl)
        assertTrue(holder.state.hasSavedCredentials)
    }

    @Test
    fun debugBootstrapFillsEmptyProviderSettings() {
        val file = tempFile()

        val holder = holder(
            file = file,
            bootstrapProviderSettings = ProviderSettingsSnapshot(
                baseUrl = "http://provider.local",
                username = "debug_user",
                password = "debug_pass",
            ),
        )

        val persisted = ProviderSettingsStore(file).load().settings
        assertEquals("http://provider.local", persisted.baseUrl)
        assertEquals("debug_user", persisted.username)
        assertEquals("debug_pass", persisted.password)
        assertTrue(holder.state.hasSavedCredentials)
        assertEquals("http://REDACTED_HOST", holder.state.redactedBaseUrl)
        assertFalse(holder.state.actionMessage.orEmpty().contains("debug_user"))
        assertFalse(holder.state.redactedBaseUrl.orEmpty().contains("provider.local"))
    }

    @Test
    fun debugBootstrapDoesNotOverwriteExistingProviderSettings() {
        val file = tempFile()
        ProviderSettingsStore(file).saveSettings(
            ProviderSettingsSnapshot(
                baseUrl = "http://saved.local",
                username = "saved_user",
                password = "saved_pass",
            ),
        )

        holder(
            file = file,
            bootstrapProviderSettings = ProviderSettingsSnapshot(
                baseUrl = "http://provider.local",
                username = "debug_user",
                password = "debug_pass",
            ),
        )

        val persisted = ProviderSettingsStore(file).load().settings
        assertEquals("http://saved.local", persisted.baseUrl)
        assertEquals("saved_user", persisted.username)
        assertEquals("saved_pass", persisted.password)
    }

    @Test
    fun missingBootstrapLeavesProviderSettingsEmpty() {
        val file = tempFile()

        val holder = holder(file = file, bootstrapProviderSettings = null)

        assertFalse(holder.state.hasSavedCredentials)
        assertEquals(null, ProviderSettingsStore(file).load().settings.credentialsOrNull())
    }

    @Test fun refusedRefreshPreservesAcknowledgedSuccessMetadataAndReopensWithRetry() = runBlocking {
        val file = tempFile(); val store = ProviderSettingsStore(file)
        store.saveSettings(ProviderSettingsSnapshot(baseUrl = "http://example.invalid", username = "fixture", password = "fixture"))
        val previous = ProviderSyncStatusSnapshot(status = CatalogSyncStatus.Success, lastCompletedAtMs = 1000L,
            lastSuccessfulSyncAtMs = 1000L, moviesImported = 12, seriesImported = 9, excludedCategories = 3, excludedItems = 4)
        store.saveSyncStatus(previous)
        val holder = holder(file = file, sync = { CatalogImportResult(CatalogSyncStatus.Failure, 2000L, 3000L, 0, 0, 99, 99,
            com.atlas.atlastv.data.db.CatalogRefreshWriteResult.RefusedEmptyCatalog.name) })
        holder.syncMoviesAndShowsNow()
        val persisted = store.load().syncStatus
        assertEquals(CatalogSyncStatus.Failure, persisted.status); assertEquals(1000L, persisted.lastSuccessfulSyncAtMs)
        assertEquals(12, persisted.moviesImported); assertEquals(9, persisted.seriesImported)
        assertEquals(3, persisted.excludedCategories); assertEquals(4, persisted.excludedItems)
        val reopened = holder(file = file)
        assertEquals(persisted, reopened.state.syncStatus)
        assertEquals(CatalogFreshnessState.Failed, reopened.state.catalogFreshness(4000L).state)
        assertFalse(reopened.state.syncStatus.refreshFailureMessage().contains("fixture"))
    }

    private fun holder(
        file: File = tempFile(),
        connection: suspend () -> ProviderConnectionResult = { ProviderConnectionResult(success = true) },
        sync: suspend () -> CatalogImportResult = {
            CatalogImportResult(CatalogSyncStatus.Success, 1L, 2L, 1, 1, 0, 0)
        },
        syncWithStage: (suspend ((XtreamLoadStage) -> Unit) -> CatalogImportResult)? = null,
        syncTimeoutMs: Long = 5L * 60L * 1000L,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
        bootstrapProviderSettings: ProviderSettingsSnapshot? = null,
    ): ProviderSettingsStateHolder =
        ProviderSettingsStateHolder(
            store = ProviderSettingsStore(file),
            scope = scope,
            testConnection = { connection() },
            syncCatalog = { _, onStage -> syncWithStage?.invoke(onStage) ?: sync() },
            syncTimeoutMs = syncTimeoutMs,
            bootstrapProviderSettings = bootstrapProviderSettings,
        )

    private fun tempFile(): File =
        File.createTempFile("provider-settings-holder", ".json").also {
            it.delete()
            it.deleteOnExit()
        }

    private suspend fun eventually(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            kotlinx.coroutines.delay(10L)
        }
        check(predicate())
    }
}

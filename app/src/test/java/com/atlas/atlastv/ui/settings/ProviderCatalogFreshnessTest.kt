package com.atlas.atlastv.ui.settings

import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogFreshnessTest {
    @Test
    fun freshCatalogDoesNotShowPrompt() {
        val state = uiState(lastSuccess = Now - 1_000L)

        val freshness = state.catalogFreshness(nowMs = Now)

        assertEquals(CatalogFreshnessState.Fresh, freshness.state)
        assertFalse(freshness.showPrompt)
    }

    @Test
    fun staleCatalogShowsRefreshPrompt() {
        val state = uiState(lastSuccess = Now - CatalogFreshnessDefaults.StaleThresholdMs - 1L)

        val freshness = state.catalogFreshness(nowMs = Now)

        assertEquals(CatalogFreshnessState.Stale, freshness.state)
        assertTrue(freshness.showPrompt)
        assertEquals("Refresh Movies/Shows", freshness.actionLabel)
    }

    @Test
    fun neverSyncedWithCredentialsOffersRefresh() {
        val freshness = ProviderSettingsUiState(hasSavedCredentials = true).catalogFreshness(nowMs = Now)

        assertEquals(CatalogFreshnessState.NeverSynced, freshness.state)
        assertTrue(freshness.showPrompt)
        assertEquals("Refresh Movies/Shows", freshness.actionLabel)
    }

    @Test
    fun neverSyncedWithoutCredentialsGuidesToSettings() {
        val freshness = ProviderSettingsUiState(hasSavedCredentials = false).catalogFreshness(nowMs = Now)

        assertEquals(CatalogFreshnessState.NeverSynced, freshness.state)
        assertTrue(freshness.showPrompt)
        assertEquals("Open Settings", freshness.actionLabel)
    }

    @Test
    fun failedRefreshShowsRedactedErrorAndPreservesLastSuccess() {
        val state = ProviderSettingsUiState(
            hasSavedCredentials = true,
            syncStatus = ProviderSyncStatusSnapshot(
                status = CatalogSyncStatus.Failure,
                lastSuccessfulSyncAtMs = Now - 2_000L,
                lastFailedSyncAtMs = Now - 1_000L,
                moviesImported = 12,
                seriesImported = 9,
                errorCategory = "DnsFailure",
            ),
        )

        val freshness = state.catalogFreshness(nowMs = Now)

        assertEquals(CatalogFreshnessState.Failed, freshness.state)
        assertEquals("DnsFailure", freshness.errorCategory)
        assertEquals("Retry Movies/Shows", freshness.actionLabel)
        assertFalse(freshness.message.contains("provider.example"))
    }

    @Test
    fun syncingStatePreventsRefreshActionCopy() {
        val state = uiState(
            status = CatalogSyncStatus.Loading,
            lastSuccess = Now - CatalogFreshnessDefaults.StaleThresholdMs - 1L,
        )

        val freshness = state.catalogFreshness(nowMs = Now)

        assertEquals(CatalogFreshnessState.Syncing, freshness.state)
        assertEquals("Syncing...", freshness.actionLabel)
    }

    @Test fun emptyRefreshFailureIsPlainAndRetryableWithOrWithoutSyncHistory() {
        for (lastSuccess in listOf<Long?>(null, Now - 1000)) {
            val status = ProviderSyncStatusSnapshot(status = CatalogSyncStatus.Failure, lastSuccessfulSyncAtMs = lastSuccess,
                moviesImported = 12, seriesImported = 9,
                errorCategory = com.atlas.atlastv.data.db.CatalogRefreshWriteResult.RefusedEmptyCatalog.name)
            val freshness = ProviderSettingsUiState(hasSavedCredentials = true, syncStatus = status).catalogFreshness(Now)
            assertEquals(CatalogFreshnessState.Failed, freshness.state)
            assertEquals("Retry Movies/Shows", freshness.actionLabel)
            assertEquals(status.refreshFailureMessage(), freshness.message)
            assertTrue(freshness.message.contains("saved library")); assertFalse(freshness.message.contains("RefusedEmptyCatalog"))
            assertEquals(lastSuccess, freshness.lastSyncedAtMs); assertEquals(12, freshness.moviesImported); assertEquals(9, freshness.seriesImported)
        }
    }

    private fun uiState(
        status: CatalogSyncStatus = CatalogSyncStatus.Success,
        lastSuccess: Long,
    ): ProviderSettingsUiState =
        ProviderSettingsUiState(
            hasSavedCredentials = true,
            syncStatus = ProviderSyncStatusSnapshot(
                status = status,
                lastCompletedAtMs = lastSuccess,
                lastSuccessfulSyncAtMs = lastSuccess,
                moviesImported = 4,
                seriesImported = 5,
            ),
        )

    private companion object {
        const val Now = 100_000_000L
    }
}

package com.atlas.atlastv.ui.settings

import com.atlas.atlastv.data.catalog.CatalogImportResult
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.xtream.model.XtreamCredentials

data class ProviderSettingsSnapshot(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val defaultOutputExtension: String? = null,
    val alternateBaseUrls: List<String> = emptyList(),
    val preferredBaseUrl: String? = null,
) {
    fun credentialsOrNull(): XtreamCredentials? {
        val safeBaseUrl = baseUrl.trim()
        val safeUsername = username.trim()
        val safePassword = password
        if (safeBaseUrl.isBlank() || safeUsername.isBlank() || safePassword.isBlank()) return null
        return XtreamCredentials(safeBaseUrl, safeUsername, safePassword, alternateBaseUrls, preferredBaseUrl)
    }
}

data class ProviderSyncStatusSnapshot(
    val status: CatalogSyncStatus = CatalogSyncStatus.Idle,
    val lastStartedAtMs: Long? = null,
    val lastCompletedAtMs: Long? = null,
    val lastSuccessfulSyncAtMs: Long? = null,
    val lastFailedSyncAtMs: Long? = null,
    val moviesImported: Int = 0,
    val seriesImported: Int = 0,
    val excludedCategories: Int = 0,
    val excludedItems: Int = 0,
    val errorCategory: String? = null,
    val currentStage: String? = null,
    val totalDurationMs: Long = 0L,
    val accountInfoDurationMs: Long = 0L,
    val vodCategoriesDurationMs: Long = 0L,
    val vodStreamsDurationMs: Long = 0L,
    val seriesCategoriesDurationMs: Long = 0L,
    val seriesDurationMs: Long = 0L,
    val filteringDurationMs: Long = 0L,
    val roomWriteDurationMs: Long = 0L,
    val vodCategoryCount: Int = 0,
    val vodStreamCount: Int = 0,
    val seriesCategoryCount: Int = 0,
    val seriesCount: Int = 0,
) {
    val excludedTotal: Int = excludedCategories + excludedItems
}

data class ProviderSettingsUiState(
    val baseUrlInput: String = "",
    val usernameInput: String = "",
    val passwordInput: String = "",
    val hasSavedCredentials: Boolean = false,
    val redactedBaseUrl: String? = null,
    val connectionStatus: String = "not_tested",
    val syncStatus: ProviderSyncStatusSnapshot = ProviderSyncStatusSnapshot(),
    val actionMessage: String? = null,
    val providerSaving: Boolean = false,
    val providerSaveFailed: Boolean = false,
    val providerReadFailed: Boolean = false,
    val syncStatusSaveFailed: Boolean = false,
    val backupUrlOne: String = "",
    val backupUrlTwo: String = "",
    val backupEditError: String? = null,
) {
    val isSyncing: Boolean = syncStatus.status == CatalogSyncStatus.Loading
}

enum class CatalogFreshnessState {
    Fresh,
    Stale,
    NeverSynced,
    Syncing,
    Failed,
}

data class CatalogFreshnessUiState(
    val state: CatalogFreshnessState,
    val showPrompt: Boolean,
    val title: String,
    val message: String,
    val actionLabel: String,
    val lastSyncedAtMs: Long?,
    val moviesImported: Int,
    val seriesImported: Int,
    val excludedTotal: Int,
    val errorCategory: String? = null,
)

data class ProviderConnectionResult(
    val success: Boolean,
    val errorCategory: String? = null,
)

fun CatalogImportResult.toProviderSyncStatus(): ProviderSyncStatusSnapshot =
    ProviderSyncStatusSnapshot(
        status = status,
        lastStartedAtMs = startedAtMs,
        lastCompletedAtMs = completedAtMs,
        lastSuccessfulSyncAtMs = completedAtMs.takeIf { status == CatalogSyncStatus.Success },
        lastFailedSyncAtMs = completedAtMs.takeIf { status == CatalogSyncStatus.Failure },
        moviesImported = moviesImported,
        seriesImported = seriesImported,
        excludedCategories = excludedCategories,
        excludedItems = excludedItems,
        errorCategory = errorCategory,
        currentStage = null,
        totalDurationMs = timings.totalDurationMs.takeIf { it > 0L } ?: durationMs,
        accountInfoDurationMs = timings.accountInfoDurationMs,
        vodCategoriesDurationMs = timings.vodCategoriesDurationMs,
        vodStreamsDurationMs = timings.vodStreamsDurationMs,
        seriesCategoriesDurationMs = timings.seriesCategoriesDurationMs,
        seriesDurationMs = timings.seriesDurationMs,
        filteringDurationMs = timings.filteringDurationMs,
        roomWriteDurationMs = timings.roomWriteDurationMs,
        vodCategoryCount = timings.vodCategoryCount,
        vodStreamCount = timings.vodStreamCount,
        seriesCategoryCount = timings.seriesCategoryCount,
        seriesCount = timings.seriesCount,
    )

fun ProviderSyncStatusSnapshot.refreshFailureMessage(): String =
    if (errorCategory == com.atlas.atlastv.data.db.CatalogRefreshWriteResult.RefusedEmptyCatalog.name)
        "Refresh returned no usable titles. Your saved library is still available. Try again."
    else "Couldn’t refresh. Your saved library is still available. Try again."

fun ProviderSettingsUiState.catalogFreshness(
    nowMs: Long = System.currentTimeMillis(),
    staleThresholdMs: Long = CatalogFreshnessDefaults.StaleThresholdMs,
): CatalogFreshnessUiState {
    val status = syncStatus
    val hasCounts = status.moviesImported > 0 || status.seriesImported > 0
    val lastSuccess = status.lastSuccessfulSyncAtMs ?: status.lastCompletedAtMs?.takeIf {
        status.status == CatalogSyncStatus.Success
    }
    return when {
        status.status == CatalogSyncStatus.Loading -> CatalogFreshnessUiState(
            state = CatalogFreshnessState.Syncing,
            showPrompt = true,
            title = "Syncing Movies/Shows",
            message = "Catalog refresh is running in the background.",
            actionLabel = "Syncing...",
            lastSyncedAtMs = lastSuccess,
            moviesImported = status.moviesImported,
            seriesImported = status.seriesImported,
            excludedTotal = status.excludedTotal,
        )
        status.status == CatalogSyncStatus.Failure &&
            (lastSuccess != null || status.errorCategory == com.atlas.atlastv.data.db.CatalogRefreshWriteResult.RefusedEmptyCatalog.name) -> CatalogFreshnessUiState(
            state = CatalogFreshnessState.Failed,
            showPrompt = true,
            title = "Catalog refresh failed",
            message = if (status.errorCategory == com.atlas.atlastv.data.db.CatalogRefreshWriteResult.RefusedEmptyCatalog.name)
                status.refreshFailureMessage()
            else "Last synced ${lastSuccess?.safeAgeLabel(nowMs).orEmpty()}. Error: ${status.errorCategory ?: "Unknown"}",
            actionLabel = "Retry Movies/Shows",
            lastSyncedAtMs = lastSuccess,
            moviesImported = status.moviesImported,
            seriesImported = status.seriesImported,
            excludedTotal = status.excludedTotal,
            errorCategory = status.errorCategory,
        )
        lastSuccess == null -> CatalogFreshnessUiState(
            state = CatalogFreshnessState.NeverSynced,
            showPrompt = true,
            title = "Catalog not synced yet",
            message = if (hasSavedCredentials) "Use Settings or refresh now to load Movies and Shows." else "Open Settings to add a provider and sync Movies/Shows.",
            actionLabel = if (hasSavedCredentials) "Refresh Movies/Shows" else "Open Settings",
            lastSyncedAtMs = null,
            moviesImported = status.moviesImported,
            seriesImported = status.seriesImported,
            excludedTotal = status.excludedTotal,
            errorCategory = status.errorCategory,
        )
        nowMs - lastSuccess > staleThresholdMs -> CatalogFreshnessUiState(
            state = CatalogFreshnessState.Stale,
            showPrompt = true,
            title = "Catalog may be out of date",
            message = "Last synced ${lastSuccess.safeAgeLabel(nowMs)}.",
            actionLabel = "Refresh Movies/Shows",
            lastSyncedAtMs = lastSuccess,
            moviesImported = status.moviesImported,
            seriesImported = status.seriesImported,
            excludedTotal = status.excludedTotal,
        )
        else -> CatalogFreshnessUiState(
            state = CatalogFreshnessState.Fresh,
            showPrompt = false,
            title = "Catalog is up to date",
            message = "Last synced ${lastSuccess.safeAgeLabel(nowMs)}.",
            actionLabel = "Refresh Movies/Shows",
            lastSyncedAtMs = lastSuccess,
            moviesImported = status.moviesImported,
            seriesImported = status.seriesImported,
            excludedTotal = status.excludedTotal,
        )
    }
}

object CatalogFreshnessDefaults {
    const val StaleThresholdMs: Long = 24L * 60L * 60L * 1000L
    const val StaleThresholdLabel: String = "24 hours"
}

internal fun Long.safeAgeLabel(nowMs: Long): String {
    val ageMs = (nowMs - this).coerceAtLeast(0L)
    val hours = ageMs / (60L * 60L * 1000L)
    return when {
        hours < 1L -> "less than 1 hour ago"
        hours == 1L -> "1 hour ago"
        hours < 48L -> "$hours hours ago"
        else -> "${hours / 24L} days ago"
    }
}

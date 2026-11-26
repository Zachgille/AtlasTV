package com.atlas.atlastv.data.catalog

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamApiClient
import com.atlas.atlastv.xtream.network.XtreamApiResult
import com.atlas.atlastv.xtream.network.XtreamCatalogTimings
import com.atlas.atlastv.xtream.network.XtreamLoadStage
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class CatalogSyncRepository(
    private val apiClient: XtreamApiClient,
    private val importer: XtreamCatalogImporter,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun syncMoviesAndSeries(
        credentials: XtreamCredentials,
        onStage: (XtreamLoadStage) -> Unit = {},
    ): CatalogImportResult {
        val startedAt = clockMs()
        return when (val result = apiClient.loadMoviesAndSeriesCatalog(credentials, onStage)) {
            is XtreamApiResult.Failure -> CatalogImportResult(
                status = CatalogSyncStatus.Failure,
                startedAtMs = startedAt,
                completedAtMs = clockMs(),
                moviesImported = 0,
                seriesImported = 0,
                excludedCategories = 0,
                excludedItems = 0,
                errorCategory = result.category.name,
                timings = result.timings.toCatalogTimings().withTotal(clockMs() - startedAt),
            )
            is XtreamApiResult.Success -> {
                coroutineContext.ensureActive()
                onStage(XtreamLoadStage.ImportingCatalog)
                importer.importMoviesAndSeries(
                    vodCategories = result.vodCategories,
                    vodStreams = result.vodStreams,
                    seriesCategories = result.seriesCategories,
                    series = result.series,
                    baseTimings = result.timings.toCatalogTimings(),
                    syncStartedAtMs = startedAt,
                )
            }
        }
    }

    private fun XtreamCatalogTimings.toCatalogTimings(): CatalogSyncTimings =
        CatalogSyncTimings(
            accountInfoDurationMs = accountInfoDurationMs,
            vodCategoriesDurationMs = vodCategoriesDurationMs,
            vodStreamsDurationMs = vodStreamsDurationMs,
            seriesCategoriesDurationMs = seriesCategoriesDurationMs,
            seriesDurationMs = seriesDurationMs,
            vodCategoryCount = vodCategoryCount,
            vodStreamCount = vodStreamCount,
            seriesCategoryCount = seriesCategoryCount,
            seriesCount = seriesCount,
        )
}

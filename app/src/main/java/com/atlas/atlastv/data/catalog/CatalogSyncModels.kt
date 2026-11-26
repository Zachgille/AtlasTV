package com.atlas.atlastv.data.catalog

data class CatalogImportResult(
    val status: CatalogSyncStatus,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val moviesImported: Int,
    val seriesImported: Int,
    val excludedCategories: Int,
    val excludedItems: Int,
    val errorCategory: String? = null,
    val timings: CatalogSyncTimings = CatalogSyncTimings(),
) {
    val durationMs: Long = (completedAtMs - startedAtMs).coerceAtLeast(0L)
}

enum class CatalogSyncStatus {
    Idle,
    Loading,
    Success,
    Failure,
}

data class CatalogSyncTimings(
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
    fun withTotal(totalMs: Long): CatalogSyncTimings =
        copy(totalDurationMs = totalMs.coerceAtLeast(0L))
}

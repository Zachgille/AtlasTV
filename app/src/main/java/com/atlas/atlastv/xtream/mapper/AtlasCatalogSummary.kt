package com.atlas.atlastv.xtream.mapper

data class AtlasCatalogSummary(
    val liveCategoryCount: Int,
    val liveStreamCount: Int,
    val vodCategoryCount: Int,
    val vodStreamCount: Int,
    val seriesCategoryCount: Int,
    val seriesCount: Int,
    val keptLiveStreamCount: Int,
    val keptVodStreamCount: Int,
    val keptSeriesCount: Int,
    val excludedCategoryCount: Int,
    val excludedItemCount: Int,
    val topKeptCategories: List<CategoryCount>,
    val topExcludedCategories: List<CategoryCount>,
)

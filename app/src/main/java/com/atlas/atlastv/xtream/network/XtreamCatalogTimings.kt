package com.atlas.atlastv.xtream.network

data class XtreamCatalogTimings(
    val accountInfoDurationMs: Long = 0L,
    val vodCategoriesDurationMs: Long = 0L,
    val vodStreamsDurationMs: Long = 0L,
    val seriesCategoriesDurationMs: Long = 0L,
    val seriesDurationMs: Long = 0L,
    val vodCategoryCount: Int = 0,
    val vodStreamCount: Int = 0,
    val seriesCategoryCount: Int = 0,
    val seriesCount: Int = 0,
)

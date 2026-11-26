package com.atlas.atlastv.playlist.debug

import com.atlas.atlastv.xtream.mapper.CategoryCount

data class XtreamDebugSummary(
    val auth: Int?,
    val status: String?,
    val message: String?,
    val serverProtocol: String?,
    val serverPort: String?,
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

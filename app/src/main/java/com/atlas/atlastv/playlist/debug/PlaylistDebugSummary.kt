package com.atlas.atlastv.playlist.debug

data class PlaylistDebugSummary(
    val redactedUrl: String,
    val downloadedCharacterCount: Int,
    val downloadedByteCount: Long?,
    val parsedItemCount: Int,
    val parseWarningCount: Int,
    val keptItemCount: Int,
    val excludedItemCount: Int,
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val unknownCount: Int,
    val topKeptCategories: List<PlaylistDebugCategoryCount>,
    val topExcludedCategories: List<PlaylistDebugCategoryCount>,
)

package com.atlas.atlastv.playlist.filter

import com.atlas.atlastv.playlist.parser.PlaylistItem

data class PlaylistFilterResult(
    val keptItems: List<PlaylistItem>,
    val excludedItems: List<PlaylistFilterDecision>,
    val totalCount: Int,
    val keptCount: Int,
    val excludedCount: Int,
    val excludedCategoryCounts: Map<String, Int>,
)

package com.atlas.atlastv.playlist.indexer

data class PlaylistIndexResult(
    val allItems: List<IndexedPlaylistItem>,
    val liveItems: List<IndexedPlaylistItem>,
    val movieItems: List<IndexedPlaylistItem>,
    val seriesItems: List<IndexedPlaylistItem>,
    val unknownItems: List<IndexedPlaylistItem>,
    val liveGroups: List<PlaylistCategoryGroup>,
    val movieGroups: List<PlaylistCategoryGroup>,
    val seriesGroups: List<PlaylistCategoryGroup>,
    val unknownGroups: List<PlaylistCategoryGroup>,
    val totalCount: Int,
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val unknownCount: Int,
)

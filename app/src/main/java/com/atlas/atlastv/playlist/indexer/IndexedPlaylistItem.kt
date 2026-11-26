package com.atlas.atlastv.playlist.indexer

import com.atlas.atlastv.playlist.parser.PlaylistItem

data class IndexedPlaylistItem(
    val sourceItem: PlaylistItem,
    val contentType: PlaylistContentType,
    val normalizedTitle: String,
    val category: String?,
    val seriesName: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

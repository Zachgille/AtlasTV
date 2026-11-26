package com.atlas.atlastv.playlist.filter

import com.atlas.atlastv.playlist.parser.PlaylistItem

data class PlaylistFilterDecision(
    val item: PlaylistItem,
    val reason: PlaylistFilterReason,
)

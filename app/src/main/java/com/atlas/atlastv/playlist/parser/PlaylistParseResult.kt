package com.atlas.atlastv.playlist.parser

data class PlaylistParseResult(
    val items: List<PlaylistItem>,
    val warnings: List<PlaylistParseWarning>,
)

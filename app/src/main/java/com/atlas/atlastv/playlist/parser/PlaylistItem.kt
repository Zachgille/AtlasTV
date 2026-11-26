package com.atlas.atlastv.playlist.parser

data class PlaylistItem(
    val tvgId: String?,
    val tvgName: String?,
    val tvgLogo: String?,
    val groupTitle: String?,
    val displayName: String,
    val streamUrl: String,
    val rawExtinf: String,
)

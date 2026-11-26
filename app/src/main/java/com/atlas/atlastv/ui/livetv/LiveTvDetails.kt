package com.atlas.atlastv.ui.livetv

data class LiveTvChannelDetails(
    val channelName: String,
    val categoryName: String,
    val selectedIndex: Int,
    val visibleCount: Int,
    val streamId: Int,
    val cacheSource: String,
    val cacheAgeMs: Long?,
    val sortModeLabel: String,
    val redactedStreamUrl: String,
    val isFavorite: Boolean,
    val playbackHint: String = "Press Select to play",
)

fun LiveTvCatalogState.Ready.selectedChannelDetails(): LiveTvChannelDetails? {
    val visibleChannels = visibleChannels()
    val selectedIndex = visibleChannels.indexOfFirst { it.streamId == selectedChannelStreamId }
    val channel = visibleChannels.getOrNull(selectedIndex) ?: return null
    return LiveTvChannelDetails(
        channelName = channel.name,
        categoryName = channel.categoryName,
        selectedIndex = selectedIndex + 1,
        visibleCount = visibleChannels.size,
        streamId = channel.streamId,
        cacheSource = source,
        cacheAgeMs = cacheAgeMs,
        sortModeLabel = sortMode.displayName,
        redactedStreamUrl = channel.redactedStreamUrl,
        isFavorite = channel.streamId in favoriteStreamIds,
    )
}

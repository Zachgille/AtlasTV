package com.atlas.atlastv.playback

data class EpisodeProgressEdit internal constructor(
    internal val version: Long,
    internal val previous: WatchProgressRecord?,
    val applied: WatchProgressRecord,
) {
    val restoresWatched: Boolean get() = previous?.completed == true
}

sealed interface EpisodeEditResult {
    data class Accepted(val edit: EpisodeProgressEdit) : EpisodeEditResult
    data object Conflict : EpisodeEditResult
}

enum class EpisodeEditStatus { Saving, Failed, Saved, Superseded }

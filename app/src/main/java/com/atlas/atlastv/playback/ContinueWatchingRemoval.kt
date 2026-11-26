package com.atlas.atlastv.playback

/** Originals and their shared ordered-writer version, retained only for this app process. */
data class ContinueWatchingRemoval internal constructor(
    internal val version: Long,
    internal val previous: List<WatchProgressRecord>,
    internal val applied: List<WatchProgressRecord>,
)
sealed interface ContinueWatchingRemovalResult {
    data class Accepted(val edit: ContinueWatchingRemoval) : ContinueWatchingRemovalResult
    data object Conflict : ContinueWatchingRemovalResult
}

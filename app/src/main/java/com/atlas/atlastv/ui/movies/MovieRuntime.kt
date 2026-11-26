package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.core.media.MediaRuntime
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord

/** Descriptive metadata only; the saved checkpoint is never rewritten here. */
internal fun MovieMetadata.withPlaybackRuntime(movieId: Int, record: WatchProgressRecord?): MovieMetadata {
    val matching = record?.takeIf { it.contentType == WatchProgressContentType.Movie && it.streamId == movieId }
    return copy(durationMinutes = MediaRuntime.minutes(matching?.durationMs) ?: MediaRuntime.validMinutes(durationMinutes))
}

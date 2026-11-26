package com.atlas.atlastv.ui.catalog

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.formatWatchProgressTime

/** Presentation only: never changes the resume/completion policy or saved checkpoint. */
data class CardWatchProgress(val fraction: Float?, val timeLabel: String, val episodeLabel: String? = null) {
    val summary: String get() = listOfNotNull(episodeLabel, timeLabel).joinToString(" · ")
}

fun WatchProgressRecord.cardWatchProgress(): CardWatchProgress {
    val duration = durationMs?.takeIf { it > 0 }
    val position = positionMs.coerceAtLeast(0)
    val fraction = if (completed) 1f else duration?.let { (position.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val time = when {
        completed -> "Watched"
        duration == null -> "At ${position.formatWatchProgressTime()}"
        else -> {
            val remaining = duration - position.coerceAtMost(duration)
            val minutes = remaining / 60_000 + if (remaining % 60_000 > 0) 1 else 0
            when {
                minutes < 60 -> "${minutes}m left"
                minutes % 60 == 0L -> "${minutes / 60}h left"
                else -> "${minutes / 60}h ${minutes % 60}m left"
            }
        }
    }
    val episode = if (contentType == WatchProgressContentType.SeriesEpisode) {
        // Historical checkpoints contain title text, not structured season/episode fields.
        // Use only an explicit unambiguous code; never infer from the stream ID or generic numbers.
        listOfNotNull(episodeTitle, title).asSequence().flatMap { text ->
            episodeCode.findAll(text).mapNotNull { match ->
                val season = match.groupValues[1].toIntOrNull()
                val number = match.groupValues[2].toIntOrNull()
                if (season != null && season >= 0 && number != null && number > 0) "S$season E$number" else null
            }
        }.distinct().toList().singleOrNull()
    } else null
    return CardWatchProgress(fraction, time, episode)
}

private val episodeCode = Regex("\\bS\\s*(\\d{1,3})\\s*[- .]?\\s*E\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)

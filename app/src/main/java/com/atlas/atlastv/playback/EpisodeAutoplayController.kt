package com.atlas.atlastv.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.ui.series.SeriesEpisodeItem
import kotlin.math.ceil

data class EpisodeAutoplayCountdown(val episode: SeriesEpisodeItem, val deadlineMs: Long, val secondsRemaining: Int)

/** One ending, one decision. Backgrounding/cancellation never resumes an old countdown. */
class EpisodeAutoplayController(private val countdownSeconds: Int = 10) {
    init { require(countdownSeconds > 0) }
    var countdown: EpisodeAutoplayCountdown? by mutableStateOf(null)
        private set
    private var suppressed = false

    fun update(ended: Boolean, enabled: Boolean, active: Boolean, next: SeriesEpisodeItem?, nowMs: Long) {
        if (!ended) {
            countdown = null
            suppressed = false
            return
        }
        if (!active || !enabled) {
            cancel()
            return
        }
        if (suppressed) return
        if (next == null) { countdown = null; return }
        if (countdown?.episode?.episodeId != next.episodeId) {
            countdown = EpisodeAutoplayCountdown(next, nowMs + countdownSeconds * 1_000L, countdownSeconds)
        }
    }

    fun cancel() { suppressed = true; countdown = null }

    /** A monotonic deadline avoids delayed frames extending the countdown. Consumption is one-shot. */
    fun tick(nowMs: Long): SeriesEpisodeItem? {
        val current = countdown ?: return null
        val remaining = current.deadlineMs - nowMs
        if (remaining <= 0) {
            cancel()
            return current.episode
        }
        countdown = current.copy(secondsRemaining = ceil(remaining / 1_000.0).toInt())
        return null
    }

    fun playNow(): SeriesEpisodeItem? {
        val next = countdown?.episode ?: return null
        cancel()
        return next
    }
}

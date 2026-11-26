package com.atlas.atlastv.playback

import com.atlas.atlastv.ui.series.SeriesEpisodeItem
import org.junit.Assert.*
import org.junit.Test

class EpisodeAutoplayControllerTest {
    private fun episode(id: Int = 2) = SeriesEpisodeItem("Episode", 1, id, id, "mp4", "redacted")
    private fun update(controller: EpisodeAutoplayController, ended: Boolean = true, enabled: Boolean = true,
        active: Boolean = true, next: SeriesEpisodeItem? = episode(), now: Long = 100) = controller.update(ended, enabled, active, next, now)

    @Test fun onlyNaturalEndWithOptInAndSuccessorStartsAFullCountdown() {
        val c = EpisodeAutoplayController()
        update(c, ended = false); assertNull(c.countdown)
        update(c); assertEquals(10, c.countdown!!.secondsRemaining); assertEquals(10_100L, c.countdown!!.deadlineMs)
        assertNull(c.tick(10_099)); assertEquals(1, c.countdown!!.secondsRemaining)
        assertEquals(episode(), c.tick(10_100)); assertNull(c.tick(20_000))
        update(c, now = 20_000); assertNull(c.countdown)
    }
    @Test fun cancelAtTheDeadlineAndLateMetadataNeverRestartTheSameEnding() {
        val c = EpisodeAutoplayController(); update(c); c.cancel()
        assertNull(c.tick(10_100)); update(c, next = episode(3), now = 15_000); assertNull(c.countdown)
        update(c, ended = false); update(c, now = 20_000); assertNotNull(c.countdown)
    }
    @Test fun backgroundCancellationCannotConsumeOrResumeWhenForegroundReturns() {
        val c = EpisodeAutoplayController(); update(c); update(c, active = false, now = 5_000)
        assertNull(c.tick(20_000)); update(c, now = 30_000); assertNull(c.countdown)
    }
    @Test fun disablingCancelsAndReenablingRequiresAnotherPlaybackEnding() {
        val c = EpisodeAutoplayController(); update(c); update(c, enabled = false)
        update(c, enabled = true); assertNull(c.countdown)
        update(c, ended = false); update(c); assertNotNull(c.countdown)
    }
    @Test fun absentOrFinalEpisodeDoesNotAutoplayAndLateValidLookupGetsFullTime() {
        val c = EpisodeAutoplayController(); update(c, next = null)
        assertNull(c.tick(50_000)); update(c, now = 50_000)
        assertEquals(60_000L, c.countdown!!.deadlineMs); assertNull(c.tick(59_999))
    }
    @Test fun changedSuccessorRestartsFullCountdownAndCannotPlayStaleEpisode() {
        val c = EpisodeAutoplayController(); update(c); update(c, next = episode(3), now = 9_000)
        assertNull(c.tick(10_100)); assertEquals(19_000L, c.countdown!!.deadlineMs)
        assertEquals(episode(3), c.tick(19_000))
    }
    @Test fun playNowIsOneShotEvenWithRepeatedActivationAndUpdates() {
        val c = EpisodeAutoplayController(); update(c)
        assertEquals(episode(), c.playNow()); assertNull(c.playNow())
        update(c, now = 30_000); assertNull(c.countdown)
    }
    @Test fun delayedTimerFramesUseDeadlineRatherThanAccumulatingExtraSeconds() {
        val c = EpisodeAutoplayController(); update(c)
        c.tick(6_700); assertEquals(4, c.countdown!!.secondsRemaining)
        assertEquals(episode(), c.tick(99_000))
    }
}

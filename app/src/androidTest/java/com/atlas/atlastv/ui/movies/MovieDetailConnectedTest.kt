package com.atlas.atlastv.ui.movies

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.MovieDetailCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MovieDetailConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MovieDetailCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }

    @Test fun arrivingMetadataKeepsWatchlistFocusAndShowsYearOnlyOnce() {
        waitFor("movie_detail_play"); press("movie_detail_play", Key.DirectionRight)
        compose.runOnIdle { compose.activity.complete() }; waitFor("movie_detail_synopsis")
        compose.onNodeWithTag("movie_detail_watchlist").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("movie_detail_play").assertIsDisplayed()
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie")
        compose.onNodeWithTag("movie_detail_year").assertTextEquals("2010 · Action / Mystery · 2h 28m")
        compose.onNodeWithTag("movie_detail_poster").assertIsDisplayed()
        compose.onNodeWithTag("movies_back").assertDoesNotExist()
    }

    @Test fun wholeHourRuntimeIsCompactAndMatchingPlaybackDurationUpdatesWithoutFocusLoss() {
        waitFor("movie_detail_play"); press("movie_detail_play", Key.DirectionRight)
        compose.runOnIdle { compose.activity.complete(value = MovieMetadata(durationMinutes = 120)) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("movie_detail_year").assertTextEquals("2010 · 2h"); true }.getOrDefault(false) }
        compose.onNodeWithTag("movie_detail_watchlist").assertIsFocused()
        compose.runOnIdle { compose.activity.setRuntimeCheckpoint(5_400_000) }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("movie_detail_year").assertTextEquals("2010 · 1h 30m"); true }.getOrDefault(false) }
        compose.onNodeWithTag("movie_detail_watchlist").assertIsFocused(); compose.onNodeWithTag("movie_detail_play").assertIsDisplayed()
        assertEquals(listOf(1), compose.activity.requestedIds)
    }
    @Test fun malformedRuntimeIsOmittedWhilePlayRemainsReady() {
        waitFor("movie_detail_play")
        compose.runOnIdle { compose.activity.complete(value = MovieMetadata(durationMinutes = Int.MAX_VALUE)) }
        compose.waitUntil(5_000) { compose.activity.holder.movieInfoState is MovieInfoState.Ready }
        compose.onNodeWithTag("movie_detail_year").assertTextEquals("2010")
        compose.onNodeWithTag("movie_detail_play").assertIsDisplayed().assertIsFocused()
    }

    @Test fun longTextCannotOverlapOrHidePrimaryActions() {
        waitFor("movie_detail_play")
        compose.runOnIdle {
            compose.activity.movieTitle = "An Extremely Long Movie Title With Many Words And Another Subtitle And More Words (2010)"
            compose.activity.complete(value = MovieMetadata("A very long description of the story and characters. ".repeat(80), "Drama / Mystery", "2010", 148))
        }
        waitFor("movie_detail_synopsis")
        compose.onNodeWithTag("movie_detail_play").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("movie_detail_watchlist").assertIsDisplayed()
        compose.onNodeWithTag("movie_detail_back").assertIsDisplayed()
        val synopsis = compose.onNodeWithTag("movie_detail_synopsis").fetchSemanticsNode().boundsInRoot
        val play = compose.onNodeWithTag("movie_detail_play").fetchSemanticsNode().boundsInRoot
        assertTrue(synopsis.bottom <= play.top)
    }

    @Test fun unavailableMetadataNeverBlocksPlayAndReturnKeepsTheMovie() {
        waitFor("movie_detail_play"); compose.runOnIdle { compose.activity.complete(value = null) }
        compose.waitUntil(5_000) { compose.activity.holder.movieInfoState is MovieInfoState.Unavailable }
        compose.onNodeWithTag("movie_detail_synopsis").assertDoesNotExist()
        press("movie_detail_play", Key.Enter); waitFor("movie_check_playback_back")
        compose.runOnIdle { assertEquals(1, compose.activity.openedMovieId) }
        compose.onNodeWithTag("movie_check_playback_back").performClick(); waitFor("movie_detail_play")
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie")
        compose.onNodeWithTag("movie_detail_play").assertIsFocused()
    }

    @Test fun playAndBackRemainUsableWhileOptionalMetadataIsStalled() {
        waitFor("movie_detail_play")
        compose.waitUntil(5_000) { compose.activity.holder.movieInfoState is MovieInfoState.Loading }
        press("movie_detail_play", Key.Enter); waitFor("movie_check_playback_back")
        compose.runOnIdle { assertEquals(1, compose.activity.openedMovieId) }
        compose.onNodeWithTag("movie_check_playback_back").performClick(); waitFor("movie_detail_play")
        press("movie_detail_play", Key.DirectionRight); press("movie_detail_watchlist", Key.DirectionRight)
        press("movie_detail_back", Key.Enter); waitFor("movies_search_open")
        compose.waitUntil(5_000) { compose.activity.holder.movieInfoState is MovieInfoState.Empty }
    }

    @Test fun watchlistAddsAndRemovesWithoutStartingPlaybackOrRefetchingMetadata() {
        waitFor("movie_detail_play"); compose.runOnIdle { compose.activity.complete() }; waitFor("movie_detail_synopsis")
        press("movie_detail_play", Key.DirectionRight); press("movie_detail_watchlist", Key.Enter)
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("− Watchlist")
        press("movie_detail_watchlist", Key.Enter)
        compose.onNodeWithTag("movie_detail_watchlist").assertTextEquals("+ Watchlist")
        compose.runOnIdle { assertNull(compose.activity.openedMovieId); assertEquals(listOf(1), compose.activity.requestedIds) }
    }

    @Test fun relatedMovieReplacesExplicitSearchSelectionAndUsesItsOwnMetadata() {
        waitFor("movie_detail_play"); compose.runOnIdle { compose.activity.complete() }; waitFor("movie_detail_synopsis")
        press("movie_detail_play", Key.DirectionDown); waitFor("focused_movie_more_like_this_card")
        press("focused_movie_more_like_this_card", Key.Enter)
        compose.waitUntil(5_000) { compose.activity.requestedIds.contains(2) }
        compose.onNodeWithTag("movie_detail_title").assertTextEquals("Another Movie")
        compose.onNodeWithTag("movie_detail_synopsis").assertDoesNotExist()
        compose.runOnIdle { compose.activity.complete(2, MovieMetadata("The second story.", releaseYear = "2020")) }
        waitFor("movie_detail_synopsis"); compose.onNodeWithTag("movie_detail_synopsis").assertTextEquals("The second story.")
        press("movie_detail_play", Key.Enter); waitFor("movie_check_playback_back")
        compose.runOnIdle { assertEquals(2, compose.activity.openedMovieId) }
    }

    @Test fun loadedMetadataSurvivesPlaybackCompositionAndBackRemainsReachable() {
        waitFor("movie_detail_play"); compose.runOnIdle { compose.activity.complete() }; waitFor("movie_detail_synopsis")
        press("movie_detail_play", Key.Enter); waitFor("movie_check_playback_back")
        compose.onNodeWithTag("movie_check_playback_back").performClick(); waitFor("movie_detail_synopsis")
        compose.runOnIdle { assertEquals(listOf(1), compose.activity.requestedIds) }
        press("movie_detail_play", Key.DirectionRight); press("movie_detail_watchlist", Key.DirectionRight)
        compose.onNodeWithTag("movie_detail_back").assertIsFocused(); press("movie_detail_back", Key.Enter)
        waitFor("movies_search_open")
    }
}

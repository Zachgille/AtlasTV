package com.atlas.atlastv.ui.movies

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MoviesNavigationConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomState()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomState()
    }

    @Test
    fun openMoviesFromHomeAndRenderCachedMovies() {
        openMovies()

        composeRule.onNodeWithTag("movies_row_page").fetchSemanticsNode()
        composeRule.onAllNodesWithTag("horizontal_row").fetchSemanticsNodes()
        composeRule.onAllNodesWithTag("movie_row_title").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("More Movies").assertCountEquals(1)
        composeRule.onAllNodesWithText("Recently Added Movies").fetchSemanticsNodes()
        composeRule.onAllNodesWithTag("movies_category_list").assertCountEquals(0)
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Cache age", substring = true).assertCountEquals(0)
    }

    @Test
    fun rowDpadSelectOpensMovieDetailThenPlayer() {
        openMovies()

        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("movie_detail_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_detail_play").assertIsFocused()
        composeRule.onNodeWithTag("movie_detail_play").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("live_tv_player_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("playback_full_screen_surface").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithTag("live_tv_player_screen").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithTag("movies_search_open").performKeyInput { pressKey(Key.DirectionDown) }
    }

    @Test
    fun movieDetailShowsSafeActionsAndWatchlistToggle() {
        openMovies()

        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("movie_detail_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_detail_title").assertTextContains("Fixture Movie One")
        composeRule.onNodeWithTag("movie_detail_category").assertTextContains("Fixture Action")
        composeRule.onNodeWithTag("movie_detail_play").assertTextContains("Play")
        composeRule.onNodeWithTag("movie_detail_watchlist").assertTextContains("+ Watchlist")
        composeRule.onNodeWithTag("movie_detail_hero").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_more_like_this_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_more_like_this_title").assertTextContains("More in this category")
        composeRule.onAllNodesWithText("Stream ID", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("fixture://", substring = true).assertCountEquals(0)

        composeRule.onNodeWithTag("movie_detail_play").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movie_detail_watchlist").assertIsFocused()
        composeRule.onNodeWithTag("movie_detail_watchlist").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("movie_detail_watchlist_state").assertTextContains("In Watchlist")
        composeRule.onNodeWithTag("movie_detail_watchlist").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movie_detail_back").assertIsFocused()
        composeRule.onNodeWithTag("movie_detail_back").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onAllNodesWithText("Watchlist").fetchSemanticsNodes()
    }

    @Test
    fun movieDetailResumeStateRestoresFromRoomAfterRelaunchWithoutJson() {
        watchProgressStore().saveRecord(
            WatchProgressRecord(
                contentType = WatchProgressContentType.Movie,
                streamId = 2001,
                title = "Fixture Movie One",
                categoryName = "Fixture Action",
                extension = "mp4",
                durationMs = 7_200_000L,
                positionMs = 900_000L,
                updatedAtMs = 100L,
            ),
        )
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        openMovies()
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("movie_detail_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_detail_resume_label").assertTextContains("Resume at 15:00", substring = true)
    }

    @Test
    fun movieDetailMoreLikeThisIsRemoteReachableAndUpdatesSelection() {
        openMovies()

        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("movie_detail_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_detail_title").assertTextContains("Fixture Movie One")

        composeRule.onNodeWithTag("movie_detail_play").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("focused_movie_more_like_this_card").assertIsFocused()
        composeRule.onNodeWithTag("focused_movie_more_like_this_card").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("movie_detail_title").assertTextContains("Fixture Movie Three")
        composeRule.onNodeWithTag("movie_detail_play").assertIsFocused()
        composeRule.onAllNodesWithText("fixture://", substring = true).assertCountEquals(0)
    }

    @Test
    fun searchOverlayFiltersMovieRowsAndClearsToNormalBrowse() {
        openMovies()

        composeRule.onNodeWithTag("movies_search_open").performClick()
        composeRule.onNodeWithTag("movies_search_overlay").fetchSemanticsNode()
        composeRule.onNodeWithTag("movies_search_field").performTextInput("drama")
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_refreshing").fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithTag("movies_search_result_count").assertTextContains("1 results")
        composeRule.onAllNodesWithText("Search Results").assertCountEquals(1)
        composeRule.onAllNodesWithText("Fixture Movie Two").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Fixture Movie One").assertCountEquals(0)
        composeRule.onAllNodesWithTag("movies_category_list").assertCountEquals(0)

        composeRule.onNodeWithTag("movies_clear_search").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_refreshing").fetchSemanticsNodes().isEmpty() }
        composeRule.onAllNodesWithText("More Movies").assertCountEquals(1)
        composeRule.onAllNodesWithText("Search Results").assertCountEquals(0)
    }

    @Test
    fun dpadUpFromFirstMovieRowReachesSearchAction() {
        openMovies()

        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_search_open").assertIsFocused()
        composeRule.onNodeWithTag("movies_search_open").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("movies_search_overlay").fetchSemanticsNode()
    }

    @Test
    fun searchOverlayBackClearsBeforeLeavingMovies() {
        openMovies()

        composeRule.onNodeWithTag("movies_search_open").performClick()
        composeRule.onNodeWithTag("movies_search_field").performTextInput("missing")
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_refreshing").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("movies_empty").fetchSemanticsNode()
        composeRule.onNodeWithTag("movies_search_field").performKeyInput { pressKey(Key.Back) }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_refreshing").fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithTag("movies_screen").fetchSemanticsNode()
        composeRule.onAllNodesWithText("More Movies").assertCountEquals(1)
        composeRule.onAllNodesWithText("No movies match your search.").assertCountEquals(0)
    }

    private fun openMovies() {
        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("movies_search_open").performKeyInput { pressKey(Key.DirectionDown) }
    }

    private fun watchProgressStore(): WatchProgressStore =
        WatchProgressStore(
            File(targetContext.filesDir, WatchProgressStore.FileName),
            WatchProgressRoomStore(AtlasDatabaseProvider.database(targetContext).watchProgressDao()),
        )

    private fun clearRoomState() {
        runBlocking {
            val database = AtlasDatabaseProvider.database(targetContext)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Movie)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Series)
            MoviesWatchlistRoomStore(database.movieWatchlistDao()).clear()
            WatchProgressRoomStore(database.watchProgressDao()).clear()
        }
    }

    private companion object {
        private val fixtureCacheJson = """
            {
              "catalogSource": "fixture",
              "generatedAtEpochMs": 1,
              "itemCounts": {
                "vodCategories": 2,
                "vodStreams": 3,
                "keptVodStreams": 3
              },
              "redactedUrl": "fixture://debug_catalog",
              "redactedHost": "fixture",
              "playableLiveItems": [],
              "playableVodItems": [
                {"title":"Fixture Movie One","categoryName":"Fixture Action","streamId":2001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2001.mp4"},
                {"title":"Fixture Movie Two","categoryName":"Fixture Drama","streamId":2002,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2002.mp4"},
                {"title":"Fixture Movie Three","categoryName":"Fixture Action","streamId":2003,"containerExtension":"mkv","redactedUrl":"fixture://debug_catalog/movie/2003.mkv"}
              ]
            }
        """.trimIndent()
    }
}

package com.atlas.atlastv.ui.series

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeriesSortRestoreConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        clearRoomCatalog()
    }

    @After
    fun tearDown() {
        scenario?.close()
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        clearRoomCatalog()
    }

    @Test
    fun savedTitleSortRestoresPredictableFirstFocusAfterRelaunch() {
        preferencesStore().save(SeriesPreferences(sortMode = SeriesSortMode.Title, selectedSeriesId = 3002))
        launchAndOpenSeries()

        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("series_detail_page").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_title").assertTextContains("Alpha Show")
    }

    @Test
    fun invalidSavedContextFallsBackToAvailableSeriesAndWatchlistStillRenders() {
        preferencesStore().save(
            SeriesPreferences(
                selectedCategoryId = "Missing Category",
                selectedSeriesId = 9999,
                selectedSeriesTitle = "http://example.test/series/user/pass/9999.mp4",
                sortMode = SeriesSortMode.CategoryThenTitle,
            ),
        )
        SeriesWatchlistStore(File(targetContext.filesDir, SeriesWatchlistStore.FileName)).saveWatchlist(
            series = listOf(SeriesItem("Zeta Show", "Fixture Action", 3001)),
            watchlistSeriesIds = setOf(3001),
        )

        launchAndOpenSeries()

        composeRule.onAllNodesWithText("Watchlist").fetchSemanticsNodes()
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("series_detail_title").assertTextContains("Zeta Show")
    }

    private fun launchAndOpenSeries() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag("top_nav_item_shows").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("series_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("series_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_row_page").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_search_open").performKeyInput { pressKey(Key.DirectionDown) }
    }

    private fun preferencesStore(): SeriesPreferencesStore =
        SeriesPreferencesStore(File(targetContext.filesDir, SeriesPreferencesStore.FileName))

    private fun clearRoomCatalog() {
        runBlocking {
            val database = AtlasDatabaseProvider.database(targetContext)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Movie)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Series)
        }
    }

    private companion object {
        private val fixtureCacheJson = """
            {
              "catalogSource": "fixture",
              "generatedAtEpochMs": 1,
              "redactedUrl": "fixture://debug_catalog",
              "redactedHost": "fixture",
              "playableLiveItems": [],
              "playableVodItems": [],
              "playableSeriesItems": [
                {"name":"Zeta Show","categoryName":"Fixture Action","seriesId":3001},
                {"name":"Alpha Show","categoryName":"Fixture Drama","seriesId":3002},
                {"name":"Beta Show","categoryName":"Fixture Action","seriesId":3003}
              ]
            }
        """.trimIndent()
    }
}

package com.atlas.atlastv.ui.livetv

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveTvSearchConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, LiveTvPreferencesStore.FileName).delete()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        File(targetContext.filesDir, LiveTvPreferencesStore.FileName).delete()
    }

    @Test
    fun searchFiltersByNormalizedChannelAndCategoryText() {
        openLiveTv()

        composeRule.onNodeWithTag("live_tv_search_field").performTextInput("sports prime")

        composeRule.onNodeWithTag("live_tv_filtered_channel_list").fetchSemanticsNode()
        composeRule.onNodeWithTag("live_tv_selected_search_result").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Prime Sports 1").assertCountEquals(2)
        composeRule.onNodeWithTag("live_tv_search_result_count").fetchSemanticsNode()
    }

    @Test
    fun clearSearchRestoresSelectedCategoryContext() {
        openLiveTv()
        composeRule.onNodeWithTag("live_tv_category_News").performClick()
        composeRule.onNodeWithTag("live_tv_search_field").performTextInput("sports")
        composeRule.onAllNodesWithText("Prime Sports 1").assertCountEquals(2)

        composeRule.onNodeWithTag("live_tv_clear_search").performClick()

        composeRule.onNodeWithTag("live_tv_channel_list").fetchSemanticsNode()
        composeRule.onAllNodesWithText("BBC World News").assertCountEquals(1)
        composeRule.onNodeWithTag("live_tv_category_News").fetchSemanticsNode()
    }

    @Test
    fun emptySearchResultsShowEmptyState() {
        openLiveTv()

        composeRule.onNodeWithTag("live_tv_search_field").performTextInput("notfound")

        composeRule.onNodeWithTag("live_tv_search_empty").fetchSemanticsNode()
        composeRule.onNodeWithTag("live_tv_search_result_count").fetchSemanticsNode()
    }

    private fun openLiveTv() {
        composeRule.onNodeWithTag("top_nav_item_live_tv").performClick()
        composeRule.onNodeWithTag("live_tv_screen").fetchSemanticsNode()
    }

    private companion object {
        private val fixtureCacheJson = """
            {
              "catalogSource": "fixture",
              "generatedAtEpochMs": 1,
              "itemCounts": {
                "liveCategories": 3,
                "liveStreams": 4,
                "keptLiveStreams": 4
              },
              "redactedUrl": "fixture://debug_catalog",
              "redactedHost": "fixture",
              "playableLiveItems": [
                {"name":"BBC World News","categoryName":"News","streamId":2001,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/2001.ts"},
                {"name":"Prime Sports 1","categoryName":"US Sports","streamId":2002,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/2002.ts"},
                {"name":"Kids Zone","categoryName":"Children TV","streamId":2003,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/2003.ts"},
                {"name":"Local Weather","categoryName":"News","streamId":2004,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/2004.ts"}
              ]
            }
        """.trimIndent()
    }
}

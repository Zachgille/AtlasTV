package com.atlas.atlastv.ui.livetv

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.atlas.atlastv.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveTvNavigationConnectedTest {
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
    fun openLiveTvFromHomeAndRenderCachedChannels() {
        openLiveTv()

        composeRule.onNodeWithTag("live_tv_category_list").fetchSemanticsNode()
        composeRule.onNodeWithTag("live_tv_channel_list").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Fixture Live One").assertCountEquals(2)
        composeRule.onNodeWithTag("live_tv_category_Fixture News").fetchSemanticsNode()
        composeRule.onNodeWithTag("live_tv_row_recent").fetchSemanticsNode()
    }

    @Test
    fun categorySelectionUpdatesChannelList() {
        openLiveTv()

        composeRule.onNodeWithTag("live_tv_category_Fixture Sports").performClick()

        composeRule.onAllNodesWithText("Fixture Live Two").assertCountEquals(1)
        composeRule.onNodeWithTag("live_tv_selected_channel").fetchSemanticsNode()
    }

    @Test
    fun dpadSelectOpensPlayerAndBackReturnsToListContext() {
        openLiveTv()
        composeRule.onNodeWithTag("live_tv_category_Fixture Sports").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("live_tv_selected_channel").performKeyInput {
            pressKey(Key.DirectionCenter)
        }

        composeRule.onNodeWithTag("live_tv_player_screen").fetchSemanticsNode()

        pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_tv_player_screen").fetchSemanticsNode()
        pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("live_tv_channel_list").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Fixture Live Two").assertCountEquals(1)
        composeRule.onNodeWithTag("live_tv_category_Fixture Sports").fetchSemanticsNode()
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
                {"name":"Fixture Live One","categoryName":"Fixture News","streamId":1001,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1001.ts"},
                {"name":"Fixture Live Two","categoryName":"Fixture Sports","streamId":1002,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1002.ts"},
                {"name":"Fixture Live Three","categoryName":"Fixture Movies","streamId":1003,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1003.ts"},
                {"name":"Fixture Live Four","categoryName":"Fixture Sports","streamId":1004,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1004.ts"}
              ]
            }
        """.trimIndent()
    }
}

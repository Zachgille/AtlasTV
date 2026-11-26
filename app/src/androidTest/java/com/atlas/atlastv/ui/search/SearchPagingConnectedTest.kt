package com.atlas.atlastv.ui.search

import com.atlas.atlastv.ui.browse.openGridOptions

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.SearchPagingCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchPagingConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<SearchPagingCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
    private fun focused(tag: String) { compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun search(query: String = "love") {
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput(query)
        waitFor("unified_search_movies_all"); compose.onNodeWithTag("unified_search_field").assertIsFocused()
    }
    private fun open() { search(); press("unified_search_field",Key.DirectionDown); focused("focused_unified_search_result_movie-1")
        press("focused_unified_search_result_movie-1",Key.DirectionLeft); focused("unified_search_movies_all")
        press("unified_search_movies_all",Key.Enter); waitFor("search_all_loading") }
    private fun complete() { compose.runOnIdle { compose.activity.completePage() }; waitFor("search_all_tile_0"); focused("search_all_tile_0") }
    private fun lastReady() {
        compose.openGridOptions("search_all"); compose.onNodeWithTag("search_all_last").performClick()
        compose.waitUntil(5_000) { compose.activity.requests.get() == 2 }
        compose.runOnIdle { compose.activity.completePage() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("search_all_tile_512") and hasContentDescription("love Movie 513")).fetchSemanticsNodes().isNotEmpty() }
        focused("search_all_tile_512")
    }
    @Test fun seeAllIsRemoteReachableAndLoadingBackCancelsWithoutLosingQuery() {
        open(); compose.onNodeWithTag("search_all_back").assertIsFocused().performClick()
        focused("unified_search_movies_all"); compose.onNodeWithTag("unified_search_field").assertTextEquals("love")
        compose.waitUntil(5_000) { compose.activity.cancelledPages.get() == 1 }
        assertEquals(1,compose.activity.searches.get())
    }
    @Test fun fullMovieResultsAndDetailReturnSurvivePlaybackCompositionWithBoundedWindow() {
        open(); complete(); lastReady()
        press("search_all_tile_512",Key.Enter); waitFor("search_check_detail_title")
        compose.onNodeWithTag("search_check_detail_title").assertTextEquals("love Movie 513")
        compose.onNodeWithTag("search_check_play").performClick(); waitFor("search_check_playback_back")
        compose.onNodeWithTag("search_check_playback_back").performClick(); waitFor("search_check_detail_back")
        compose.onNodeWithTag("search_check_detail_back").performClick(); focused("search_all_tile_512")
        assertTrue(compose.activity.holder.activeGrid!!.holder.state.value.items.size <= 200)
        compose.onNodeWithTag("search_all_back").performClick(); focused("unified_search_movies_all")
        compose.onNodeWithTag("unified_search_field").assertTextEquals("love"); assertEquals(1,compose.activity.searches.get())
    }
    @Test fun scrolledShowPreviewReturnsToExactResultAndBothRowsRemainReachable() {
        search(); press("unified_search_field",Key.DirectionDown); focused("focused_unified_search_result_movie-1")
        press("focused_unified_search_result_movie-1",Key.DirectionDown); focused("focused_unified_search_result_show-1")
        repeat(10) { i -> press("focused_unified_search_result_show-${i+1}",Key.DirectionRight) }
        focused("focused_unified_search_result_show-11")
        var beforeScroll = emptyList<Int>()
        compose.runOnIdle {
            val row = compose.activity.holder.rowScroll["unified_search_shows"]!!
            assertTrue(row.first > 0)
            beforeScroll = listOf(compose.activity.holder.listIndex,compose.activity.holder.listOffset,row.first,row.second)
        }
        press("focused_unified_search_result_show-11",Key.Enter)
        waitFor("search_check_detail_title"); compose.onNodeWithTag("search_check_detail_title").assertTextEquals("love Show 11")
        compose.onNodeWithTag("search_check_detail_back").performClick(); focused("focused_unified_search_result_show-11")
        compose.runOnIdle {
            val row = compose.activity.holder.rowScroll["unified_search_shows"]!!
            assertEquals(beforeScroll,listOf(compose.activity.holder.listIndex,compose.activity.holder.listOffset,row.first,row.second))
        }
        press("focused_unified_search_result_show-11",Key.DirectionUp); focused("focused_unified_search_result_movie-1")
        press("focused_unified_search_result_movie-1",Key.DirectionUp); focused("unified_search_field")
        assertEquals(1,compose.activity.searches.get())
    }
    @Test fun deepFailureKeepsGoodWindowRetriesAndNewQueryCancelsOldSearchWithoutFocusTheft() {
        open(); complete(); compose.openGridOptions("search_all"); compose.onNodeWithTag("search_all_last").performClick()
        compose.waitUntil(5_000) { compose.activity.requests.get() == 2 }
        compose.runOnIdle { compose.activity.failPage() }; waitFor("search_all_error")
        assertNotNull(compose.activity.holder.activeGrid!!.holder.state.value.item(0))
        press("search_all_tile_512",Key.Enter); compose.waitUntil(5_000) { compose.activity.requests.get() == 3 }
        compose.runOnIdle { compose.activity.completePage() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("search_all_tile_512") and hasContentDescription("love Movie 513")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search_all_back").performClick(); focused("unified_search_movies_all")
        compose.onNodeWithTag("unified_search_clear").performClick(); compose.onNodeWithTag("unified_search_field").performTextInput("slow")
        compose.waitUntil(5_000) { compose.activity.searches.get() == 2 }
        compose.onNodeWithTag("unified_search_field").performTextReplacement("new")
        compose.waitUntil(5_000) { compose.activity.cancelledSearches.get() == 1 }
        waitFor("unified_search_movies_all"); focused("unified_search_field")
        assertNull(compose.activity.holder.activeGrid); assertEquals("new",compose.activity.holder.query)
        assertTrue(compose.activity.holder.rowScroll.values.all { it.first == 0 })
    }
    @Test fun initialErrorHasSafeRemoteRetryAndClearReturnsToPrompt() {
        waitFor("unified_search_field"); compose.onNodeWithTag("unified_search_field").performTextInput("fail")
        waitFor("unified_search_error"); press("unified_search_field",Key.DirectionDown); focused("unified_search_retry")
        press("unified_search_retry",Key.Enter); waitFor("unified_search_movies_all"); focused("focused_unified_search_result_movie-1")
        compose.onNodeWithTag("unified_search_clear").performClick(); waitFor("unified_search_field"); focused("unified_search_field")
        compose.onNodeWithTag("unified_search_back").performClick(); waitFor("search_check_home")
    }
    @Test fun closingAfterRefreshRemovesSeeAllEntryAndFocusesRemainingResult() {
        open(); complete(); compose.runOnIdle { compose.activity.shrinkPreview() }
        compose.waitUntil(5_000) { (compose.activity.holder.state as? UnifiedSearchState.Ready)?.movieCount == 1 }
        compose.onNodeWithTag("search_all_back").performClick(); focused("focused_unified_search_result_movie-1")
        compose.onNodeWithTag("unified_search_movies_all").assertDoesNotExist()
        compose.onNodeWithTag("unified_search_field").assertTextEquals("love")
        assertEquals(1, (compose.activity.holder.state as UnifiedSearchState.Ready).totalCount)
        compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
    }
}

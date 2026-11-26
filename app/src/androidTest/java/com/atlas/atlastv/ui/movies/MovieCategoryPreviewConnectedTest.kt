package com.atlas.atlastv.ui.movies

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.MovieCategoryPreviewCheckActivity
import com.atlas.atlastv.ui.home.assertHomeFooterVisible
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class MovieCategoryPreviewConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(blocked: Boolean = false, singleton: Boolean = false, fail: Boolean = false) = ActivityScenario.launch<MovieCategoryPreviewCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), MovieCategoryPreviewCheckActivity::class.java).putExtra("blocked", blocked).putExtra("singleton", singleton).putExtra("fail", fail))
    private fun waitTag(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun cardPart(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("focused_movie_more_like_this_card")), useUnmergedTree = true)
    private fun title() = cardPart("poster_card_title").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun visible() = assertHomeFooterVisible(cardPart("focused_poster_card"), compose.onNodeWithTag("movie_category_preview_viewport"), cardPart("poster_card_footer"), cardPart("poster_card_title"))
    @Test fun categoryOnlyDeepPosterOpensDetailsAndBothBackRoutesRestoreItsExactPosition() {
        launch().use { s ->
            waitTag("movie_more_like_this_title"); focused("movie_detail_play")
            compose.onNodeWithTag("movie_more_like_this_title").assertTextEquals("More in this category")
            compose.onAllNodesWithText("More Like This").assertCountEquals(0); compose.onAllNodesWithText("Format:", substring = true).assertCountEquals(0)
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_movie_more_like_this_card"); assertEquals("Category Movie 2", title()); visible()
            repeat(11) { native(KeyEvent.KEYCODE_DPAD_RIGHT) }; assertEquals("Category Movie 13", title()); visible()
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("movie_detail_play"); focused("movie_detail_play")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("Category Movie 13")
            native(KeyEvent.KEYCODE_DPAD_RIGHT); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movie_detail_back")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("movie_more_like_this_title"); focused("focused_movie_more_like_this_card")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie"); assertEquals("Category Movie 13", title()); visible()
            native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_detail_play"); native(KeyEvent.KEYCODE_BACK)
            focused("focused_movie_more_like_this_card"); assertEquals("Category Movie 13", title()); visible()
            s.onActivity { assertNull(it.playedId); assertTrue(it.requests.all { request -> request.second == 12 }); assertTrue(it.threads.none { name -> name == "main" })
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(it.cacheDir, "movie-category-preview-controlled.png").outputStream().use { output -> image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }; image.recycle() }
            native(KeyEvent.KEYCODE_DPAD_UP); focused("movie_detail_play")
        }
    }
    @Test fun fakePlaybackCompositionReturnsToTheChildThenTheExactParentPoster() {
        launch().use { s ->
            waitTag("movie_more_like_this_title"); focused("movie_detail_play")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_movie_more_like_this_card")
            repeat(2) { native(KeyEvent.KEYCODE_DPAD_RIGHT) }; assertEquals("Category Movie 4", title())
            native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_detail_play")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("category_mock_playback_back")
            s.onActivity { assertEquals(4, it.playedId) }
            native(KeyEvent.KEYCODE_BACK); waitTag("movie_detail_play"); focused("movie_detail_play")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("Category Movie 4")
            native(KeyEvent.KEYCODE_BACK); focused("focused_movie_more_like_this_card")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie"); assertEquals("Category Movie 4", title()); visible()
        }
    }
    @Test fun delayedCategoryDoesNotStealActionFocusAndRefreshRemovingFocusedRowRecoversToPlay() {
        launch(blocked = true).use { s ->
            waitTag("movie_detail_play"); focused("movie_detail_play")
            native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movie_detail_watchlist")
            s.onActivity { it.release.complete(Unit) }; waitTag("movie_more_like_this_title"); focused("movie_detail_watchlist")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_movie_more_like_this_card")
            s.onActivity { it.removeCategory = true; it.holder.invalidateCachedCatalog() }
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("movie_more_like_this_row").fetchSemanticsNodes().isEmpty() }
            focused("movie_detail_play"); compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie")
            s.onActivity { assertNull(it.playedId) }
        }
    }
    @Test fun removedParentReturnsToSafeBackWithoutRequestingAMissingPlayControl() {
        launch().use { s ->
            waitTag("movie_more_like_this_title"); focused("movie_detail_play")
            native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_movie_more_like_this_card")
            native(KeyEvent.KEYCODE_DPAD_CENTER); focused("movie_detail_play")
            s.onActivity { it.removeParent = true; it.holder.invalidateCachedCatalog() }
            compose.waitUntil(8_000) { var missing = false; s.onActivity { missing = (it.holder.state as? MoviesCatalogState.Ready)?.movies?.singleOrNull { movie -> movie.streamId == 1 }?.available == false }; missing }
            native(KeyEvent.KEYCODE_BACK); waitTag("movie_detail_back"); focused("movie_detail_back")
            compose.onNodeWithTag("movie_detail_title").assertTextEquals("A Movie")
            compose.onNodeWithTag("movie_detail_play").assertDoesNotExist(); compose.onNodeWithTag("movie_more_like_this_row").assertDoesNotExist()
            s.onActivity { assertNull(it.playedId) }
        }
    }
    @Test fun singletonAndOptionalReadFailureLeaveHonestEmptySpaceAndUsableActions() {
        for (failure in listOf(false, true)) launch(singleton = !failure, fail = failure).use { s ->
            waitTag("movie_detail_play"); focused("movie_detail_play")
            compose.waitUntil(8_000) { var calls = 0; s.onActivity { calls = it.requests.size }; calls == 1 }
            compose.waitForIdle(); compose.onNodeWithTag("movie_more_like_this_row").assertDoesNotExist()
            compose.onAllNodesWithText("Unrelated").assertCountEquals(0)
            native(KeyEvent.KEYCODE_DPAD_RIGHT); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("movie_detail_back")
            native(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("movies_search_open")
            s.onActivity { assertNull(it.playedId) }
        }
    }
}

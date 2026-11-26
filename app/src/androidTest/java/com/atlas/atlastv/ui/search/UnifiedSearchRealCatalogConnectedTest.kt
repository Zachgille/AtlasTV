package com.atlas.atlastv.ui.search

import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Read-only catalog acceptance; no fixture seeding, file deletion or teardown writes. */
@RunWith(AndroidJUnit4::class)
class UnifiedSearchRealCatalogConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun typingKeepsFocusAndRemoteNavigatesBothResultRows() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val dao = AtlasDatabaseProvider.database(compose.activity).safeCatalogDao()
        val repository = SafeCatalogRepository(dao)
        val countsBefore = runBlocking(Dispatchers.IO) {
            dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series)
        }
        val savedStateBefore = savedStateHashes()
        val expected = runBlocking(Dispatchers.IO) { RoomUnifiedSearchSource(repository).search("love") }
        assertTrue("Requires a real catalog with Movies and Shows matching love", expected.rows.size == 2)
        val movieTag = "focused_unified_search_result_${expected.rows[0].items.first().id}"
        val showTag = "focused_unified_search_result_${expected.rows[1].items.first().id}"
        waitForTag("top_nav_item_search")
        compose.onNodeWithTag("top_nav_item_search").performClick()
        compose.onNodeWithTag("unified_search_field").performTextInput("lo")
        compose.onNodeWithTag("unified_search_field").performTextInput("v")
        compose.onNodeWithTag("unified_search_field").performTextInput("e")
        val enteredAt = SystemClock.elapsedRealtime()
        waitForTag("unified_search_result_${expected.rows[0].items.first().id}")
        compose.onNodeWithTag("unified_search_field").assertTextEquals("love")
        compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        val loadedAt = SystemClock.elapsedRealtime()
        println("Search acceptance: ${loadedAt - enteredAt} ms after final input; ${expected.totalCount} matches")
        compose.waitUntil(2_000) { SystemClock.elapsedRealtime() - loadedAt > 600 }
        compose.onNodeWithTag("unified_search_field").assertIsFocused()
        press("unified_search_field", Key.DirectionDown)
        waitForTag(movieTag)
        compose.onNodeWithTag(movieTag).assertIsFocused()
        compose.runOnIdle { compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        compose.waitUntil(8_000) {
            var hidden = false
            compose.activityRule.scenario.onActivity { hidden = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) != true }
            hidden
        }
        // Allow the system keyboard exit animation to settle before visual evidence.
        val hiddenAt = SystemClock.elapsedRealtime()
        compose.waitUntil(2_000) { SystemClock.elapsedRealtime() - hiddenAt > 600 }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.cacheDir, "search-clean-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        press(movieTag, Key.DirectionDown)
        waitForTag(showTag)
        press(showTag, Key.DirectionUp)
        waitForTag(movieTag)
        press(movieTag, Key.DirectionUp)
        compose.onNodeWithTag("unified_search_field").assertIsFocused()
        compose.onNodeWithTag("unified_search_clear").performClick()
        compose.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        assertEquals("", compose.onNodeWithTag("unified_search_field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("unified_search_field").assertIsFocused()
        compose.onNodeWithTag("unified_search_prompt").assertDoesNotExist()
        compose.onNodeWithTag("unified_search_back").performClick()
        compose.onNodeWithTag("home_screen").fetchSemanticsNode()
        assertEquals(savedStateBefore, savedStateHashes())
        val countsAfter = runBlocking(Dispatchers.IO) {
            dao.countForType(SafeCatalogContentTypes.Movie) to dao.countForType(SafeCatalogContentTypes.Series)
        }
        assertEquals(countsBefore, countsAfter)
        println("Saved account/watch-state hashes unchanged; catalog counts $countsAfter")
    }

    private fun press(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun savedStateHashes(): Map<String, String> = compose.activity.filesDir.listFiles().orEmpty()
        .filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("progress") || it.name.contains("watchlist")) }
        .associate { file ->
            file.name to MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
}

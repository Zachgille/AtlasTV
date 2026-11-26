package com.atlas.atlastv.ui.mylist

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in personal navigation only; no membership, playback or import mutations. */
class MyListRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    @Test fun actualCombinedListFiltersAndHomeReturnPreserveMembership() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitFor("top_nav_item_my_list"); compose.onNodeWithTag("top_nav_item_settings").assertIsDisplayed()
        val dao = AtlasDatabaseProvider.database(compose.activity).myListDao()
        val original = runBlocking(Dispatchers.IO) { dao.page("", 0, Int.MAX_VALUE).map { it.saved } }
        compose.onNodeWithTag("top_nav_item_my_list").performClick(); waitFor("my_list_screen")
        for (filter in MyListFilter.entries) {
            compose.onNodeWithTag("my_list_filter_${filter.name.lowercase()}").performClick()
            val count = runBlocking(Dispatchers.IO) { dao.count(filter.storageKey) }
            if (count == 0) { waitFor("my_list_empty"); focused("my_list_back") } else focused("my_list_tile_0")
            compose.onNodeWithTag("my_list_count").assertDoesNotExist()
            compose.onNodeWithTag("my_list_filter_${filter.name.lowercase()}").assertIsSelected()
        }
        compose.onNodeWithTag("my_list_filter_all").performClick(); waitFor("my_list_screen")
        if (original.isEmpty()) waitFor("my_list_empty") else focused("my_list_tile_0")
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.cacheDir, "my-list-refined.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); focused("top_nav_item_my_list")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); focused("top_nav_item_live_tv")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); focused("top_nav_item_settings")
        assertEquals(original, runBlocking(Dispatchers.IO) { dao.page("", 0, Int.MAX_VALUE).map { it.saved } })
        println("Real My List filters/return preserve ${original.size} saved titles")
    }
}

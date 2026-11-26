package com.atlas.atlastv.ui.home

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.ui.media.isDisplayFallback
import com.atlas.atlastv.ui.settings.ProviderSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in normal Home reading; no personal membership/progress actions or provider video. */
class HomeHeroCacheRealConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitFor(tag: String) {
        try { compose.waitUntil(45_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
        catch (error: Throwable) { throw AssertionError("Expected Home UI tag: $tag", error) }
    }
    private fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    @Test fun actualHomePreviewPersistsReopensFromDiskAndReturnsToTheExactEpisodeActionWithoutPersonalWrites() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        val db = AtlasDatabaseProvider.database(compose.activity)
        val progress = runBlocking { db.watchProgressDao().records() }
        val accountFile = File(compose.activity.filesDir, ProviderSettingsStore.FileName); val accountBytes = accountFile.readBytes()
        val account = ProviderSettingsStore(accountFile).load().settings.credentialsOrNull()!!
        waitFor("hero_synopsis"); val title = text("hero_title"); val synopsis = text("hero_synopsis")
        val row = runBlocking { db.homeHeroCacheDao().sizes().mapNotNull { db.homeHeroCacheDao().entry(it.providerKey, it.metadataKey) }
            .single { it.providerKey == providerHomeHeroKey(account) && HomeHeroCacheCodec.decode(it.payload)?.synopsis == synopsis } }
        assertTrue(row.payloadBytes <= HomeHeroCacheCodec.MaxEntryBytes)
        val metadata = HomeHeroCacheCodec.decode(row.payload)!!
        assertFalse(row.payload.contains(account.username)); assertFalse(row.payload.contains(account.password))
        assertFalse(row.payload.contains("http://")); assertFalse(row.payload.contains("https://")); assertFalse(row.payload.contains("episodes"))
        compose.activityRule.scenario.recreate()
        waitFor("hero_saved_info")
        waitFor("hero_synopsis")
        if (!metadata.backdrop.isDisplayFallback()) waitFor("loaded_detail_backdrop") else {
            compose.waitUntil(45_000) { compose.onAllNodes(hasTestTag("loaded_provider_poster") and
                hasAnyAncestor(hasTestTag("hero_poster_fallback"))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("hero_poster_fallback").assertIsDisplayed()
        }
        assertEquals(title, text("hero_title")); assertEquals(synopsis, text("hero_synopsis"))
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("home_hero_primary").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_RIGHT); compose.onNodeWithTag("home_hero_details").assertIsFocused()
        val shelf = hasAnyAncestor(hasTestTag("continue_watching_row"))
        compose.waitUntil(45_000) {
            val cards = compose.onAllNodes(hasTestTag("poster_card") and shelf, useUnmergedTree = true).fetchSemanticsNodes().size
            cards > 0 && compose.onAllNodes(hasTestTag("loaded_provider_poster") and shelf, useUnmergedTree = true).fetchSemanticsNodes().size == cards
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 3_000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "home-cache-real.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("series_detail_title"); compose.onNodeWithTag("series_detail_title").assertTextEquals(title)
        native(KeyEvent.KEYCODE_BACK); waitFor("hero_saved_info"); compose.onNodeWithTag("home_hero_details").assertIsFocused()
        assertEquals(title, text("hero_title")); assertEquals(progress, runBlocking { db.watchProgressDao().records() }); assertArrayEquals(accountBytes, accountFile.readBytes())
    }
}

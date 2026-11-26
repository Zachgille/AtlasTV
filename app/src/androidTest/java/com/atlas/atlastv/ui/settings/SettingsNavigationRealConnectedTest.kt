package com.atlas.atlastv.ui.settings

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in normal Settings; navigation only, with no save/refresh/toggle/provider video. */
class SettingsNavigationRealConnectedTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun key(code: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); ui.waitForIdle() }
    private fun waitTag(tag: String) { ui.waitUntil(20_000) { runCatching { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
    private fun focus(tag: String) { ui.waitUntil(8_000) { runCatching { ui.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun hashes(): Map<String,String> = ui.activity.filesDir.walkTopDown().filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("watchlist") || it.name.contains("progress") || it.name.endsWith("preferences_pb")) }
        .associate { it.relativeTo(ui.activity.filesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) } }
    @Test fun actualSectionsAndBothDiagnosticsBackRoutesPreservePersonalState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance") == "true")
        waitTag("top_nav_item_settings")
        val before = hashes()
        val db = AtlasDatabaseProvider.database(ui.activity)
        val progress = runBlocking(Dispatchers.IO) { db.watchProgressDao().records() }
        val counts = runBlocking(Dispatchers.IO) { db.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie) to db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series) }
        ui.onNodeWithTag("top_nav_item_settings").performClick(); waitTag("provider_sync_action"); focus("settings_back")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("provider_sync_action")
        ui.onNodeWithTag("settings_section_title").assertTextEquals("Library")
        ui.onNodeWithTag("provider_base_url_field").assertDoesNotExist(); ui.onNodeWithTag("advanced_diagnostics_entry").assertDoesNotExist()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(ui.activity.cacheDir,"settings-library-real.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("settings_autoplay")
        ui.onNodeWithTag("settings_section_title").assertTextEquals("Playback")
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("provider_save_action")
        for (tag in listOf("provider_base_url_field","provider_username_field","provider_password_field")) assertEquals("",ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        key(KeyEvent.KEYCODE_DPAD_UP); focus("settings_back"); key(KeyEvent.KEYCODE_DPAD_DOWN); focus("settings_section_provider")
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("advanced_diagnostics_entry"); key(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("debug_loader_screen")
        key(KeyEvent.KEYCODE_BACK); focus("advanced_diagnostics_entry"); ui.onNodeWithTag("settings_section_title").assertTextEquals("Advanced")
        key(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("debug_loader_screen"); ui.onNodeWithText("Back").performClick(); focus("advanced_diagnostics_entry")
        key(KeyEvent.KEYCODE_BACK); waitTag("home_screen")
        assertEquals(before,hashes()); assertEquals(progress,runBlocking(Dispatchers.IO) { db.watchProgressDao().records() })
        assertEquals(counts,runBlocking(Dispatchers.IO) { db.safeCatalogDao().countForType(SafeCatalogContentTypes.Movie) to db.safeCatalogDao().countForType(SafeCatalogContentTypes.Series) })
    }
}

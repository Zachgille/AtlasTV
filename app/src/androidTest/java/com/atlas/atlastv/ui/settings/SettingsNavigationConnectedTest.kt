package com.atlas.atlastv.ui.settings

import android.content.Intent
import android.view.KeyEvent
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.SettingsNavigationCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsNavigationConnectedTest {
    @get:Rule val ui = createEmptyComposeRule()
    private fun launch(missing: Boolean = false) = ActivityScenario.launch<SettingsNavigationCheckActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext, SettingsNavigationCheckActivity::class.java).putExtra("no_provider", missing))
    private fun key(code: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); ui.waitForIdle() }
    private fun waitTag(tag: String) { ui.waitUntil(12_000) { runCatching { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
    private fun focus(tag: String) { ui.waitUntil(8_000) { runCatching { ui.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun emptyFields() { for (tag in listOf("provider_base_url_field","provider_username_field","provider_password_field")) assertEquals("",ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text) }
    private fun fit(tag: String) {
        val box = ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val screen = ui.onNodeWithTag("settings_screen").fetchSemanticsNode().boundsInRoot
        assertTrue("$tag stays inside Settings", box.top >= screen.top && box.bottom <= screen.bottom && box.left >= screen.left && box.right <= screen.right)
        val text = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(text) }
        assertFalse("Complete $tag text",text.single().hasVisualOverflow)
    }
    private fun blue(tag: String) {
        val p = ui.onNodeWithTag(tag).captureToImage().toPixelMap(); val c = p[p.width/2,1]
        assertTrue("Painted focus on $tag", c.blue>.6 && c.blue>c.red*1.5)
    }
    @Test fun nativeSectionsKeepEverydayControlsSeparateFromPrivateProviderAndDiagnostics() {
        launch().use { scenario ->
            waitTag("provider_sync_action"); focus("settings_back")
            ui.onNodeWithTag("provider_base_url_field").assertDoesNotExist(); ui.onNodeWithTag("settings_autoplay").assertDoesNotExist(); ui.onNodeWithTag("advanced_diagnostics_entry").assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_DOWN); focus("settings_section_library"); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("provider_sync_action"); blue("provider_sync_action")
            fit("provider_sync_status_label"); fit("provider_sync_counts")
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"settings-library-controlled.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
            key(KeyEvent.KEYCODE_DPAD_LEFT); focus("settings_section_library"); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("settings_autoplay")
            ui.onNodeWithTag("settings_section_title").assertTextEquals("Playback"); ui.onNodeWithTag("provider_sync_action").assertDoesNotExist(); fit("settings_autoplay"); blue("settings_autoplay")
            key(KeyEvent.KEYCODE_DPAD_CENTER); scenario.onActivity { assertTrue(it.preferences.state.enabled); assertEquals(1,it.preferenceWrites) }
            key(KeyEvent.KEYCODE_DPAD_LEFT); focus("settings_section_playback"); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("provider_save_action"); emptyFields()
            ui.onNodeWithTag("provider_sync_action").assertDoesNotExist(); ui.onNodeWithTag("advanced_diagnostics_entry").assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_UP); focus("settings_back"); key(KeyEvent.KEYCODE_DPAD_DOWN); focus("settings_section_provider"); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("advanced_diagnostics_entry")
            key(KeyEvent.KEYCODE_DPAD_CENTER); waitTag("navigation_check_diagnostics"); key(KeyEvent.KEYCODE_BACK); focus("advanced_diagnostics_entry"); blue("advanced_diagnostics_entry")
            key(KeyEvent.KEYCODE_BACK); waitTag("navigation_check_home")
            scenario.onActivity { assertEquals(0,it.calls); assertEquals(0,it.writes); assertEquals(1,it.diagnostics) }
        }
    }
    @Test fun pendingRefreshKeepsSectionNavigationAndBackUsableWithoutCompletionFocusTheft() {
        launch().use { scenario ->
            waitTag("provider_sync_action"); scenario.onActivity { it.blockRefresh = true }
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("provider_sync_action"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.waitUntil(8_000) { ui.onNodeWithTag("provider_sync_action").fetchSemanticsNode().config[SemanticsProperties.Text].single().text == "Refreshing…" }
            repeat(3) { key(KeyEvent.KEYCODE_DPAD_CENTER) }; scenario.onActivity { assertEquals(1,it.calls) }
            key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); focus("settings_autoplay")
            scenario.onActivity { it.refreshGate.complete(Unit) }; ui.waitUntil(8_000) { var done=false; scenario.onActivity { done=!it.holder.state.isSyncing }; done }; focus("settings_autoplay")
            key(KeyEvent.KEYCODE_BACK); waitTag("navigation_check_home")
            scenario.onActivity { assertEquals(1,it.calls); assertEquals(0,it.preferenceWrites) }
        }
    }
    @Test fun providerSaveFailureRetainsDraftAndRecoveryAcrossSectionChanges() {
        launch().use { scenario ->
            waitTag("provider_sync_action"); ui.onNodeWithTag("settings_section_provider").performClick(); focus("provider_save_action")
            scenario.onActivity { it.holder.updateBaseUrl("http://example.invalid"); it.holder.updateUsername("replacement"); it.holder.updatePassword("opaque replacement"); it.failSave = true }
            key(KeyEvent.KEYCODE_DPAD_CENTER); ui.waitUntil(8_000) { var failed=false; scenario.onActivity { failed=it.holder.state.providerSaveFailed }; failed }
            focus("provider_save_action"); blue("provider_save_action"); fit("provider_action_message")
            ui.onNodeWithTag("settings_section_library").performClick(); focus("provider_sync_action")
            ui.onNodeWithTag("provider_password_field").assertDoesNotExist()
            ui.onNodeWithTag("settings_section_provider").performClick(); focus("provider_save_action"); ui.onNodeWithTag("provider_save_action").assertTextEquals("Retry save")
            val masked = ui.onNodeWithTag("provider_password_field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            assertEquals("opaque replacement".length, masked.length); assertTrue(masked.all { it == '•' })
            scenario.onActivity { assertEquals("opaque replacement",it.holder.state.passwordInput) }
            scenario.onActivity { it.failSave = false }; key(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.waitUntil(8_000) { var done=false; scenario.onActivity { done=!it.holder.state.providerSaving && !it.holder.state.providerSaveFailed }; done }
            focus("provider_save_action"); emptyFields(); scenario.onActivity { assertEquals(0,it.calls) }
        }
        launch(missing = true).use { scenario ->
            waitTag("provider_sync_action"); ui.onNodeWithTag("provider_sync_action").assertTextEquals("Set up provider")
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_CENTER); focus("provider_base_url_field")
            scenario.onActivity { assertEquals(0,it.calls); assertEquals(0,it.writes); it.window.insetsController?.hide(WindowInsets.Type.ime()) }
        }
    }
}

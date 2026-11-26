package com.atlas.atlastv.ui.settings

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.xtream.network.ProviderEndpointAddresses
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Normal preview UI. Cancel is read-only; the separate setup method requires explicit supplied addresses. */
class ProviderBackupsUiConnectedTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store get() = ProviderSettingsStore(File(instrumentation.targetContext.filesDir, ProviderSettingsStore.FileName))
    private fun tag(value: String) { ui.waitUntil(15_000) { runCatching { ui.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
    private fun open() {
        tag("top_nav_item_settings"); ui.onNodeWithTag("top_nav_item_settings").performClick()
        tag("settings_section_provider"); ui.onNodeWithTag("settings_section_provider").performClick()
        tag("provider_backups_open"); ui.onNodeWithTag("provider_backups_open").performClick(); tag("provider_backups_dialog")
    }
    @Test fun backupDialogHasVisibleRemoteActionsAndCancelKeepsTheSavedAccount() {
        assumeTrue(store.load().settings.credentialsOrNull() != null)
        val before = store.load().settings
        ActivityScenario.launch(MainActivity::class.java).use {
            open()
            ui.onNodeWithTag("provider_backups_save").assertIsDisplayed()
            ui.onNodeWithTag("provider_backups_cancel").assertIsDisplayed()
            ui.onNodeWithTag("provider_backup_one").performTextReplacement("http://cancel.example.invalid")
            ui.onNodeWithTag("provider_backups_cancel").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            ui.onNodeWithTag("provider_backups_cancel").assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.waitForIdle()
            ui.onNodeWithTag("provider_backups_dialog").assertDoesNotExist()
            ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("provider_test_connection_action").assertIsFocused(); true }.getOrDefault(false) }
            assertTrue("Cancel must leave saved provider details unchanged",
                before.copy(preferredBaseUrl = null) == store.load().settings.copy(preferredBaseUrl = null))
        }
    }
    @Test fun configureSuppliedBackupsThroughTheNormalSaveAction() {
        val supplied = InstrumentationRegistry.getArguments().getString("backup_addresses")
        assumeTrue("Explicit address arguments required", supplied != null)
        val addresses = supplied!!.split(',').mapNotNull(ProviderEndpointAddresses::normalize).distinct()
        val before = store.load().settings
        val primary = ProviderEndpointAddresses.normalize(before.baseUrl)
        assumeTrue("Saved account must belong to the supplied provider", primary in addresses)
        val backups = addresses.filter { it != primary }.take(2)
        assertEquals(2, backups.size)
        ActivityScenario.launch(MainActivity::class.java).use {
            open()
            ui.onNodeWithTag("provider_backup_one").performTextReplacement(backups[0])
            ui.onNodeWithTag("provider_backup_two").performTextReplacement(backups[1])
            ui.onNodeWithTag("provider_backups_save").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            ui.onNodeWithTag("provider_backups_save").assertIsFocused()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("provider_backups_dialog").fetchSemanticsNodes().isEmpty() }
            val after = store.load().settings
            assertEquals(backups, after.alternateBaseUrls)
            assertTrue("The primary address and opaque account credentials must remain unchanged",
                before.baseUrl == after.baseUrl && before.username == after.username && before.password == after.password)
        }
    }
}

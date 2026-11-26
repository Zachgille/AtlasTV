package com.atlas.atlastv.ui.settings

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.PlaybackPreferencesCheckActivity
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

/** Only isolated Settings/provider and cache DataStore files, never personal account/preferences. */
class PlaybackPreferencesConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(id: String = UUID.randomUUID().toString(), failSave: Boolean = false, failLoad: Boolean = false) =
        ActivityScenario.launch<PlaybackPreferencesCheckActivity>(Intent(ApplicationProvider.getApplicationContext(), PlaybackPreferencesCheckActivity::class.java)
            .putExtra("check_id", id).putExtra("fail_save", failSave).putExtra("fail_load", failLoad))
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun ready(activity: PlaybackPreferencesCheckActivity) {
        compose.waitUntil(10_000) { !activity.preferences.state.loading }
        compose.onNodeWithTag("settings_section_playback").performClick()
        compose.onNodeWithTag("settings_autoplay").assertIsDisplayed().assertIsFocused()
    }
    @Test fun settingsToggleIsRemoteReachableAndActualDataStoreSurvivesReopening() {
        val id = UUID.randomUUID().toString()
        launch(id).use { scenario ->
            lateinit var activity: PlaybackPreferencesCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            compose.onNodeWithTag("settings_autoplay").assertTextEquals("Autoplay next episode: Off")
            compose.onNodeWithTag("settings_autoplay").assertIsFocused()
            native(KeyEvent.KEYCODE_DPAD_CENTER); compose.waitUntil(5_000) { activity.preferences.state.enabled }
            assertTrue(runBlocking { activity.store.loadAutoplay() }); assertTrue(activity.preferenceFile.isFile)
            compose.onNodeWithTag("settings_autoplay").assertIsFocused()
            runBlocking { activity.storageScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        launch(id).use { scenario ->
            lateinit var activity: PlaybackPreferencesCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            compose.onNodeWithTag("settings_autoplay").assertTextEquals("Autoplay next episode: On")
            native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { !activity.preferences.state.saving && !activity.preferences.state.enabled }
            assertFalse(runBlocking { activity.store.loadAutoplay() })
        }
    }
    @Test fun failedWriteIsSafeOffWithFocusedRetryAndEventuallySavesTheSameRequest() {
        launch(failSave = true).use { scenario ->
            lateinit var activity: PlaybackPreferencesCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { activity.preferences.state.saveFailed }
            compose.onNodeWithTag("settings_autoplay").assertIsFocused().assertTextEquals("Autoplay: Off · Retry save")
            assertFalse(activity.preferences.state.enabled)
            compose.runOnIdle { activity.failSave = false }
            native(KeyEvent.KEYCODE_DPAD_CENTER); compose.waitUntil(5_000) { activity.preferences.state.enabled }
            assertEquals(2, activity.writes); assertTrue(runBlocking { activity.store.loadAutoplay() })
        }
    }
    @Test fun failedReadOffersRemoteRetryAndBackStaysReachable() {
        launch(failLoad = true).use { scenario ->
            lateinit var activity: PlaybackPreferencesCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            compose.onNodeWithTag("settings_autoplay").assertTextEquals("Retry playback settings")
            compose.runOnIdle { activity.failLoad = false }
            native(KeyEvent.KEYCODE_DPAD_CENTER); compose.waitUntil(5_000) { activity.preferences.state.loaded }
            assertFalse(activity.preferences.state.enabled)
            native(KeyEvent.KEYCODE_DPAD_LEFT); compose.onNodeWithTag("settings_section_playback").assertIsFocused()
            native(KeyEvent.KEYCODE_DPAD_UP); native(KeyEvent.KEYCODE_DPAD_UP); compose.onNodeWithTag("settings_back").assertIsFocused()
        }
    }
    @Test fun corruptedNonessentialPreferenceRecoversToOffAndCanSaveAgain() {
        val id = UUID.randomUUID().toString()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.cacheDir, "autoplay-check-$id.preferences_pb").writeBytes(byteArrayOf(0xff.toByte(), 0, 0xfe.toByte()))
        launch(id).use { scenario ->
            lateinit var activity: PlaybackPreferencesCheckActivity; scenario.onActivity { activity = it }; ready(activity)
            assertTrue(activity.preferences.state.loaded); assertFalse(activity.preferences.state.loadFailed)
            assertFalse(activity.preferences.state.enabled)
            native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(5_000) { activity.preferences.state.enabled }
            assertTrue(runBlocking { activity.store.loadAutoplay() })
        }
    }
}

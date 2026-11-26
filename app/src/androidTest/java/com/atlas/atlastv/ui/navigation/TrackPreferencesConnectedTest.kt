package com.atlas.atlastv.ui.navigation

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.TrackPreferencesCheckActivity
import com.atlas.atlastv.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class TrackPreferencesConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(id: String = UUID.randomUUID().toString(), failSave: Boolean = false, failLoad: Boolean = false, stallRead: Boolean = false) =
        ActivityScenario.launch<TrackPreferencesCheckActivity>(Intent(ApplicationProvider.getApplicationContext(), TrackPreferencesCheckActivity::class.java)
            .putExtra("check_id", id).putExtra("fail_save", failSave).putExtra("fail_load", failLoad).putExtra("stall_read", stallRead))
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun waitFor(tag: String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun menu() { waitFor("playback_track_options_control"); native(KeyEvent.KEYCODE_MEDIA_PAUSE); native(KeyEvent.KEYCODE_DPAD_UP); waitFor("playback_track_options_panel") }
    private fun next() {
        waitFor("playback_next_episode_control")
        compose.onNodeWithTag("playback_play_pause_control").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        native(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("playback_next_episode_control").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun prefix(value: String) = SemanticsMatcher("track prefix $value") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("playback_track_${value}_") == true }
    private fun track(type: String, language: String, forced: Boolean = false) = compose.onNode(prefix(type) and hasText(language, substring = true) and
        (if (forced) hasText("Forced", substring = true) else !hasText("Forced", substring = true)))
    private fun selected(type: String, language: String, forced: Boolean = false) {
        compose.waitUntil(8_000) { runCatching { track(type, language, forced).assert(hasText("✓")); true }.getOrDefault(false) }
    }
    private fun saved(activity: TrackPreferencesCheckActivity) { compose.waitUntil(8_000) { !activity.preferences.state.saving && !activity.preferences.state.loading } }
    private fun done() { compose.onNodeWithTag("playback_track_done").performClick(); compose.waitForIdle() }
    private fun chooseUnknown(option: SemanticsNodeInteraction) {
        compose.mainClock.autoAdvance = false
        try {
            option.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
            // Media3 selection and the menu's close callback post work to the UI thread.
            // Pump frames without fast-forwarding through the transient notice's lifetime.
            try { compose.waitUntil(5_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("playback_track_choice_notice").fetchSemanticsNodes().isNotEmpty()
            } } catch (failure: Throwable) { throw AssertionError(compose.onRoot().printToString(), failure) }
            compose.onNodeWithTag("playback_track_choice_notice").assertTextEquals("Language unknown · for this video only")
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun languageAndSubtitleChoiceSurviveNativeNextEpisodeReorderedTracksAndRealDataStoreReopen() {
        val id = UUID.randomUUID().toString()
        launch(id).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); selected("audio", "English"); compose.onNodeWithTag("playback_track_subtitle_off").assert(hasText("✓"))
            runBlocking { activity.store.saveAutoplay(true) }
            track("audio", "French").performClick(); menu(); selected("audio", "French")
            track("subtitle", "English").performClick(); saved(activity)
            assertEquals("fr", runBlocking { activity.store.loadTracks() }.audio.language)
            assertEquals("en", runBlocking { activity.store.loadTracks() }.subtitles?.language)
            next()
            compose.waitUntil(10_000) { activity.clip == "b" }; menu()
            selected("audio", "French"); selected("subtitle", "English")
            assertTrue(runBlocking { activity.store.loadAutoplay() })
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(activity.cacheDir, "track-preferences-refined.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            done(); compose.runOnIdle { activity.start("a", asMovie = true) }; menu()
            selected("audio", "French"); selected("subtitle", "English")
            runBlocking { activity.storageScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        launch(id).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); selected("audio", "French"); selected("subtitle", "English")
            assertTrue(runBlocking { activity.store.loadAutoplay() })
        }
    }
    @Test fun subtitleOffPersistsAcrossNextEpisodeMoviePlaybackAndReopening() {
        val id = UUID.randomUUID().toString()
        launch(id).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); track("subtitle", "English").performClick(); saved(activity); menu(); selected("subtitle", "English")
            compose.onNodeWithTag("playback_track_subtitle_off").performClick(); saved(activity)
            next(); compose.waitUntil(10_000) { activity.clip == "b" }; menu()
            compose.onNodeWithTag("playback_track_subtitle_off").assert(hasText("✓")); done()
            compose.runOnIdle { activity.start("a", asMovie = true) }; menu()
            compose.onNodeWithTag("playback_track_subtitle_off").assert(hasText("✓"))
            assertNull(runBlocking { activity.store.loadTracks() }.subtitles)
            runBlocking { activity.storageScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        launch(id).use { menu(); compose.onNodeWithTag("playback_track_subtitle_off").assert(hasText("✓")) }
    }
    @Test fun missingPreferredSubtitlesUseOffWithoutSelectingForeignOrErasingThePreference() {
        launch().use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); track("subtitle", "English").performClick(); saved(activity)
            compose.runOnIdle { activity.start("missing") }; menu()
            compose.onNodeWithTag("playback_track_subtitle_off").assert(hasText("✓"))
            track("subtitle", "French").assert(!hasText("✓"))
            assertEquals("en", runBlocking { activity.store.loadTracks() }.subtitles?.language)
            done(); compose.runOnIdle { activity.start("b") }; menu(); selected("subtitle", "English")
        }
    }
    @Test fun forcedSubtitlePreferenceMatchesItsRoleAfterReordering() {
        launch().use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); track("subtitle", "English", forced = true).performClick(); saved(activity)
            assertTrue(runBlocking { activity.store.loadTracks() }.subtitles!!.forced)
            next(); compose.waitUntil(10_000) { activity.clip == "b" }; menu()
            selected("subtitle", "English", forced = true)
            track("subtitle", "English").assert(!hasText("✓"))
        }
    }
    @Test fun unlabelledTrackRemainsUsableForThisVideoWithoutInventingASavedLanguage() {
        assertNull(normalizedTrackLanguage("und")); assertNull(normalizedTrackLanguage("zxx"))
        launch().use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu()
            val unknown = compose.onNode(prefix("audio") and !hasText("English", substring = true) and !hasText("French", substring = true))
            chooseUnknown(unknown)
            assertEquals("en", activity.preferences.state.preferences.audio.language)
            assertEquals(0, activity.writes)
            menu(); compose.onNode(prefix("audio") and !hasText("English", substring = true) and !hasText("French", substring = true)).assert(hasText("✓"))
            val unknownSubtitle = prefix("subtitle") and !hasTestTag("playback_track_subtitle_off") and
                !hasText("English", substring = true) and !hasText("French", substring = true)
            chooseUnknown(compose.onNode(unknownSubtitle))
            assertNull(activity.preferences.state.preferences.subtitles); assertEquals(0, activity.writes)
            menu(); compose.onNode(unknownSubtitle).assert(hasText("✓"))
            done(); compose.runOnIdle { activity.start("b") }; menu(); selected("audio", "English")
            compose.onNodeWithTag("playback_track_subtitle_off").assert(hasText("✓"))
        }
    }
    @Test fun failedLanguageSaveHasRemoteRetryWithoutLosingSelectionOrRetryFocus() {
        launch(failSave = true).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); track("audio", "French").performClick(); saved(activity); assertTrue(activity.preferences.state.saveFailed)
            menu(); selected("audio", "French"); waitFor("playback_language_preference_retry")
            compose.onNodeWithTag("playback_track_done").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
            native(KeyEvent.KEYCODE_DPAD_UP); compose.onNodeWithTag("playback_language_preference_retry").assertIsFocused()
            compose.runOnIdle { activity.failSave = false }; native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(8_000) { !activity.preferences.state.saveFailed && !activity.preferences.state.saving }
            compose.onNodeWithTag("playback_language_preference_retry").assertIsFocused()
            assertEquals("fr", runBlocking { activity.store.loadTracks() }.audio.language); assertEquals(2, activity.writes)
            native(KeyEvent.KEYCODE_DPAD_DOWN); compose.onNodeWithTag("playback_track_done").assertIsFocused()
            native(KeyEvent.KEYCODE_BACK); compose.onNodeWithTag("playback_track_options_panel").assertDoesNotExist()
        }
    }
    @Test fun failedLoadDoesNotPreventLocalVideoAndExplicitRetryRestoresPreferences() {
        launch(failLoad = true).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); selected("audio", "English"); waitFor("playback_language_preference_retry")
            compose.runOnIdle { activity.failLoad = false }
            compose.onNodeWithTag("playback_language_preference_retry").performClick()
            compose.waitUntil(8_000) { !activity.preferences.state.loading && !activity.preferences.state.loadFailed }
            selected("audio", "English")
        }
    }
    @Test fun lateSavedLanguagesCannotReplaceManualAudioOrStealFocusWhileOtherFieldsLoad() {
        val id = UUID.randomUUID().toString()
        launch(id).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            runBlocking { activity.store.saveAudio(LanguageTrackPreference("fr")); activity.store.saveSubtitles(LanguageTrackPreference("fr"))
                activity.storageScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        launch(id, stallRead = true).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); selected("audio", "English"); assertTrue(activity.preferences.state.loading)
            track("audio", "English").performClick(); menu()
            val focusedTag = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.TestTag]
            compose.runOnIdle { activity.loadRelease.complete(Unit) }; saved(activity)
            selected("audio", "English"); selected("subtitle", "French")
            compose.onNodeWithTag(focusedTag).assertIsFocused()
            assertEquals("en", runBlocking { activity.store.loadTracks() }.audio.language)
            assertEquals("fr", runBlocking { activity.store.loadTracks() }.subtitles?.language)
        }
    }
    @Test fun corruptedNonessentialSettingsRecoverToEnglishAndOffAndCanSaveAgain() {
        val id = UUID.randomUUID().toString()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        java.io.File(context.cacheDir, "track-preference-check-$id.preferences_pb").writeBytes(byteArrayOf(0xff.toByte(), 0, 0xfe.toByte()))
        launch(id).use { scenario ->
            lateinit var activity: TrackPreferencesCheckActivity; scenario.onActivity { activity = it }
            menu(); selected("audio", "English"); compose.onNodeWithTag("playback_track_subtitle_off").assert(hasText("✓"))
            assertFalse(activity.preferences.state.loadFailed)
            track("subtitle", "English").performClick(); saved(activity)
            assertEquals("en", runBlocking { activity.store.loadTracks() }.subtitles?.language)
        }
    }
}

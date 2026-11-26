package com.atlas.atlastv.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Actual Media3 groups in local files; no provider, preferences or watch files. */
@androidx.annotation.OptIn(UnstableApi::class)
class TrackPreferenceControllerConnectedTest {
    private fun <T> main(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun waitFor(block: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (main(block)) return
            Thread.sleep(50)
        }
        fail("Expected local Media3 selection did not arrive")
    }
    private fun run(block: (ExoPlayer, PlaybackTrackPreferencesController, MutableList<Pair<PlaybackTrackType, LanguageTrackPreference?>>) -> Unit) {
        val choices = mutableListOf<Pair<PlaybackTrackType, LanguageTrackPreference?>>()
        val player = main { ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setRenderersFactory(LivePlaybackRequestConfig.renderersFactory(ApplicationProvider.getApplicationContext())).build() }
        val controller = main { PlaybackTrackPreferencesController(player) { type, preference -> choices += type to preference }.also { current ->
            player.addListener(object : Player.Listener { override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { current.apply() } })
        } }
        try { block(player, controller, choices) } finally { main { player.release() } }
    }
    private fun start(player: Player, file: String) = main { player.setMediaItem(MediaItem.fromUri("asset:///track-preferences-$file.mkv")); player.prepare() }
    private fun options(player: Player, type: PlaybackTrackType) = player.currentTracks.playbackTrackOptions(type)
    private fun hasSelected(player: Player, type: PlaybackTrackType, language: String, forced: Boolean = false) =
        options(player, type).any { it.selected && it.language == language && it.forced == forced }
    @Test fun retryingTheSameVideoPreservesBothManualSelectionsWithoutSavingThemAgain() = run { player, controller, choices ->
        start(player, "a"); waitFor { options(player, PlaybackTrackType.Subtitle).size == 4 }
        main {
            assertTrue(controller.select(options(player, PlaybackTrackType.Audio).first { it.language == "fr" }) == true)
            assertTrue(controller.select(options(player, PlaybackTrackType.Subtitle).first { it.language == "en" && !it.forced }) == true)
        }
        waitFor { hasSelected(player, PlaybackTrackType.Audio, "fr") && hasSelected(player, PlaybackTrackType.Subtitle, "en") }
        main { player.stop(); controller.apply() }; start(player, "a")
        waitFor { hasSelected(player, PlaybackTrackType.Audio, "fr") && hasSelected(player, PlaybackTrackType.Subtitle, "en") }
        main { assertEquals(2, choices.size) }
    }
    @Test fun newGroupsAreResolvedAgainAndStaleOrUnsupportedOptionsCannotApplyOrSave() = run { player, controller, choices ->
        main { controller.update(TrackPreferences(subtitles = LanguageTrackPreference("en"))) }
        start(player, "a"); waitFor { hasSelected(player, PlaybackTrackType.Audio, "en") && hasSelected(player, PlaybackTrackType.Subtitle, "en") }
        val old = main { options(player, PlaybackTrackType.Audio).first { it.language == "en" } }
        start(player, "b"); waitFor { hasSelected(player, PlaybackTrackType.Audio, "en") && hasSelected(player, PlaybackTrackType.Subtitle, "en") }
        main {
            assertNull(controller.select(old)); assertNull(controller.select(old.copy(trackIndex = 500)))
            assertTrue(choices.isEmpty())
        }
    }
    @Test fun manualSubtitleLanguageReturnsAfterMissingGroupsButExplicitOffStaysOff() = run { player, controller, choices ->
        start(player, "a"); waitFor { options(player, PlaybackTrackType.Subtitle).size == 4 }
        main { controller.select(options(player, PlaybackTrackType.Subtitle).first { it.language == "en" && !it.forced }) }
        start(player, "missing"); waitFor { options(player, PlaybackTrackType.Subtitle).size == 1 &&
            androidx.media3.common.C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes }
        start(player, "b"); waitFor { hasSelected(player, PlaybackTrackType.Subtitle, "en") }
        main { controller.subtitlesOff() }; start(player, "a")
        waitFor { options(player, PlaybackTrackType.Subtitle).size == 4 &&
            androidx.media3.common.C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes }
        main { assertEquals(2, choices.size); assertNull(choices.last().second) }
    }
}

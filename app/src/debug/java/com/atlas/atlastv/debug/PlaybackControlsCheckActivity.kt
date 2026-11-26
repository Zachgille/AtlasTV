package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.components.PlaybackTrackMenu
import com.atlas.atlastv.ui.debug.DebugLivePlaybackScreen
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.theme.*

/** Generated local video / in-memory track list only; no account, catalog, network or watch-file access. */
class PlaybackControlsCheckActivity : ComponentActivity() {
    var menuFixture by mutableStateOf(false)
    var destination by mutableStateOf(false)
    var selected: PlaybackTrackOption? = null
    var subtitlesOff = false
    var reorder by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AtlasTvTheme {
                if (destination) Text("Returned", Modifier.testTag("playback_controls_destination"))
                else if (menuFixture) {
                    val audio = (0..11).map { PlaybackTrackOption(PlaybackTrackType.Audio, 0, it, if (it == 10) "English · 5.1" else "Audio ${it + 1}", it == 10) }.let { if (reorder) it.reversed() else it }
                    val subtitles = (0..17).map { PlaybackTrackOption(PlaybackTrackType.Subtitle, 1, it, "Subtitle ${it + 1}", false) }
                    Box(Modifier.fillMaxSize().background(AtlasBackground).onPreviewKeyEvent {
                        // Same menu-open guard as the player: Up belongs to the menu.
                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && shouldOpenTrackMenu(true, audio.size, subtitles.size)
                    }, contentAlignment = Alignment.Center) {
                        PlaybackTrackMenu(audio, subtitles, true,
                            onSelect = { selected = it; destination = true },
                            onSubtitlesOff = { subtitlesOff = true; destination = true },
                            onDismiss = { destination = true })
                    }
                } else DebugLivePlaybackScreen(
                    stream = LiveTvChannel("Local movie (2020)", "", 0, "mp4", ""),
                    streamUrl = "asset:///progress-check.mp4",
                    progressRecord = WatchProgressRecord(WatchProgressContentType.Movie, 0, title = "Local controls check", positionMs = 0, updatedAtMs = 0),
                    onBack = { destination = true },
                )
            }
        }
    }
}

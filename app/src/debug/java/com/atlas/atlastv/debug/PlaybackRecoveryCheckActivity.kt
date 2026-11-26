package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.debug.DebugLivePlaybackScreen
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import java.io.File
import java.util.UUID

/** Isolated physical-device recovery check. Never reads credentials or modifies the user's data. */
class PlaybackRecoveryCheckActivity : ComponentActivity() {
    lateinit var progressStore: WatchProgressStore
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        progressStore = WatchProgressStore(File(cacheDir, "recovery-check-${UUID.randomUUID()}.json"))
        val record = WatchProgressRecord(
            contentType = WatchProgressContentType.Movie,
            streamId = 0,
            title = "Playback recovery check",
            positionMs = 255_000L,
            updatedAtMs = 0L,
        )
        setContent {
            AtlasTvTheme {
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { progressStore.saveRecordAsync(record); ready = true }
            if (ready) {
            DebugLivePlaybackScreen(
                stream = LiveTvChannel("Playback recovery check", "", 0, "mp4", "local test"),
                streamUrl = "http://127.0.0.1:1/unavailable.mp4",
                initialPositionMs = 255_000L,
                progressRecord = record,
                progressStore = progressStore,
                onBack = { finish() },
            )
            }
            }
        }
    }
}

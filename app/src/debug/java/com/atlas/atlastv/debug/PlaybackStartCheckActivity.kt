package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.debug.PreparingPlaybackScreen
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.channels.Channel

/** Controlled resume lookup with no storage, provider or personal-data dependency. */
class PlaybackStartCheckActivity : ComponentActivity() {
    val responses = Channel<Result<WatchProgressSnapshot>>(Channel.UNLIMITED)
    @Volatile var starts = 0
    @Volatile var lookups = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AtlasTvTheme {
                val scope = rememberCoroutineScope()
                val holder = remember { PlaybackStartStateHolder(scope) { lookups++; responses.receive().getOrThrow() } }
                val state by holder.state.collectAsState()
                LaunchedEffect(Unit) { holder.start(WatchProgressRecord(WatchProgressContentType.Movie, 1, title = "Test", positionMs = 0, updatedAtMs = 0)) }
                LaunchedEffect(state) { if (state is PlaybackStartState.Ready) starts++ }
                PreparingPlaybackScreen(state == PlaybackStartState.Failed, holder::retry) { holder.cancel(); finish() }
            }
        }
    }
}

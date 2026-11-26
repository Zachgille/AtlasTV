package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.tv.material3.Text
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.debug.DebugLivePlaybackScreen
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.series.SeriesEpisodeItem
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Real local Media3 tracks with isolated cache DataStore; no provider/personal-state access. */
class TrackPreferencesCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var store: DataStorePlaybackPreferencesStore
    lateinit var preferences: TrackPreferencesStateHolder
    lateinit var preferenceFile: File
    var failSave = false; var failLoad = false; var writes = 0
    var clip by mutableStateOf("a"); private set
    var generation by mutableStateOf(0); private set
    var destination by mutableStateOf(false)
    var movie by mutableStateOf(false); private set
    val loadRelease = CompletableDeferred<Unit>()
    val next = SeriesEpisodeItem("Next local episode", 1, 2, 12, "mkv", "")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("check_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: UUID.randomUUID().toString()
        preferenceFile = File(cacheDir, "track-preference-check-$id.preferences_pb")
        store = DataStorePlaybackPreferencesStore(PreferenceDataStoreFactory.create(scope = storageScope,
            corruptionHandler = playbackPreferencesCorruptionHandler(), produceFile = { preferenceFile }))
        failSave = intent.getBooleanExtra("fail_save", false); failLoad = intent.getBooleanExtra("fail_load", false)
        if (!intent.getBooleanExtra("stall_read", false)) loadRelease.complete(Unit)
        preferences = TrackPreferencesStateHolder(object : TrackPreferencesStore {
            override suspend fun loadTracks(): TrackPreferences {
                val saved = store.loadTracks(); loadRelease.await()
                if (failLoad) error("private diagnostic"); return saved
            }
            override suspend fun saveAudio(preference: LanguageTrackPreference) { writes++; if (failSave) error("private diagnostic"); store.saveAudio(preference) }
            override suspend fun saveSubtitles(preference: LanguageTrackPreference?) { writes++; if (failSave) error("private diagnostic"); store.saveSubtitles(preference) }
        }, scope)
        setContent { AtlasTvTheme {
            if (destination) Text("Returned", Modifier.testTag("track_preferences_destination"))
            else key(generation) { DebugLivePlaybackScreen(
                stream = LiveTvChannel(if (movie) "Local Movie" else "Local Episode", "", 0, "mkv", ""),
                streamUrl = "asset:///track-preferences-$clip.mkv",
                progressRecord = WatchProgressRecord(if (movie) WatchProgressContentType.Movie else WatchProgressContentType.SeriesEpisode,
                    if (movie) 77 else if (clip == "a") 11 else 12, seriesId = if (movie) null else 7,
                    title = "Local language check", seriesTitle = if (movie) null else "Local series", positionMs = 0, updatedAtMs = 0),
                trackPreferences = preferences,
                nextEpisodeState = if (movie) NextEpisodeState.Idle else NextEpisodeState.Available(next),
                onNextEpisode = { start("b") }, onBack = { destination = true }) }
        } }
    }
    fun start(value: String, asMovie: Boolean = false) { clip = value; movie = asMovie; generation++; destination = false }
    override fun onDestroy() { scope.cancel(); storageScope.cancel(); super.onDestroy() }
}

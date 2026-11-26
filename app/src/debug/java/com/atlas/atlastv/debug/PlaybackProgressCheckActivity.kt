package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.room.Room
import com.atlas.atlastv.data.db.AtlasDatabase
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.debug.DebugLivePlaybackScreen
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Local generated video and a separate cache database. Never opens personal account/catalog files. */
class PlaybackProgressCheckActivity : ComponentActivity() {
    lateinit var store: WatchProgressStore
        private set
    lateinit var writer: WatchProgressCoordinator
        private set
    @Volatile var savedOnEntry: WatchProgressRecord? = null
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("check_id")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) } ?: "process-restart"
        val database = Room.databaseBuilder(applicationContext, AtlasDatabase::class.java, File(cacheDir, "progress-check-$name.db").absolutePath).build()
        store = WatchProgressStore(File(cacheDir, "progress-check-$name.json"), WatchProgressRoomStore(database.watchProgressDao()))
        writer = WatchProgressCoordinator.forStore(store)
        setContent {
            AtlasTvTheme {
                var ready by remember { mutableStateOf<WatchProgressRecord?>(null) }
                LaunchedEffect(Unit) {
                    val base = WatchProgressRecord(WatchProgressContentType.Movie, 1, title = "Local playback progress check", durationMs = 600_000, positionMs = 255_000, updatedAtMs = System.currentTimeMillis())
                    if (intent.getBooleanExtra("reset", false)) {
                        writer.awaitSaved()
                        store.saveRecordsAsync(listOf(base))
                    }
                    val snapshot = writer.loadSnapshot()
                    val existing = snapshot.records.firstOrNull() ?: base
                    if (snapshot.records.isEmpty()) store.saveRecordAsync(existing)
                    savedOnEntry = existing
                    withContext(Dispatchers.IO) {
                        File(cacheDir, "progress-check-$name-entry.txt").writeText("position=${existing.positionMs};completed=${existing.completed}")
                    }
                    ready = existing
                }
                ready?.let { record ->
                    DebugLivePlaybackScreen(
                        stream = LiveTvChannel("Local progress check", "", 1, "mp4", "Local generated test video"),
                        streamUrl = "asset:///progress-check.mp4",
                        initialPositionMs = WatchProgressPolicy.resumePositionMs(record),
                        progressRecord = record,
                        progressStore = store,
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}

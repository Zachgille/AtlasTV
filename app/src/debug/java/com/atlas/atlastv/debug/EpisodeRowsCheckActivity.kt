package com.atlas.atlastv.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.media.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Memory-only catalog/watch state; thumbnail bytes are unique cache entries removed on teardown. */
class EpisodeRowsCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val series = SeriesItem("A Clean Show (2020)", "Shows", 7)
    private val thumbnail = atlasImageRefFromRaw("https://episode-check.invalid/${UUID.randomUUID()}.png")
    private val cache by lazy { AtlasProviderImageCache(File(filesDir, AtlasProviderImageCache.DirectoryName)) }
    var records = listOf(
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 11, 7, "Pilot", durationMs = 2_520_000, positionMs = 630_000, updatedAtMs = 1),
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 12, 7, "Second", durationMs = 2_520_000, positionMs = 2_520_000, updatedAtMs = 2, completed = true),
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 13, 7, "Unknown", positionMs = 120_000, updatedAtMs = 3),
    )
    val episodes = (1..24).flatMap { season -> (1..64).map { number ->
        val id = if (season == 1) 10 + number else season * 1000 + number
        SeriesEpisodeItem(if (id == 11) "Pilot With A Very Long But Readable Episode Name" else "A Clean Show (2020) - S${season}E$number",
            season, number, id, "mp4", "", if (id == 13) null else 2_520_000)
    } }
    private var opened by mutableStateOf<Int?>(null)
    var openedEpisodeId: Int? = null; private set
    private var request by mutableStateOf(1)
    val holder = SeriesCatalogStateHolder(object : SeriesCatalogSource {
        override suspend fun load(previous: SeriesCatalogState.Ready?) = SeriesCatalogState.Ready("check", 0,
            listOf(series), emptyList(), selectedSeriesId = 7, watchProgress = records.associateBy { it.streamId })
    }, scope, loadEpisodes = { SeriesEpisodeLoadResult.Success(episodes, SeriesShowMetadata("A concise synopsis.", "Drama", "2020")) })
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AtlasTvTheme {
            if (opened != null) {
                androidx.activity.compose.BackHandler { opened = null }
                Text("Selected episode", Modifier.testTag("episode_rows_destination"))
            } else SeriesScreen(holder, onBack = { finish() }, selectedSeriesForDetails = series,
                openSelectedSeriesDetailsRequest = request,
                onEpisodeSelected = { _, episode -> openedEpisodeId = episode.episodeId; request++; opened = episode.episodeId })
        } }
    }
    fun revealThumbnail() { scope.launch {
        withContext(Dispatchers.IO) {
            val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.rgb(34, 88, 116))
            val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            bitmap.recycle(); cache.write(thumbnail.providerImageToken, bytes)
        }
        holder.rememberPlaybackEpisodes(7, SeriesEpisodeState.Ready(episodes.map { if (it.episodeId == 11) it.copy(thumbnail = thumbnail) else it },
            SeriesShowMetadata("A concise synopsis.", "Drama", "2020")))
    } }
    fun replaceRuntimes(provider: Long?, checkpoint: Long?) {
        records = records.map { if (it.streamId == 11) it.copy(durationMs = checkpoint) else it }
        holder.rememberPlaybackEpisodes(7, SeriesEpisodeState.Ready(episodes.map {
            if (it.episodeId == 11) it.copy(durationMs = provider) else it
        }, SeriesShowMetadata("A concise synopsis.", "Drama", "2020")))
        holder.refreshWatchProgress()
    }
    fun updateProgress() { records = records.map { if (it.streamId == 11) it.copy(positionMs = 1_260_000) else it }; holder.refreshWatchProgress() }
    override fun onDestroy() {
        scope.cancel(); cache.remove(thumbnail.providerImageToken); super.onDestroy()
    }
}

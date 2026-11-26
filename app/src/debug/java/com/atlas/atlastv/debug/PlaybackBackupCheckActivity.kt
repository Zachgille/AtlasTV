package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.debug.DebugLivePlaybackScreen
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.theme.LumaireTheme
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Local generated video, loopback endpoints and cache-only progress. No personal provider/database use. */
class PlaybackBackupCheckActivity : ComponentActivity() {
    lateinit var store: WatchProgressStore; private set
    lateinit var writer: WatchProgressCoordinator; private set
    lateinit var account: XtreamCredentials; private set
    lateinit var initial: WatchProgressRecord; private set
    lateinit var other: WatchProgressRecord; private set
    val backupRequests = AtomicInteger()
    private var server: ServerSocket? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WatchProgressStore(File(cacheDir, "backup-playback-${UUID.randomUUID()}.json"))
        writer = WatchProgressCoordinator.forStore(store)
        setContent { LumaireTheme {
            var ready by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val episode = intent.getBooleanExtra("episode", false)
                    initial = WatchProgressRecord(if (episode) WatchProgressContentType.SeriesEpisode else WatchProgressContentType.Movie,
                        17, seriesId = if (episode) 81 else null, title = "Local playback backup check", extension = "mp4",
                        durationMs = 600_000, positionMs = 255_000, updatedAtMs = 1)
                    other = initial.copy(contentType = if (episode) WatchProgressContentType.Movie else WatchProgressContentType.SeriesEpisode,
                        seriesId = if (episode) null else 82, positionMs = 88_000)
                    store.saveRecordsAsync(listOf(initial, other))
                    val primary = ServerSocket(0); val backup = ServerSocket(0)
                    account = XtreamCredentials("http://127.0.0.1:${primary.localPort}", "local fixture", "opaque/密碼 +?",
                        listOf("http://127.0.0.1:${backup.localPort}"))
                    primary.close()
                    if (intent.getBooleanExtra("unreachable", false)) backup.close()
                    else {
                        server = backup
                        val bytes = assets.open("progress-check.mp4").use { it.readBytes() }
                        Thread {
                            while (!backup.isClosed) {
                                val connection = try { backup.accept() } catch (_: Exception) { break }
                                runCatching { connection.use {
                                    connection.soTimeout = 3_000
                                    val input = connection.getInputStream().bufferedReader()
                                    input.readLine()
                                    var range: String? = null
                                    while (true) {
                                        val header = input.readLine().orEmpty(); if (header.isEmpty()) break
                                        if (header.startsWith("Range:", true)) range = header.substringAfter(':').trim()
                                    }
                                    backupRequests.incrementAndGet()
                                    val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                                    val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
                                    val count = end - start + 1
                                    if (start !in bytes.indices || count <= 0) return@use
                                    val status = if (range != null) "206 Partial Content" else "200 OK"
                                    val contentRange = if (range != null) "Content-Range: bytes $start-$end/${bytes.size}\r\n" else ""
                                    connection.getOutputStream().apply {
                                        write("HTTP/1.1 $status\r\nContent-Type: video/mp4\r\nAccept-Ranges: bytes\r\n${contentRange}Content-Length: $count\r\nConnection: close\r\n\r\n".toByteArray())
                                        write(bytes, start, count); flush()
                                    }
                                } }
                            }
                        }.apply { isDaemon = true; start() }
                    }
                }
                ready = true
            }
            if (ready) {
                val physical = account.copy(alternateBaseUrls = emptyList())
                val url = if (initial.contentType == WatchProgressContentType.Movie) XtreamEndpointBuilder().buildVodStream(physical, 17)
                    else XtreamEndpointBuilder().buildSeriesEpisodeStream(physical, 17)
                DebugLivePlaybackScreen(LiveTvChannel("Local backup check", "", 17, "mp4", "Local test"), url,
                    onBack = { finish() }, initialPositionMs = initial.positionMs, progressRecord = initial,
                    progressStore = store, progressCoordinator = writer, providerCredentials = { account })
            }
        } }
    }
    override fun onDestroy() { server?.close(); super.onDestroy() }
}

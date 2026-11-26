package com.atlas.atlastv.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.atlas.atlastv.core.config.DebugConfig
import com.atlas.atlastv.core.security.UrlRedactor
import com.atlas.atlastv.playlist.debug.PlaylistDebugLoadState
import com.atlas.atlastv.playlist.debug.PlaylistDebugLoader
import com.atlas.atlastv.playlist.debug.XtreamDebugLoadState
import com.atlas.atlastv.playlist.debug.XtreamDebugLoader
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DebugPlaylistLoaderStateHolder(
    private val scope: CoroutineScope,
    private val loader: PlaylistDebugLoader = PlaylistDebugLoader(),
    private val xtreamLoader: XtreamDebugLoader = XtreamDebugLoader(),
    private val localDebugPlaylistUrl: String = DebugConfig.debugPlaylistUrl,
    private val localXtreamCredentials: XtreamCredentials? = DebugConfig.debugXtreamCredentials,
) {
    var urlInput by mutableStateOf("")
        private set

    var loadState: PlaylistDebugLoadState by mutableStateOf(PlaylistDebugLoadState.Idle)
        private set

    var xtreamLoadState: XtreamDebugLoadState by mutableStateOf(
        if (localXtreamCredentials == null) XtreamDebugLoadState.Unavailable else XtreamDebugLoadState.Ready,
    )
        private set

    private var loadJob: Job? = null

    val hasLocalDebugPlaylistUrl: Boolean
        get() = localDebugPlaylistUrl.isNotBlank()

    val hasLocalXtreamConfig: Boolean
        get() = localXtreamCredentials != null

    val xtreamConfigStatus = DebugConfig.debugXtreamConfigStatus

    fun onUrlChanged(value: String) {
        urlInput = value
    }

    fun loadPlaylist() {
        loadUrl(urlInput)
    }

    fun loadLocalDebugPlaylist() {
        if (localDebugPlaylistUrl.isBlank()) return
        loadUrl(localDebugPlaylistUrl)
    }

    fun loadLocalXtreamCatalog() {
        val credentials = localXtreamCredentials ?: run {
            xtreamLoadState = XtreamDebugLoadState.Unavailable
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            xtreamLoader.load(credentials) { state ->
                xtreamLoadState = state
            }
        }
    }

    private fun loadUrl(url: String) {
        val submittedUrl = url
        loadJob?.cancel()
        loadState = PlaylistDebugLoadState.Loading(redactedUrl = UrlRedactor.redact(submittedUrl))
        loadJob = scope.launch {
            loadState = loader.load(submittedUrl)
        }
    }
}

package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.settings.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.data.catalog.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException

/** Actual Settings with unique cache storage and fake provider/preferences; no personal data/network. */
class SettingsNavigationCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var directory: File
    lateinit var holder: ProviderSettingsStateHolder
    lateinit var preferences: PlaybackPreferencesStateHolder
    val navigation = SettingsNavigationState()
    var screen by mutableStateOf("settings")
    var calls = 0; private set
    var preferenceWrites = 0; private set
    var writes = 0; private set
    var diagnostics = 0; private set
    var failSave = false
    var blockRefresh = false
    val refreshGate = CompletableDeferred<Unit>()
    private var autoplay = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        directory = File(cacheDir, "settings-navigation-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "settings.json")
        val store = ProviderSettingsStore(file) { temporary, target ->
            if (target == file) { writes++; if (failSave) throw IOException("opaque private diagnostic") }
            atomicProviderReplace(temporary, target)
        }
        if (!intent.getBooleanExtra("no_provider", false)) {
            check(store.saveSettings(ProviderSettingsSnapshot("http://example.invalid", "opaque-user", "opaque-password")))
            check(store.saveSyncStatus(ProviderSyncStatusSnapshot(status = CatalogSyncStatus.Success, moviesImported = 12, seriesImported = 9, lastSuccessfulSyncAtMs = System.currentTimeMillis())))
        }
        writes = 0
        holder = ProviderSettingsStateHolder(store, scope, { ProviderConnectionResult(true) }, { _, stage ->
            calls++; stage(com.atlas.atlastv.xtream.network.XtreamLoadStage.VodStreams)
            if (blockRefresh) refreshGate.await()
            CatalogImportResult(CatalogSyncStatus.Success,100,200,15,10,0,0)
        })
        preferences = PlaybackPreferencesStateHolder(object : PlaybackPreferencesStore {
            override suspend fun loadAutoplay() = autoplay
            override suspend fun saveAutoplay(enabled: Boolean) { preferenceWrites++; autoplay = enabled }
        }, scope)
        setContent { LumaireTheme {
            when (screen) {
                "diagnostics" -> { BackHandler { screen = "settings" }; Text("Diagnostics", Modifier.testTag("navigation_check_diagnostics")) }
                "home" -> Text("Home", Modifier.testTag("navigation_check_home"))
                else -> SettingsScreen(holder, { screen = "home" }, { diagnostics++; screen = "diagnostics" },
                    playbackPreferences = preferences, navigationState = navigation)
            }
        } }
    }
    override fun onDestroy() { refreshGate.complete(Unit); scope.cancel(); if (::directory.isInitialized) directory.deleteRecursively(); super.onDestroy() }
}

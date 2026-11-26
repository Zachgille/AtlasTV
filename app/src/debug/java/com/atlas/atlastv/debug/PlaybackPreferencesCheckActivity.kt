package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.atlas.atlastv.data.catalog.CatalogImportResult
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.settings.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Isolated Settings/DataStore UI. Unique cache preference files and a fake provider; no personal state. */
class PlaybackPreferencesCheckActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var preferences: PlaybackPreferencesStateHolder
    lateinit var store: DataStorePlaybackPreferencesStore
    lateinit var preferenceFile: File
    var failSave = false
    var failLoad = false
    var writes = 0
    private var providerFile: File? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("check_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: UUID.randomUUID().toString()
        preferenceFile = File(cacheDir, "autoplay-check-$id.preferences_pb")
        store = DataStorePlaybackPreferencesStore(PreferenceDataStoreFactory.create(scope = storageScope,
            corruptionHandler = playbackPreferencesCorruptionHandler(), produceFile = { preferenceFile }))
        failSave = intent.getBooleanExtra("fail_save", false)
        failLoad = intent.getBooleanExtra("fail_load", false)
        preferences = PlaybackPreferencesStateHolder(object : PlaybackPreferencesStore {
            override suspend fun loadAutoplay(): Boolean { if (failLoad) error("Private storage diagnostic"); return store.loadAutoplay() }
            override suspend fun saveAutoplay(enabled: Boolean) { writes++; if (failSave) error("Private storage diagnostic"); store.saveAutoplay(enabled) }
        }, uiScope)
        val settingsFile = File.createTempFile("autoplay-provider-check-", ".json", cacheDir).also { providerFile = it }
        val settings = ProviderSettingsStateHolder(ProviderSettingsStore(settingsFile), uiScope,
            { ProviderConnectionResult(true) }, { _, _ -> CatalogImportResult(CatalogSyncStatus.Success, 0, 0, 0, 0, 0, 0) })
        setContent { LumaireTheme { SettingsScreen(settings, onBack = { finish() }, onAdvancedDiagnostics = {}, playbackPreferences = preferences) } }
    }
    override fun onDestroy() { uiScope.cancel(); storageScope.cancel(); providerFile?.delete(); super.onDestroy() }
}

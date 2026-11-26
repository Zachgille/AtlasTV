package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.settings.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import com.atlas.atlastv.data.catalog.*
import kotlinx.coroutines.*
import java.io.File

/** Isolated UI fixtures. The settings file is temporary, not the saved account. */
class LibraryUsabilityCheckActivity : ComponentActivity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val cards = mutableListOf(
        HomeCard("sample-movie","Sample Movie",HomeContentType.Movie,"Movies",1,progressLabel="Resume at 1:00",statusText=""),
        HomeCard("sample-show","Sample Show",HomeContentType.SeriesEpisode,"Shows",2,seriesId=3,progressLabel="Resume at 2:00",statusText=""))
    var removed = mutableListOf<String>(); private set
    var playbackRequests=0; private set
    var refreshRequests=0; private set
    var refreshCallbacks=0; private set
    var settingsOpen by mutableStateOf(false)
    private var temp: File?=null
    lateinit var settings:ProviderSettingsStateHolder
    val syncGate=CompletableDeferred<Unit>()
    val home=HomeShellStateHolder({ HomeLoadResult(HomeShellState.Ready("check",0,
        listOf(HomeContentRow(HomeRowIds.ContinueWatching,"Continue Watching",cards.toList())),cards.firstOrNull()?.id)) })
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val file=File.createTempFile("library-settings-check-",".json",cacheDir).also { temp=it }
        val store=ProviderSettingsStore(file)
        store.saveSettings(ProviderSettingsSnapshot("http://example.test","test","test"))
        settings=ProviderSettingsStateHolder(store,scope,{ ProviderConnectionResult(true) }, { _, _ ->
            refreshRequests++; syncGate.await(); CatalogImportResult(CatalogSyncStatus.Success,1,2,100,50,0,0)
        })
        setContent { LumaireTheme {
            if (settingsOpen) SettingsScreen(settings,{settingsOpen=false},{},onRefreshCatalog={settings.syncMoviesAndShowsAsync { refreshCallbacks++ }})
            else AtlasHomeScreen(home,onLiveTvSelected={},onMoviesSelected={},onSeriesSelected={},
                onSettingsSelected={settingsOpen=true},onHomeCardSelected={playbackRequests++},
                onRemoveContinueWatching={card -> removed+=card.id; cards.removeAll { it.id==card.id }; home.refresh() })
        } }
    }
    override fun onDestroy() { scope.cancel(); temp?.delete(); super.onDestroy() }
}

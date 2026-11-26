package com.atlas.atlastv.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.data.catalog.XtreamCatalogImporter
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.ui.home.HomeShellState
import com.atlas.atlastv.ui.home.HomeShellStateHolder
import com.atlas.atlastv.ui.movies.MoviesCatalogState
import com.atlas.atlastv.ui.movies.MoviesCatalogStateHolder
import com.atlas.atlastv.ui.search.UnifiedSearchState
import com.atlas.atlastv.ui.search.buildUnifiedSearchState
import com.atlas.atlastv.ui.series.SeriesCatalogState
import com.atlas.atlastv.ui.series.SeriesCatalogStateHolder
import com.atlas.atlastv.ui.theme.LumaireTheme
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamVodStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProviderSettingsSyncConnectedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: SafeCatalogRepository
    private lateinit var settingsFile: File

    @Before
    fun setUp() {
        repository = SafeCatalogRepository(AtlasDatabaseProvider.database(targetContext).safeCatalogDao()) { 20_000L }
        settingsFile = File(targetContext.cacheDir, "provider-settings-${System.nanoTime()}.json")
        runBlocking { repository.clearCatalog() }
    }

    @After
    fun tearDown() {
        runBlocking { repository.clearCatalog() }
        settingsFile.delete()
    }

    @Test
    fun settingsSaveTestAndSyncImportRoomRowsWithoutDebugJson() {
        val holder = holder()
        composeRule.setContent {
            LumaireTheme {
                SettingsScreen(
                    stateHolder = holder,
                    onBack = {},
                    onAdvancedDiagnostics = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_section_provider").performClick()
        composeRule.onNodeWithTag("provider_base_url_field").fetchSemanticsNode()
        composeRule.onNodeWithTag("provider_username_field").fetchSemanticsNode()
        composeRule.onNodeWithTag("provider_password_field").fetchSemanticsNode()
        composeRule.onNodeWithTag("provider_actions_panel").fetchSemanticsNode()
        composeRule.onNodeWithTag("provider_save_action").fetchSemanticsNode()
        composeRule.onNodeWithTag("provider_test_connection_action").fetchSemanticsNode()

        composeRule.runOnIdle {
            holder.updateBaseUrl("http://fixture.test")
            holder.updateUsername("fixture_user")
            holder.updatePassword("fixture_pass")
            holder.saveProviderSettings()
        }
        composeRule.waitUntil(5_000) { !holder.state.providerSaving }
        runBlocking {
            holder.testConnectionNow()
            holder.syncMoviesAndShowsNow()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("provider_connection_status").fetchSemanticsNode()
        composeRule.onNodeWithTag("provider_sync_status_label").fetchSemanticsNode()
        composeRule.onNodeWithTag("settings_section_library").performClick()
        composeRule.onNodeWithTag("provider_sync_counts").fetchSemanticsNode()
        assertEquals("success", holder.state.connectionStatus)
        assertEquals(CatalogSyncStatus.Success, holder.state.syncStatus.status)
        assertEquals(2, holder.state.syncStatus.moviesImported)
        assertEquals(2, holder.state.syncStatus.seriesImported)

        val homeHolder = HomeShellStateHolder(targetContext)
        val moviesHolder = MoviesCatalogStateHolder(targetContext)
        val seriesHolder = SeriesCatalogStateHolder(targetContext, credentialsProvider = { null })
        runBlocking { homeHolder.loadHome() }
        kotlinx.coroutines.runBlocking { moviesHolder.loadCachedCatalog() }
        kotlinx.coroutines.runBlocking { seriesHolder.loadCachedCatalog() }
        val home = homeHolder.state as HomeShellState.Ready
        val movies = moviesHolder.state as MoviesCatalogState.Ready
        val series = seriesHolder.state as SeriesCatalogState.Ready
        val search = buildUnifiedSearchState(movies, series, "fixture")

        assertEquals("room", home.source)
        assertEquals("room", movies.source)
        assertEquals("room", series.source)
        assertEquals(2, movies.movies.size)
        assertEquals(2, series.series.size)
        assertTrue(search is UnifiedSearchState.Ready)
        assertFalse(ProviderSettingsStore(settingsFile).load().syncStatus.errorCategory.orEmpty().contains("fixture_pass"))
    }

    private fun holder(): ProviderSettingsStateHolder =
        ProviderSettingsStateHolder(
            store = ProviderSettingsStore(settingsFile),
            scope = CoroutineScope(Dispatchers.Main),
            testConnection = { ProviderConnectionResult(success = true) },
            syncCatalog = { _, onStage ->
                onStage(com.atlas.atlastv.xtream.network.XtreamLoadStage.VodCategories)
                XtreamCatalogImporter(repository, clockMs = { 20_000L }).importMoviesAndSeries(
                    vodCategories = listOf(XtreamCategory("movies-us", "US Movies", null)),
                    vodStreams = listOf(
                        XtreamVodStream(1, "Fixture Movie One", "movie", 61001, null, null, null, "1", "movies-us", "mp4"),
                        XtreamVodStream(2, "Fixture Movie Two", "movie", 61002, null, null, null, "2", "movies-us", "mp4"),
                    ),
                    seriesCategories = listOf(XtreamCategory("series-us", "US Series", null)),
                    series = listOf(
                        XtreamSeries(1, "Fixture Series One", 62001, null, null, null, null, null, null, "1", null, null, "series-us"),
                        XtreamSeries(2, "Fixture Series Two", 62002, null, null, null, null, null, null, "2", null, null, "series-us"),
                    ),
                )
            },
        )
}


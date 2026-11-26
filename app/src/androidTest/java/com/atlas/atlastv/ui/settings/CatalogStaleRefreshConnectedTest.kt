package com.atlas.atlastv.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CatalogStaleRefreshConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: SafeCatalogRepository
    private lateinit var providerStore: ProviderSettingsStore
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        repository = SafeCatalogRepository(AtlasDatabaseProvider.database(targetContext).safeCatalogDao())
        providerStore = ProviderSettingsStore(File(targetContext.filesDir, ProviderSettingsStore.FileName))
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).delete()
        providerStore.clear()
        runBlocking { repository.clearCatalog() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        providerStore.clear()
        runBlocking { repository.clearCatalog() }
    }

    @Test
    fun freshCatalogDoesNotShowPromptAndHomeRowsRemainUsable() {
        seedCatalog()
        saveProviderSettings()
        providerStore.saveSyncStatus(successStatus(lastSuccess = System.currentTimeMillis() - 1_000L))

        launchHome()

        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("popular_row").fetchSemanticsNode()
        composeRule.onAllNodesWithTag("catalog_refresh_prompt").assertCountEquals(0)
    }

    @Test
    fun staleCatalogShowsPromptAndCachedRowsRemainUsable() {
        seedCatalog()
        saveProviderSettings()
        providerStore.saveSyncStatus(
            successStatus(lastSuccess = System.currentTimeMillis() - CatalogFreshnessDefaults.StaleThresholdMs - 1_000L),
        )

        launchHome()

        composeRule.onNodeWithTag("catalog_refresh_prompt").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Movies: 1", substring = true).fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Refresh Movies/Shows", substring = true).fetchSemanticsNodes()
        composeRule.onNodeWithTag("popular_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.onNodeWithTag("movies_screen").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_shows").performClick()
        composeRule.onNodeWithTag("series_screen").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_search").performClick()
        composeRule.onNodeWithTag("unified_search_screen").fetchSemanticsNode()
    }

    @Test
    fun neverSyncedWithoutCatalogGuidesToSettings() {
        launchHome()

        composeRule.onAllNodesWithText("Open Settings", substring = true).fetchSemanticsNodes()
        composeRule.onNodeWithTag("catalog_refresh_prompt").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Open Settings", substring = true).fetchSemanticsNodes()
        composeRule.onNodeWithTag("catalog_refresh_prompt").performClick()
        composeRule.onNodeWithTag("settings_screen").fetchSemanticsNode()
    }

    @Test
    fun refreshPromptStartsSyncPersistsSafeFailureAndKeepsCachedHomeUsable() {
        seedCatalog()
        saveProviderSettings(baseUrl = "http://127.0.0.1:1")
        providerStore.saveSyncStatus(
            successStatus(lastSuccess = System.currentTimeMillis() - CatalogFreshnessDefaults.StaleThresholdMs - 1_000L),
        )

        launchHome()

        composeRule.onNodeWithTag("catalog_refresh_prompt").performClick()
        composeRule.onNodeWithTag("popular_row").fetchSemanticsNode()
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            providerStore.load().syncStatus.status == CatalogSyncStatus.Failure
        }

        val status = providerStore.load().syncStatus
        composeRule.onAllNodesWithText("Catalog refresh failed", substring = true).fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Movies: 1", substring = true).fetchSemanticsNodes()
        assertTrue(status.lastFailedSyncAtMs != null)
        assertTrue(status.lastSuccessfulSyncAtMs != null)
        assertTrue(status.moviesImported == 1)
        assertTrue(status.seriesImported == 1)
        assertFalse(status.errorCategory.orEmpty().contains("fixture_user"))
        assertFalse(status.errorCategory.orEmpty().contains("fixture_pass"))
        assertFalse(status.errorCategory.orEmpty().contains("127.0.0.1"))
        assertFalse(status.errorCategory.orEmpty().contains("/movie/"))
        assertFalse(status.errorCategory.orEmpty().contains("/series/"))
    }

    private fun launchHome() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
    }

    private fun seedCatalog() {
        runBlocking {
            repository.replaceMovies(listOf(MovieItem("Fixture Movie", "Fixture", 71001, "mp4", "room://safe_catalog/vod/71001.mp4")))
            repository.replaceSeries(listOf(SeriesItem("Fixture Series", "Fixture", 72001)))
        }
    }

    private fun saveProviderSettings(baseUrl: String = "http://fixture.test") {
        providerStore.saveSettings(
            ProviderSettingsSnapshot(
                baseUrl = baseUrl,
                username = "fixture_user",
                password = "fixture_pass",
            ),
        )
    }

    private fun successStatus(lastSuccess: Long): ProviderSyncStatusSnapshot =
        ProviderSyncStatusSnapshot(
            status = CatalogSyncStatus.Success,
            lastStartedAtMs = lastSuccess - 100L,
            lastCompletedAtMs = lastSuccess,
            lastSuccessfulSyncAtMs = lastSuccess,
            moviesImported = 1,
            seriesImported = 1,
        )
}

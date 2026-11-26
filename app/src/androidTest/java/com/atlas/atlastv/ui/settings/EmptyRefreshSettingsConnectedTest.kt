package com.atlas.atlastv.ui.settings

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.catalog.*
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import com.atlas.atlastv.xtream.model.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The real Settings screen with a private temporary account and in-memory catalog. */
class EmptyRefreshSettingsConnectedTest {
    @get:Rule val ui = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AtlasDatabase::class.java).build()
    private val repository = SafeCatalogRepository(db.safeCatalogDao())
    private val file = File(instrumentation.targetContext.cacheDir, "empty-refresh-${System.nanoTime()}.json")
    private val store = ProviderSettingsStore(file)
    private val requests = AtomicInteger()
    private val gate = CompletableDeferred<Unit>()
    @Volatile private var usable = false
    @Volatile private var blocked = false
    private var settingsOpen by mutableStateOf(true)
    private lateinit var holder: ProviderSettingsStateHolder

    private fun movie(id: Int) = XtreamVodStream(1, "Sample Movie", "movie", id, null, null, null, null, null, "mp4")
    private fun show(id: Int) = XtreamSeries(1, "Sample Show", id, null, null, null, null, null, null, null, null, null, null)
    private fun start() {
        runBlocking { XtreamCatalogImporter(repository).importMoviesAndSeries(emptyList(), listOf(movie(1)), emptyList(), listOf(show(2))) }
        store.saveSettings(ProviderSettingsSnapshot("http://example.invalid", "fixture", "fixture"))
        store.saveSyncStatus(ProviderSyncStatusSnapshot(status = CatalogSyncStatus.Success,
            lastSuccessfulSyncAtMs = 1_000, moviesImported = 1, seriesImported = 1))
        holder = ProviderSettingsStateHolder(store, scope, { ProviderConnectionResult(true) }, { _, _ ->
            requests.incrementAndGet()
            if (blocked) gate.await()
            XtreamCatalogImporter(repository).importMoviesAndSeries(emptyList(), if (usable) listOf(movie(11)) else emptyList(),
                emptyList(), if (usable) listOf(show(22)) else emptyList())
        })
        ui.setContent { AtlasTvTheme {
            BackHandler { settingsOpen = false }
            if (settingsOpen) SettingsScreen(holder, { settingsOpen = false }, {})
            else androidx.tv.material3.Text("Library remains available")
        } }
        ui.onNodeWithTag("provider_sync_action").performSemanticsAction(SemanticsActions.RequestFocus)
        ui.onNodeWithTag("provider_sync_action").assertIsFocused()
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); ui.waitForIdle() }
    private fun waitStatus(status: CatalogSyncStatus) { ui.waitUntil(10_000) { holder.state.syncStatus.status == status } }
    private fun assertRows(movieId: Int, showId: Int) = runBlocking {
        assertEquals(listOf(movieId), db.safeCatalogDao().itemsForType("movie").map { it.itemId })
        assertEquals(listOf(showId), db.safeCatalogDao().itemsForType("series").map { it.itemId })
    }

    @Test fun nativeRefreshPreservesSavedLibraryAndNativeRetryReplacesIt() {
        start()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitStatus(CatalogSyncStatus.Failure)
        assertRows(1, 2)
        val saved = store.load().syncStatus
        assertEquals(1_000L, saved.lastSuccessfulSyncAtMs)
        assertEquals(1, saved.moviesImported); assertEquals(1, saved.seriesImported)
        ui.onNodeWithTag("provider_sync_status_label").assertTextEquals(saved.refreshFailureMessage())
        ui.onNodeWithTag("provider_sync_counts").assertTextEquals("1 movies · 1 shows")
        ui.onNodeWithText(CatalogRefreshWriteResult.RefusedEmptyCatalog.name, substring = true).assertDoesNotExist()
        ui.onNodeWithTag("provider_sync_action").assertIsFocused()
        val bounds = ui.onNodeWithTag("provider_sync_status_label").fetchSemanticsNode().boundsInRoot
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom)
        ui.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "empty-refresh-refined.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        usable = true
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitStatus(CatalogSyncStatus.Success)
        assertRows(11, 22)
        assertEquals(2, requests.get())
        ui.onNodeWithTag("provider_sync_status_label").assertTextEquals("Library ready")
        ui.onNodeWithTag("provider_sync_action").assertIsFocused()
    }

    @Test fun repeatedRemoteRefreshIsSuppressedAndBackWorksWhilePending() {
        blocked = true
        start()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        waitStatus(CatalogSyncStatus.Loading)
        repeat(3) { key(KeyEvent.KEYCODE_DPAD_CENTER) }
        assertEquals(1, requests.get()); assertRows(1, 2)
        key(KeyEvent.KEYCODE_BACK)
        ui.onNodeWithText("Library remains available").assertExists()
        gate.complete(Unit)
        waitStatus(CatalogSyncStatus.Failure)
        assertRows(1, 2)
        assertEquals(CatalogSyncStatus.Failure, store.load().syncStatus.status)
        assertEquals(1_000L, store.load().syncStatus.lastSuccessfulSyncAtMs)
    }

    @After fun close() { scope.cancel(); db.close(); file.delete() }
}

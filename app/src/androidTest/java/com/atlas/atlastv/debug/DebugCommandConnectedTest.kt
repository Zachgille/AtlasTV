package com.atlas.atlastv.debug

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.livetv.LiveTvPreferencesStore
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DebugCommandConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
    }

    @Test
    fun fastDebugCommandsWriteSafePrivateJson() {
        val health = runCommand("health_check")
        assertEquals("success", health.status)
        assertSafeJson(DebugCommandFiles.CommandResult)

        val fixture = runCommand("load_fixture_catalog")
        assertEquals("success", fixture.status)
        assertEquals("fixture", fixture.catalogSource)
        assertEquals(3, fixture.itemCounts.liveStreams)
        assertSafeJson(DebugCommandFiles.CommandResult)
        assertSafeJson(DebugCommandFiles.CatalogSummary)

        val state = runCommand("dump_current_state")
        assertEquals("success", state.status)
        assertEquals("fixture", state.catalogSource)
        assertSafeJson(DebugCommandFiles.CommandResult)
        assertSafeJson(DebugCommandFiles.State)

        val liveTvState = runCommand("dump_live_tv_state")
        assertEquals("success", liveTvState.status)
        assertEquals("fixture", liveTvState.liveTvState!!.cacheSource)
        assertEquals(3, liveTvState.liveTvState.cappedItemCount)
        assertEquals(1001, liveTvState.liveTvState.selectedChannelStreamId)
        assertEquals("Provider order", liveTvState.liveTvState.selectedSortModeLabel)
        assertSafeJson(DebugCommandFiles.CommandResult)
        assertSafeJson(DebugCommandFiles.LiveTvState)

        val moviesState = runCommand("dump_movies_state")
        assertEquals("success", moviesState.status)
        assertEquals("fixture", moviesState.moviesState!!.cacheSource)
        assertEquals(3, moviesState.moviesState.cappedItemCount)
        assertEquals(2001, moviesState.moviesState.selectedMovieStreamId)
        assertEquals("Provider order", moviesState.moviesState.selectedSortModeLabel)
        assertSafeJson(DebugCommandFiles.CommandResult)
        assertSafeJson(DebugCommandFiles.MoviesState)

        val seededProgress = runCommand("seed_fixture_watch_progress")
        assertEquals("success", seededProgress.status)
        assertEquals(2, seededProgress.watchProgressState!!.continueWatchingCount)
        assertSafeJson(DebugCommandFiles.CommandResult)
        assertSafeJson(DebugCommandFiles.WatchProgressState)

        val progressState = runCommand("dump_watch_progress_state")
        assertEquals("success", progressState.status)
        assertEquals(2, progressState.watchProgressState!!.totalCount)
        assertSafeJson(DebugCommandFiles.CommandResult)
        assertSafeJson(DebugCommandFiles.WatchProgressState)

        val clearedProgress = runCommand("clear_watch_progress")
        assertEquals("success", clearedProgress.status)
        assertEquals(0, clearedProgress.watchProgressState!!.totalCount)
        assertSafeJson(DebugCommandFiles.CommandResult)
        assertSafeJson(DebugCommandFiles.WatchProgressState)

        val cleared = runCommand("clear_debug_cache")
        assertEquals("success", cleared.status)
        assertEquals("success", runCommand("clear_safe_catalog_cache_snapshot").status)
        val missingImport = runCommand("import_safe_catalog_cache_snapshot")
        assertEquals("failure", missingImport.status)
        assertEquals("SnapshotMissing", missingImport.lastErrorCategory)

        val fixtureForSnapshot = runCommand("load_fixture_catalog")
        assertEquals("success", fixtureForSnapshot.status)
        val exportedSnapshot = runCommand("export_safe_catalog_cache_snapshot")
        assertEquals("success", exportedSnapshot.status)
        assertEquals(true, exportedSnapshot.catalogCacheSnapshotState!!.exists)
        assertSafeJson(DebugCommandFiles.SafeCatalogCacheSnapshot)

        val clearedCatalog = runCommand("clear_debug_cache")
        assertEquals("success", clearedCatalog.status)
        val importedSnapshot = runCommand("import_safe_catalog_cache_snapshot")
        assertEquals("success", importedSnapshot.status)
        assertEquals(3, importedSnapshot.itemCounts.keptSeries)
        assertSafeJson(DebugCommandFiles.CatalogSummary)

        val snapshotState = runCommand("dump_catalog_cache_snapshot_state")
        assertEquals("success", snapshotState.status)
        assertEquals(true, snapshotState.catalogCacheSnapshotState!!.exists)

        File(targetContext.filesDir, DebugCommandFiles.SafeCatalogCacheSnapshot).writeText(
            """{"schemaVersion":1,"exportedAtEpochMs":1,"catalog":{"catalogSource":"real","generatedAtEpochMs":1,"itemCounts":{},"redactedUrl":"http://example.test","playableLiveItems":[{"name":"Raw","categoryName":"Raw","streamId":1,"containerExtension":"ts","redactedUrl":"http://example.test/live/fake_user/fake_pass/1.ts"}]}}""",
            Charsets.UTF_8,
        )
        val invalidImport = runCommand("import_safe_catalog_cache_snapshot")
        assertEquals("failure", invalidImport.status)
        assertEquals("UnsafeStreamUrl", invalidImport.lastErrorCategory)

        assertSafeJson(DebugCommandFiles.CommandResult)
    }

    private fun runCommand(command: String, durationSeconds: Int? = null): DebugCommandResult {
        resultFile().delete()
        val intent = Intent(DebugCommandReceiver.ACTION_DEBUG_COMMAND)
            .setComponent(ComponentName(targetContext.packageName, DebugCommandReceiver::class.java.name))
            .putExtra(DebugCommandReceiver.EXTRA_COMMAND, command)
        durationSeconds?.let { intent.putExtra(DebugCommandReceiver.EXTRA_DURATION_SECONDS, it.toString()) }
        targetContext.sendBroadcast(intent)
        val json = waitForResult(command)
        return DebugCommandJson.gson.fromJson(json, DebugCommandResult::class.java)
    }

    private fun waitForResult(command: String): String {
        val deadline = System.currentTimeMillis() + 30_000L
        do {
            val file = resultFile()
            if (file.exists()) {
                val json = file.readText(Charsets.UTF_8)
                if (json.contains(""""command": "$command"""")) {
                    return json
                }
            }
            Thread.sleep(100L)
        } while (System.currentTimeMillis() < deadline)
        error("Timed out waiting for $command result.")
    }

    private fun resultFile(): File =
        File(targetContext.filesDir, DebugCommandFiles.CommandResult)

    private fun assertSafeJson(fileName: String) {
        val file = File(targetContext.filesDir, fileName)
        assertTrue("$fileName should exist", file.exists())
        val json = file.readText(Charsets.UTF_8)
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains(Regex("""https?://[^"']+/live/[^"']+/[^"']+/[^"']+""")))
        assertFalse(json.contains(Regex("""https?://[^"']+/movie/[^"']+/[^"']+/[^"']+""")))
        assertFalse(json.contains(Regex("""https?://[^"']+/series/[^"']+/[^"']+/[^"']+""")))
    }

    private fun String.contains(regex: Regex): Boolean =
        regex.containsMatchIn(this)
}

package com.atlas.atlastv.xtream

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.catalog.*
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.settings.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Loopback transport, in-memory catalog and unique private settings fixtures only. */
class ProviderEndpointRecoveryConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun old(type: String, id: Int) = SafeCatalogItemEntity(type, id, "Old $type", categoryName = "English",
        providerOrder = 0, containerExtension = "mp4", posterState = "Missing", posterSafeUri = null,
        posterProviderToken = null, posterRedactedSource = null, updatedAtMs = 1)

    @Test fun realHttpRecoversImportsOneCompleteBackupAndReopensOnTheSavedRoute() = runBlocking(Dispatchers.IO) {
        val primarySocket = ServerSocket(0)
        val primary = "http://127.0.0.1:${primarySocket.localPort}"
        val server = ServerSocket(0); val backup = "http://127.0.0.1:${server.localPort}"
        primarySocket.close()
        val count = AtomicInteger()
        val thread = Thread {
            try { while (!server.isClosed) server.accept().use { connection ->
                val input = connection.getInputStream().bufferedReader()
                val request = input.readLine().orEmpty()
                while (!input.readLine().isNullOrEmpty()) { }
                count.incrementAndGet()
                val response = when {
                    request.contains("get_vod_categories") -> """[{"category_id":"1","category_name":"English Movies"}]"""
                    request.contains("get_vod_streams") -> """[{"stream_id":101,"name":"Backup Movie","category_id":"1","container_extension":"mp4"}]"""
                    request.contains("get_series_categories") -> """[{"category_id":"2","category_name":"English Shows"}]"""
                    request.contains("get_series") -> """[{"series_id":202,"name":"Backup Show","category_id":"2"}]"""
                    else -> """{"user_info":{"auth":1,"status":"Active"}}"""
                }.toByteArray()
                connection.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(response); flush()
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val file = File(context.cacheDir, "backup-route-${UUID.randomUUID()}.json")
        val store = ProviderSettingsStore(file)
        val settings = ProviderSettingsSnapshot(primary, "opaque user", "opaque/密碼 +?", alternateBaseUrls = listOf(backup))
        store.saveSettings(settings)
        val routes = ProviderEndpointRecovery().apply { persistUsing { credentials, base -> store.rememberEndpoint(credentials, base) } }
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            db.safeCatalogDao().insertAll(listOf(old("movie", 1), old("series", 2)))
            val repository = CatalogSyncRepository(XtreamApiClient(recovery = routes), XtreamCatalogImporter(SafeCatalogRepository(db.safeCatalogDao())))
            val result = repository.syncMoviesAndSeries(settings.credentialsOrNull()!!)
            assertEquals(CatalogSyncStatus.Success, result.status)
            assertEquals(listOf(101), db.safeCatalogDao().itemsForType("movie").map { it.itemId })
            assertEquals(listOf(202), db.safeCatalogDao().itemsForType("series").map { it.itemId })
            assertEquals(5, count.get())
            val reopened = ProviderSettingsStore(file).load().settings
            assertEquals(primary, reopened.baseUrl); assertEquals(backup, reopened.preferredBaseUrl)
            val credentials = reopened.credentialsOrNull()!!
            assertTrue(XtreamApiClient(recovery = ProviderEndpointRecovery()).testConnection(credentials) is XtreamApiResult.Success)
            assertEquals(6, count.get())
            assertTrue(XtreamEndpointBuilder().buildVodStream(credentials, 101).startsWith("$backup/movie/"))
        } finally { server.close(); thread.join(1_000); db.close(); store.clear() }
    }
    @Test fun allUnreachableEndpointsLeaveTheSavedLibraryUntouched() = runBlocking(Dispatchers.IO) {
        val a = ServerSocket(0); val b = ServerSocket(0)
        val credentials = XtreamCredentials("http://127.0.0.1:${a.localPort}", "opaque", "opaque", listOf("http://127.0.0.1:${b.localPort}"))
        a.close(); b.close()
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val original = listOf(old("movie", 1), old("series", 2)); db.safeCatalogDao().insertAll(original)
            val result = CatalogSyncRepository(XtreamApiClient(recovery = ProviderEndpointRecovery()),
                XtreamCatalogImporter(SafeCatalogRepository(db.safeCatalogDao()))).syncMoviesAndSeries(credentials)
            assertEquals(CatalogSyncStatus.Failure, result.status)
            assertEquals(original, db.safeCatalogDao().itemsForType("movie") + db.safeCatalogDao().itemsForType("series"))
        } finally { db.close() }
    }
}

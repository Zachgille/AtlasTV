package com.atlas.atlastv.ui.media

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest

/** Explicit opt-in after real sync. Counts token coverage only; never prints artwork addresses. */
class ProviderArtworkRealCatalogConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun everyProviderPosterTokenResolvesWithoutRegistryOrLegacyJsonDependence() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.fullArtworkAcceptance") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = context.filesDir.listFiles().orEmpty().filter { it.isFile && (it.name == "provider_settings.json" || it.name.contains("watch") || it.name.contains("progress")) }
            .associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val db = AtlasDatabaseProvider.database(context)
        val reader = db.openHelper.readableDatabase
        fun scalar(sql: String): Int = reader.query(sql).use { it.moveToFirst(); it.getInt(0) }
        val total = scalar("SELECT COUNT(*) FROM safe_catalog_items WHERE posterState='ProviderImage'")
        val missing = scalar("SELECT COUNT(*) FROM safe_catalog_items c LEFT JOIN provider_artwork a ON c.posterProviderToken=a.token WHERE c.posterState='ProviderImage' AND a.token IS NULL")
        val addresses = scalar("SELECT COUNT(*) FROM provider_artwork")
        assertTrue(total > 5_000)
        assertEquals(0, missing)
        AtlasProviderImageRegistry.clearForTests()
        val index = AtlasProviderImageIndex.create(context)
        reader.query("SELECT posterProviderToken FROM safe_catalog_items WHERE posterState='ProviderImage' ORDER BY contentType,itemId LIMIT 1 OFFSET 5001").use { row ->
            assertTrue(row.moveToFirst()); assertNotNull(runBlocking(Dispatchers.IO) { index.resolveAsync(row.getString(0)) })
        }
        reader.query("SELECT posterProviderToken FROM safe_catalog_items WHERE posterState='ProviderImage' ORDER BY contentType DESC,itemId DESC LIMIT 1").use { row ->
            assertTrue(row.moveToFirst()); assertNotNull(runBlocking(Dispatchers.IO) { index.resolveAsync(row.getString(0)) })
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("top_nav_item_movies").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("top_nav_item_movies").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("loaded_provider_poster", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val loaded = compose.onAllNodesWithTag("loaded_provider_poster", useUnmergedTree = true).fetchSemanticsNodes().size
        val decodedBytes = AtlasProviderImageLoader.cachedMemoryBytes()
        val diskBytes = java.io.File(context.filesDir, AtlasProviderImageCache.DirectoryName).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".img") }.sumOf { it.length() }
        assertTrue(decodedBytes <= AtlasProviderImageLoader.MaxMemoryBytes)
        assertTrue(diskBytes <= AtlasProviderImageCache.DefaultMaxTotalBytes)
        val after = context.filesDir.listFiles().orEmpty().filter { it.name in before }.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        assertEquals(before, after)
        println("Artwork acceptance: $total catalog posters; $addresses indexed addresses; $missing missing tokens; $loaded visible decoded posters; decoded cache $decodedBytes bytes; disk cache $diskBytes bytes; saved state unchanged")
    }
}

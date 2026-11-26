package com.atlas.atlastv.data.discovery

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.catalog.displayTitle
import com.atlas.atlastv.ui.home.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Normal MainActivity and metadata-only upgrade; no personal import, membership, progress or video action. */
class DiscoveryRealConnectedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key);compose.waitForIdle() }
    private fun waitTag(tag: String) { compose.waitUntil(45000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused() { compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("focused_home_card").assertIsFocused();true}.getOrDefault(false)} }
    private fun part(tag: String)=compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("focused_home_card")),useUnmergedTree=true)
    private fun inRow(id: String)=compose.onAllNodes(hasTestTag("focused_home_card") and hasAnyAncestor(hasTestTag("home_row_$id"))).fetchSemanticsNodes().isNotEmpty()
    private fun personal()=compose.activity.filesDir.walkTopDown().filter {it.isFile && (it.name=="provider_settings.json" || it.name.contains("watchlist") || it.name.contains("progress") || it.name=="playback_preferences.preferences_pb")}.associate {it.relativeTo(compose.activity.filesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()}
    @Test fun independentImdbRatingsAndCatalogDatesDriveBothTypesAndRemoteReturnsToExactRankedPoster() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance")=="true")
        waitTag("home_hero_primary")
        val dao=AtlasDatabaseProvider.database(compose.activity).safeCatalogDao()
        val before=personal()
        val catalog=runBlocking(Dispatchers.IO) {dao.itemsForType("movie")+dao.itemsForType("series")}
        val repo=SafeCatalogRepository(dao)
        // Optional bulk upgrade runs behind cached Home, never one request per card.
        compose.waitUntil(300000) {runBlocking(Dispatchers.IO) {
            val categories=dao.discoveryCategories("movie")+dao.discoveryCategories("series")
            val complete=listOf("movie","series").all {type -> dao.discoveryCategories(type).all {cat ->dao.discoveryBackfill(type,cat.id)?.let {it.complete && it.catalogAtMs==cat.stamp}==true} }
            categories.isNotEmpty() && complete && dao.imdbMatchPage(ImdbDiscoveryIndex.Dataset,"",-1).isEmpty()
        } }
        val collections=runBlocking(Dispatchers.IO) {(repo.discoveryCollections("movie",12)+repo.discoveryCollections("series",12)).forHome()}
        for(id in listOf("movies_new","series_new","movies_rated","series_rated")) assertEquals(12,collections.first {it.id==id}.items.size)
        compose.waitUntil(20000) {compose.onAllNodesWithTag("home_row_movies_rated").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_DOWN);native(KeyEvent.KEYCODE_DPAD_DOWN);focused()
        var moves=0
        while(!inRow("movies_new") && moves++<8) native(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(inRow("movies_new"))
        for(id in listOf("movies_new","series_new","movies_rated","series_rated")) {
            assertTrue(id,inRow(id));val source=collections.first {it.id==id}
            val first=source.items.first();part("poster_card_title").assertTextEquals(displayTitle(first.title))
            val metadata=runBlocking(Dispatchers.IO) {dao.discoveryMetadata(first.contentType,first.itemId)}!!
            assertEquals(first.updatedAtMs,metadata.catalogAtMs)
            if(id.endsWith("_rated")) {assertTrue(metadata.ratingMilli!!>=7000);assertTrue(metadata.ratingSource == "imdb" && metadata.ratingVotes!!>=1000 && metadata.imdbId!!.startsWith("tt"))}
            if(id.endsWith("_new")) assertTrue(metadata.releaseSort!!>=20250101)
            if(id!="series_rated") native(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        repeat(11) {native(KeyEvent.KEYCODE_DPAD_RIGHT)};focused()
        val selected=collections.first {it.id=="series_rated"}.items.last()
        part("poster_card_title").assertTextEquals(displayTitle(selected.title))
        val bounds=part("focused_poster_card").fetchSemanticsNode().boundsInRoot
        assertHomeFooterVisible(part("focused_poster_card"),compose.onNodeWithTag("home_rows_viewport"),part("poster_card_footer"),part("poster_card_title"))
        native(KeyEvent.KEYCODE_DPAD_CENTER);waitTag("series_detail_title")
        compose.onNodeWithTag("series_detail_title").assertTextEquals(displayTitle(selected.title))
        compose.onAllNodesWithTag("playback_full_screen_surface").assertCountEquals(0)
        native(KeyEvent.KEYCODE_BACK);focused();assertTrue(inRow("series_rated"));part("poster_card_title").assertTextEquals(displayTitle(selected.title))
        assertEquals(bounds,part("focused_poster_card").fetchSemanticsNode().boundsInRoot)
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir,"discovery-real.png").outputStream().use {image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        assertEquals(before,personal());assertEquals(catalog,runBlocking(Dispatchers.IO) {dao.itemsForType("movie")+dao.itemsForType("series")})
        val report=StringBuilder()
        for(row in collections) {
            report.append(row.id).append(": ")
            for(item in row.items.take(3)) {
                val meta=runBlocking(Dispatchers.IO) {dao.discoveryMetadata(item.contentType,item.itemId)}!!
                report.append(item.itemId).append(" rating=").append(meta.ratingMilli).append(" release=").append(meta.releaseSort).append(" source=").append(meta.ratingSource?:meta.releaseSource).append("; ")
            }
            report.append('\n')
        }
        File(compose.activity.cacheDir,"discovery-source-evidence.txt").writeText(report.toString())
    }
}

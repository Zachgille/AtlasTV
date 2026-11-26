package com.atlas.atlastv.data.discovery

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.ui.catalog.displayTitle
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

/** Normal retained library, details only. No import, personal edits, data clear, fixtures or video. */
class HomeCollectionsRealConnectedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun key(code: Int) {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);compose.waitForIdle()}
    private fun tag(id: String) {compose.waitUntil(45000) {compose.onAllNodesWithTag(id).fetchSemanticsNodes().isNotEmpty()}}
    private fun focus(id: String) {compose.waitUntil(15000) {runCatching {compose.onNodeWithTag(id).assertIsFocused();true}.getOrDefault(false)}}
    private fun inRow(id: String): Boolean {
        val rowTag=when(id) {"continue_watching"->"continue_watching_row";"watchlist"->"watchlist_row";"popular"->"popular_row";else->"home_row_$id"}
        return compose.onAllNodes(hasTestTag("focused_home_card") and hasAnyAncestor(hasTestTag(rowTag))).fetchSemanticsNodes().isNotEmpty()
    }
    private fun personal()=compose.activity.filesDir.walkTopDown().filter {it.isFile && (it.name=="provider_settings.json" || it.name.contains("watchlist") || it.name.contains("progress") || it.name=="playback_preferences.preferences_pb")}.associate {it.relativeTo(compose.activity.filesDir).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()}
    @Test fun bothRealRatedCollectionsReachFinalBeyondPreviewAndReturnThroughDetailsToExactHomeAction() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance")=="true")
        tag("home_hero_primary")
        val dao=AtlasDatabaseProvider.database(compose.activity).safeCatalogDao()
        val before=personal();val catalog=runBlocking(Dispatchers.IO) {dao.itemsForType("movie")+dao.itemsForType("series")}
        val home=runBlocking(Dispatchers.IO) {com.atlas.atlastv.ui.home.HomeCatalogSource(compose.activity).load().state as com.atlas.atlastv.ui.home.HomeShellState.Ready}
        val report=StringBuilder()
        for(id in listOf("movies_rated","series_rated")) {
            focus("top_nav_item_search");key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN)
            val first=home.rows.indexOfFirst {row ->row.cards.any {it.id==home.initialFocusCardId}}.coerceAtLeast(0)
            try { compose.waitUntil(12000) {inRow(home.rows[first].id)} } catch (error: Throwable) {
                File(compose.activity.cacheDir,"home-collections-real-focus.txt").writeText("expected=${home.rows[first].id} rows=${home.rows.map { it.id }} initial=${home.initialFocusCardId}\n"+compose.onRoot(useUnmergedTree=true).printToString())
                val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(compose.activity.cacheDir,"home-collections-real-focus.png").outputStream().use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};shot.recycle();throw error
            }
            for(index in first+1..home.rows.indexOfFirst {it.id==id}) {
                key(KeyEvent.KEYCODE_DPAD_DOWN);compose.waitUntil(12000) {inRow(home.rows[index].id)}
            }
            assertTrue(inRow(id))
            val spec=DiscoveryCollectionSpec.fromId(id)!!
            val window=runBlocking(Dispatchers.IO) {dao.discoveryWindow(spec,System.currentTimeMillis(),Int.MAX_VALUE,null)}
            assertTrue(window.total>500);assertTrue(window.items.size<=200)
            val last=window.items.last()
            key(KeyEvent.KEYCODE_DPAD_LEFT);focus("home_collection_$id")
            val shelfBounds=compose.onNodeWithTag("home_collection_$id").fetchSemanticsNode().boundsInRoot
            val start=android.os.SystemClock.elapsedRealtime()
            key(KeyEvent.KEYCODE_DPAD_CENTER);focus("discovery_collection_tile_0")
            report.append(id).append(" total=").append(window.total).append(" entry-to-ready-ms=").append(android.os.SystemClock.elapsedRealtime()-start)
            key(KeyEvent.KEYCODE_DPAD_UP);focus("discovery_collection_back");key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER)
            tag("discovery_collection_options_dialog");key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER)
            val tile="discovery_collection_tile_${window.total-1}";focus(tile)
            compose.waitUntil(12000) {compose.onAllNodes(hasText(displayTitle(last.title)) and hasAnyAncestor(hasTestTag(tile)),useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNode(hasText(displayTitle(last.title)) and hasAnyAncestor(hasTestTag(tile)),useUnmergedTree=true).assertExists()
            val tileBounds=compose.onNodeWithTag(tile).fetchSemanticsNode().boundsInRoot
            assertTrue(tileBounds.top>=0 && tileBounds.bottom<=compose.activity.resources.displayMetrics.heightPixels)
            val lastShot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.cacheDir,"home-collection-$id-grid.png").outputStream().use {lastShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};lastShot.recycle()
            key(KeyEvent.KEYCODE_DPAD_CENTER);tag(if(spec.type=="movie") "movie_detail_title" else "series_detail_title")
            compose.onNodeWithTag(if(spec.type=="movie") "movie_detail_title" else "series_detail_title").assertTextEquals(displayTitle(last.title))
            compose.onAllNodesWithTag("playback_full_screen_surface").assertCountEquals(0)
            key(KeyEvent.KEYCODE_BACK);focus(tile);assertEquals(tileBounds,compose.onNodeWithTag(tile).fetchSemanticsNode().boundsInRoot)
            key(KeyEvent.KEYCODE_BACK);focus("home_collection_$id");assertEquals(shelfBounds,compose.onNodeWithTag("home_collection_$id").fetchSemanticsNode().boundsInRoot)
            val warmed=android.os.SystemClock.elapsedRealtime();key(KeyEvent.KEYCODE_DPAD_CENTER);focus(tile)
            report.append(" warm-reopen-to-selected-ms=").append(android.os.SystemClock.elapsedRealtime()-warmed)
            key(KeyEvent.KEYCODE_BACK);focus("home_collection_$id");assertEquals(shelfBounds,compose.onNodeWithTag("home_collection_$id").fetchSemanticsNode().boundsInRoot)
            report.append(" last-id=").append(last.itemId).append(" details/grid/shelf-return=exact\n")
            val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.cacheDir,"home-collection-$id-shelf.png").outputStream().use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};shot.recycle()
            key(KeyEvent.KEYCODE_BACK);focus("top_nav_item_search")
        }
        assertEquals(before,personal());assertEquals(catalog,runBlocking(Dispatchers.IO) {dao.itemsForType("movie")+dao.itemsForType("series")})
        File(compose.activity.cacheDir,"home-collections-real-evidence.txt").writeText(report.toString())
    }
}

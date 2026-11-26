package com.atlas.atlastv.data.discovery

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.DiscoveryCheckActivity
import com.atlas.atlastv.ui.home.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeCollectionsConnectedTest {
    @get:Rule val compose=createAndroidComposeRule<DiscoveryCheckActivity>()
    private fun key(code: Int) {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);compose.waitForIdle()}
    private fun tag(id: String) {compose.waitUntil(15000) {compose.onAllNodesWithTag(id).fetchSemanticsNodes().isNotEmpty()}}
    private fun row(id: String) {compose.waitUntil(12000) {compose.onAllNodes(hasTestTag("focused_home_card") and hasAnyAncestor(hasTestTag("home_row_$id"))).fetchSemanticsNodes().isNotEmpty()}}
    private fun focus(id: String) {compose.waitUntil(12000) {runCatching {compose.onNodeWithTag(id).assertIsFocused();true}.getOrDefault(false)}}
    private fun part(id: String)=compose.onNode(hasTestTag(id) and hasAnyAncestor(hasTestTag("focused_home_card")),useUnmergedTree=true)
    private fun enter() {tag("home_hero_primary");key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);row("movies_new")}
    @Test fun manyRowsAreVirtualizedRapidTravelKeepsColumnAndBackReturnsToTop() {
        enter()
        compose.onAllNodesWithTag("home_row_series_genre-sci-fi").assertCountEquals(0)
        val ids=(compose.activity.holder.state as HomeShellState.Ready).rows.map {it.id}
        val started=android.os.SystemClock.elapsedRealtime()
        for(id in ids.drop(1)) {key(KeyEvent.KEYCODE_DPAD_DOWN);row(id)}
        assertTrue(ids.size>=22)
        repeat(11) {key(KeyEvent.KEYCODE_DPAD_RIGHT)}
        focus("focused_home_card")
        val bounds=part("focused_poster_card").fetchSemanticsNode().boundsInRoot
        assertHomeFooterVisible(part("focused_poster_card"),compose.onNodeWithTag("home_rows_viewport"),part("poster_card_footer"),part("poster_card_title"))
        key(KeyEvent.KEYCODE_DPAD_CENTER);tag("discovery_destination");key(KeyEvent.KEYCODE_BACK);row(ids.last())
        assertEquals(bounds,part("focused_poster_card").fetchSemanticsNode().boundsInRoot)
        key(KeyEvent.KEYCODE_BACK);focus("top_nav_item_search")
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);row(ids.first())
        repeat(4) {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)}
        compose.waitForIdle();row(ids[4])
        File(compose.activity.cacheDir,"home-collection-controlled-timing.txt").writeText("22-row controlled native navigation/details/Back-to-top/rapid-travel journey ms="+(android.os.SystemClock.elapsedRealtime()-started))
    }
    @Test fun seeAllReachesBeyond500AndDetailsRestoreExactGridThenExactShelfAction() {
        enter()
        val ids=(compose.activity.holder.state as HomeShellState.Ready).rows.map {it.id}
        val target="movies_genre-drama"
        for(id in ids.drop(1).take(ids.indexOf(target))) {key(KeyEvent.KEYCODE_DPAD_DOWN);row(id)}
        row(target);key(KeyEvent.KEYCODE_DPAD_LEFT);focus("home_collection_$target")
        val bounds=compose.onNodeWithTag("home_collection_$target").fetchSemanticsNode().boundsInRoot
        val started=android.os.SystemClock.elapsedRealtime()
        key(KeyEvent.KEYCODE_DPAD_CENTER);focus("discovery_collection_tile_0")
        assertEquals(620,compose.activity.browser.grid!!.state.value.total)
        key(KeyEvent.KEYCODE_DPAD_UP);focus("discovery_collection_back")
        key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);tag("discovery_collection_options_dialog")
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);focus("discovery_collection_tile_619")
        compose.waitUntil(12000) {compose.activity.browser.grid!!.state.value.item(619)!=null}
        assertTrue(compose.activity.browser.grid!!.state.value.items.size<=200)
        val selected=compose.activity.browser.grid!!.state.value.item(619)!!
        val tileBounds=compose.onNodeWithTag("discovery_collection_tile_619").fetchSemanticsNode().boundsInRoot
        key(KeyEvent.KEYCODE_DPAD_CENTER);tag("discovery_destination");assertEquals(selected.streamId,compose.activity.selected!!.streamId)
        key(KeyEvent.KEYCODE_BACK);focus("discovery_collection_tile_619")
        assertEquals(tileBounds,compose.onNodeWithTag("discovery_collection_tile_619").fetchSemanticsNode().boundsInRoot)
        key(KeyEvent.KEYCODE_BACK);focus("home_collection_$target")
        assertEquals(bounds,compose.onNodeWithTag("home_collection_$target").fetchSemanticsNode().boundsInRoot)
        val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir,"home-collections-controlled.png").outputStream().use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};shot.recycle()
        File(compose.activity.cacheDir,"home-collection-grid-timing.txt").writeText("620-title full collection/last/details/exact grid and shelf return journey ms="+(android.os.SystemClock.elapsedRealtime()-started))
    }
}

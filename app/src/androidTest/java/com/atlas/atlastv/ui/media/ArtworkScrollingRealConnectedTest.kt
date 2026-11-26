package com.atlas.atlastv.ui.media

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.ui.home.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Arrival sampling during actual retained Home navigation, never a complete-artwork claim. */
class ArtworkScrollingRealConnectedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun key(code: Int) {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);compose.waitForIdle()}
    private fun rowTag(id: String)=when(id) {"continue_watching"->"continue_watching_row";"watchlist"->"watchlist_row";"popular"->"popular_row";else->"home_row_$id"}
    private fun focused(id: String)=compose.onAllNodes(hasTestTag("focused_home_card") and hasAnyAncestor(hasTestTag(rowTag(id)))).fetchSemanticsNodes().isNotEmpty()
    private fun loaded(id: String)=compose.onAllNodes(hasTestTag("loaded_provider_poster") and hasAnyAncestor(hasTestTag(rowTag(id))),useUnmergedTree=true).fetchSemanticsNodes().size
    @Test fun nativeScrollingReportsArtworkArrivalWithoutStealingFocus() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("atlastv.realCatalogAcceptance")=="true")
        compose.waitUntil(45000) {compose.onAllNodesWithTag("home_hero_primary").fetchSemanticsNodes().isNotEmpty()}
        val home=runBlocking(Dispatchers.IO) {HomeCatalogSource(compose.activity).load().state as HomeShellState.Ready}
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN)
        val first=home.rows.indexOfFirst {r ->r.cards.any {it.id==home.initialFocusCardId}}.coerceAtLeast(0)
        compose.waitUntil(12000) {focused(home.rows[first].id)}
        val report=StringBuilder("Actual Home; decoded visible poster counts sampled on entry, +500ms, +1500ms. Network/cache-dependent observations, not universal latency guarantees.\n")
        for(index in first..home.rows.indexOfFirst {it.id=="series_rated"}) {
            val id=home.rows[index].id
            if(index>first) {key(KeyEvent.KEYCODE_DPAD_DOWN);compose.waitUntil(12000) {focused(id)}}
            val counts=mutableListOf(loaded(id))
            Thread.sleep(500);compose.waitForIdle();counts+=loaded(id)
            Thread.sleep(1000);compose.waitForIdle();counts+=loaded(id)
            assertTrue("Late art must preserve the focused shelf",focused(id))
            report.append(id).append(" decoded=").append(counts).append('\n')
        }
        val shot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir,"artwork-speed-real-home.png").outputStream().use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};shot.recycle()
        File(compose.activity.cacheDir,"artwork-speed-real-arrival.txt").writeText(report.toString())
        println(report)
        key(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
    }
}

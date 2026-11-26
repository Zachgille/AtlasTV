package com.atlas.atlastv.data.discovery

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.DiscoveryCheckActivity
import com.atlas.atlastv.ui.home.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Native remote journeys while the secondary source is deliberately held. */
class HomeProgressiveConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch() = ActivityScenario.launch<DiscoveryCheckActivity>(Intent(
        InstrumentationRegistry.getInstrumentation().targetContext,DiscoveryCheckActivity::class.java).putExtra("progressive",true))
    private fun key(code: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code); compose.waitForIdle() }
    private fun focus(tag: String) { compose.waitUntil(12000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused();true }.getOrDefault(false) } }
    private fun tag(tag: String) { compose.waitUntil(12000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun inRow(id: String) { compose.waitUntil(12000) { compose.onAllNodes(hasTestTag("focused_home_card") and hasAnyAncestor(hasTestTag("home_row_$id"))).fetchSemanticsNodes().isNotEmpty() } }
    private fun enter() { tag("home_hero_primary"); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); inRow("movies_new") }
    private fun complete(s: ActivityScenario<DiscoveryCheckActivity>) {
        s.onActivity { it.genreRelease.complete(Unit) }
        compose.waitUntil(12000) { var done=false;s.onActivity { done=(it.holder.state as? HomeShellState.Ready)?.rows?.size==22 && !it.holder.isRefreshing };done }
    }
    @Test fun leadingRowsAcceptDeepInputBeforeGenresAndLateRowsPreserveExactCardBounds() {
        launch().use { s ->
            enter(); repeat(8) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            var selected="";s.onActivity {
                val ready=it.holder.state as HomeShellState.Ready
                assertEquals(6,ready.rows.size);assertTrue(it.holder.isRefreshing)
                assertFalse(it.genreRelease.isCompleted);assertTrue(it.genreStarts>0)
                selected=it.holder.selectedCardId!!
            }
            focus("focused_home_card")
            val bounds=compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot
            complete(s);focus("focused_home_card");inRow("movies_new")
            assertEquals(bounds,compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot)
            s.onActivity { assertEquals(selected,it.holder.selectedCardId) }
            repeat(4) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN) }
            compose.waitForIdle();inRow("movies_recent")
            key(KeyEvent.KEYCODE_BACK);focus("top_nav_item_search")
        }
    }
    @Test fun detailsBeforeGenreCompletionCancelWorkAndReturnToExactDeepPoster() {
        launch().use { s ->
            enter();repeat(7) {key(KeyEvent.KEYCODE_DPAD_RIGHT)}
            var selected="";s.onActivity {selected=it.holder.selectedCardId!!}
            val bounds=compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot
            key(KeyEvent.KEYCODE_DPAD_CENTER);tag("discovery_destination")
            s.onActivity {
                assertEquals(selected,it.selected!!.id);assertTrue(it.genreCancellations>0)
                it.genreRelease.complete(Unit)
                assertEquals(6,(it.holder.state as HomeShellState.Ready).rows.size)
            }
            key(KeyEvent.KEYCODE_BACK);focus("focused_home_card");complete(s)
            assertEquals(bounds,compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot)
            s.onActivity {assertEquals(selected,it.holder.selectedCardId);assertTrue(it.genreStarts>=2)}
        }
    }
    @Test fun warmRefreshRetainsDeepGenreAndCoordinatesUntilAllRowsFinish() {
        launch().use { s ->
            enter();complete(s)
            val target="movies_genre-drama"
            var ids=emptyList<String>();s.onActivity {ids=(it.holder.state as HomeShellState.Ready).rows.map {row->row.id}}
            for(id in ids.drop(1).take(ids.indexOf(target))) {key(KeyEvent.KEYCODE_DPAD_DOWN);inRow(id)}
            repeat(5) {key(KeyEvent.KEYCODE_DPAD_RIGHT)}
            val bounds=compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot
            var selected="";s.onActivity {selected=it.holder.selectedCardId!!;it.genreRelease=CompletableDeferred();it.holder.refresh()}
            compose.waitUntil(12000) {var loading=false;s.onActivity {loading=it.holder.isRefreshing && it.genreStarts>=2};loading}
            focus("focused_home_card");inRow(target)
            s.onActivity {assertEquals(22,(it.holder.state as HomeShellState.Ready).rows.size);assertEquals(selected,it.holder.selectedCardId)}
            assertEquals(bounds,compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot)
            complete(s);focus("focused_home_card");inRow(target)
            assertEquals(bounds,compose.onNodeWithTag("focused_home_card").fetchSemanticsNode().boundsInRoot)
        }
    }
}

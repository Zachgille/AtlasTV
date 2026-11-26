package com.atlas.atlastv.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.LibraryUsabilityCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibraryUsabilityConnectedTest {
    @get:Rule val compose=createAndroidComposeRule<LibraryUsabilityCheckActivity>()
    @Test fun cleanupIsRemoteReachableAndLastRemovalLeavesUsableDoneAction() {
        waitFor("continue_watching_edit")
        press("top_nav_item_search",Key.DirectionDown)
        press("home_hero_primary",Key.DirectionDown)
        press("focused_home_card",Key.DirectionUp)
        compose.onNodeWithTag("continue_watching_edit").assertIsFocused()
        press("continue_watching_edit",Key.Enter); waitFor("continue_watching_editor")
        press("continue_watching_done",Key.DirectionDown)
        press("continue_watching_remove_sample-movie",Key.Enter)
        compose.waitUntil(5_000) { compose.activity.removed.size==1 }
        compose.onNodeWithTag("continue_watching_done").assertIsFocused()
        press("continue_watching_done",Key.DirectionDown)
        press("continue_watching_remove_sample-show",Key.Enter)
        compose.waitUntil(5_000) { compose.activity.removed.size==2 }
        compose.onNodeWithTag("continue_watching_done").assertIsFocused()
        press("continue_watching_done",Key.Enter)
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        compose.runOnIdle { assertEquals(0,compose.activity.playbackRequests) }
    }
    @Test fun refreshStartsOnceWhileBackRemainsAvailableAndCompletionInvalidatesCaller() {
        compose.runOnIdle { compose.activity.settingsOpen=true }; waitFor("provider_sync_action")
        compose.onNodeWithTag("provider_sync_action").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.activity.refreshRequests==1 }
        compose.onNodeWithTag("provider_sync_action").performClick()
        compose.runOnIdle { assertEquals(1,compose.activity.refreshRequests) }
        compose.onNodeWithTag("settings_back").assertIsDisplayed().performClick()
        waitFor("top_nav_item_search")
        compose.runOnIdle { compose.activity.syncGate.complete(Unit) }
        compose.waitUntil(5_000) { compose.activity.refreshCallbacks>0 }
        compose.runOnIdle { assertFalse(compose.activity.settings.state.isSyncing) }
    }
    private fun press(tag:String,key:Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun waitFor(tag:String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
}

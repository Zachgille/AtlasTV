package com.atlas.atlastv.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.atlas.atlastv.debug.HomeLoadingCheckActivity
import org.junit.Rule
import org.junit.Test

class HomeLoadingConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<HomeLoadingCheckActivity>()

    @Test fun loadingAndRefreshKeepNavigationAndCardFocusStable() {
        waitFor("home_loading")
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        press("top_nav_item_search", Key.DirectionRight)
        compose.onNodeWithTag("top_nav_item_home").assertIsFocused()
        compose.runOnIdle { compose.activity.complete() }
        waitFor("hero_area")
        compose.onNodeWithTag("top_nav_item_home").assertIsFocused()
        press("top_nav_item_home", Key.DirectionDown)
        compose.onNodeWithTag("home_hero_primary").assertIsFocused()
        press("home_hero_primary", Key.DirectionDown)
        compose.onNodeWithTag("focused_home_card").assertIsFocused()
        compose.runOnIdle { compose.activity.holder.refresh() }
        waitFor("home_refreshing")
        compose.runOnIdle { compose.activity.complete("Replacement", "replacement") }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("focused_home_card").assertIsFocused()
        press("focused_home_card", Key.DirectionUp)
        press("home_hero_primary", Key.DirectionUp)
        compose.runOnIdle { compose.activity.holder.refresh() }
        waitFor("home_refreshing")
        compose.runOnIdle { compose.activity.complete("New rows", "nav-update") }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
        press("top_nav_item_search", Key.DirectionDown)
        press("home_hero_primary", Key.DirectionDown)
        compose.runOnIdle { compose.activity.holder.refresh() }
        waitFor("home_refreshing")
        compose.onNodeWithTag("focused_home_card").assertIsFocused()
        compose.runOnIdle { compose.activity.complete("Updated movie") }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("focused_home_card").assertIsFocused()
        compose.runOnIdle { compose.activity.holder.refresh() }
        waitFor("home_refreshing")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("home_refresh_error")
        compose.onNodeWithTag("focused_home_card").assertIsFocused()
        press("focused_home_card", Key.DirectionUp)
        press("home_hero_primary", Key.DirectionUp)
        press("top_nav_item_search", Key.DirectionDown)
        compose.onNodeWithTag("home_refresh_retry").assertIsFocused()
        press("home_refresh_retry", Key.Enter)
        compose.runOnIdle { compose.activity.complete() }
        compose.waitUntil(5_000) { !compose.activity.holder.isRefreshing }
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
    }

    @Test fun navigationWhileLoadingCancelsTheHomeLoad() {
        waitFor("home_loading")
        compose.onNodeWithTag("top_nav_item_search").performClick()
        waitFor("home_check_destination")
        compose.waitUntil(5_000) { compose.activity.cancelledLoads.get() == 1 }
    }

    @Test fun failedFirstLoadOffersRemoteRetry() {
        waitFor("home_loading")
        compose.runOnIdle { compose.activity.fail() }
        waitFor("home_retry")
        press("top_nav_item_search", Key.DirectionDown)
        compose.onNodeWithTag("home_retry").assertIsFocused()
        press("home_retry", Key.Enter)
        waitFor("home_loading")
        compose.runOnIdle { compose.activity.complete() }
        waitFor("hero_area")
        compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
    }

    @Test fun emptyLibraryOffersSettingsAsThePrimaryAction() {
        waitFor("home_loading")
        compose.runOnIdle { compose.activity.empty() }
        waitFor("home_open_settings")
        press("top_nav_item_search", Key.DirectionDown)
        compose.onNodeWithTag("home_open_settings").assertIsFocused()
        press("home_open_settings", Key.Enter)
        waitFor("home_check_destination")
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) } }
}


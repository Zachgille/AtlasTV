package com.atlas.atlastv.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.atlas.atlastv.debug.HomeHeroCheckActivity
import com.atlas.atlastv.ui.settings.CatalogFreshnessState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeHeroConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<HomeHeroCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun loaded() { waitFor("home_hero_primary"); compose.runOnIdle { compose.activity.complete() }; waitFor("hero_synopsis") }

    @Test fun arrivingMetadataKeepsDetailsFocusAndFirstRowFullyVisible() {
        waitFor("home_hero_primary"); press("top_nav_item_search", Key.DirectionDown); press("home_hero_primary", Key.DirectionRight)
        compose.runOnIdle { compose.activity.complete() }; waitFor("hero_synopsis")
        compose.onNodeWithTag("home_hero_details").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("hero_title").assertTextEquals("A Movie")
        compose.onNodeWithTag("hero_metadata").assertTextEquals("2010 · Action / Mystery · 2h 28m")
        compose.onNodeWithTag("continue_watching_row").assertIsDisplayed()
        val first = compose.onNodeWithTag("home_card_continue-movie").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("home_screen").fetchSemanticsNode().boundsInRoot
        assertTrue("First card should fit without entering the rows", first.bottom <= screen.bottom)
    }

    @Test fun stalledMetadataKeepsResumeUsableAndReturnRestoresItsAction() {
        waitFor("home_hero_primary"); press("top_nav_item_search", Key.DirectionDown); press("home_hero_primary", Key.Enter)
        waitFor("home_hero_destination"); compose.runOnIdle { assertEquals("continue-movie", compose.activity.selectedCardId); compose.activity.returnHome() }
        waitFor("home_hero_primary"); compose.onNodeWithTag("home_hero_primary").assertIsFocused().assertTextEquals("Resume")
        press("home_hero_primary", Key.DirectionUp); compose.onNodeWithTag("top_nav_item_search").assertIsFocused()
    }

    @Test fun longSynopsisCannotHideActionsOrOverlapThem() {
        waitFor("home_hero_primary")
        compose.runOnIdle {
            compose.activity.movieTitle = "A Very Long Movie Title With Many Words And Another Subtitle And More Words (2010)"
            compose.activity.holder.refresh()
            compose.activity.complete(value = HomeHeroMetadata("A long story about many characters. ".repeat(100), "Action / Mystery / Adventure", "2010", 148))
        }
        waitFor("hero_synopsis")
        compose.onNodeWithTag("home_hero_primary").assertIsDisplayed()
        compose.onNodeWithTag("home_hero_details").assertIsDisplayed()
        compose.onNodeWithTag("catalog_refresh_prompt").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("hero_synopsis").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("home_hero_primary").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun rowSelectionChangesPreviewAndDetailsReturnRestoresTheSelectedShow() {
        loaded(); press("top_nav_item_search", Key.DirectionDown); press("home_hero_primary", Key.DirectionDown)
        press("focused_home_card", Key.DirectionRight)
        compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        compose.runOnIdle { compose.activity.complete("show-2", HomeHeroMetadata("A second story.", "Drama", "2015")) }; waitFor("hero_synopsis")
        compose.onNodeWithTag("hero_synopsis").assertTextEquals("A second story.")
        press("focused_home_card", Key.DirectionUp); compose.onNodeWithTag("continue_watching_edit").assertIsFocused()
        press("continue_watching_edit", Key.DirectionUp); press("home_hero_primary", Key.DirectionRight)
        press("home_hero_details", Key.Enter); waitFor("home_hero_destination")
        compose.runOnIdle { assertEquals("continue-show", compose.activity.selectedCardId); compose.activity.returnHome() }
        waitFor("home_hero_details"); compose.onNodeWithTag("home_hero_details").assertIsFocused()
        compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        compose.runOnIdle { assertEquals(1, compose.activity.requests.count { it == "show-2" }) }
    }

    @Test fun compactRefreshCannotStartTwiceAndKeepsFocusThroughCompletion() {
        loaded(); press("top_nav_item_search", Key.DirectionDown); press("home_hero_primary", Key.DirectionRight)
        press("home_hero_details", Key.DirectionRight); press("catalog_refresh_prompt", Key.Enter)
        compose.onNodeWithTag("catalog_refresh_prompt").assertIsFocused().assertTextEquals("Refreshing…")
        press("catalog_refresh_prompt", Key.Enter)
        compose.runOnIdle { assertEquals(1, compose.activity.refreshRequests); compose.activity.freshness = CatalogFreshnessState.Fresh }
        compose.onNodeWithTag("catalog_refresh_prompt").assertIsFocused().assertTextEquals("Refresh Library")
        compose.onAllNodesWithText("Administrative counts should stay out of Home").assertCountEquals(0)
        press("catalog_refresh_prompt", Key.DirectionLeft); compose.onNodeWithTag("home_hero_details").assertIsFocused()
    }

    @Test fun cardDestinationReturnRestoresExactRowCard() {
        loaded(); press("top_nav_item_search", Key.DirectionDown); press("home_hero_primary", Key.DirectionDown)
        press("focused_home_card", Key.DirectionRight); press("focused_home_card", Key.Enter); waitFor("home_hero_destination")
        compose.runOnIdle { compose.activity.returnHome() }; waitFor("focused_home_card")
        compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        compose.onNodeWithTag("focused_home_card").assertIsFocused()
    }

    @Test fun deepHorizontalCardReturnComposesAndFocusesTheExactItem() {
        loaded(); press("top_nav_item_search", Key.DirectionDown); press("home_hero_primary", Key.DirectionDown)
        repeat(9) { press("focused_home_card", Key.DirectionRight) }
        compose.onNodeWithTag("hero_title").assertTextEquals("Extra Movie 17")
        press("focused_home_card", Key.Enter); waitFor("home_hero_destination")
        compose.runOnIdle { assertEquals("continue-extra-17", compose.activity.selectedCardId); compose.activity.returnHome() }
        waitFor("focused_home_card"); compose.onNodeWithTag("focused_home_card").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("hero_title").assertTextEquals("Extra Movie 17")
    }
}

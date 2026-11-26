package com.atlas.atlastv.ui.browse

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Uses the visible overflow route; no private navigation or test-only product shortcut. */
fun ComposeTestRule.openGridOptions(tags: String) {
    onNodeWithTag("${tags}_options").performClick()
    waitUntil(5_000) { onAllNodesWithTag("${tags}_options_dialog").fetchSemanticsNodes().isNotEmpty() }
}

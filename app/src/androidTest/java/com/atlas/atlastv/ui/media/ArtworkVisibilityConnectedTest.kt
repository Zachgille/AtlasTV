package com.atlas.atlastv.ui.media

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.atlas.atlastv.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class ArtworkVisibilityConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun clippedAndReturningCardsChangePriorityWithoutRecreatingTheirRequestState() {
        val priority = MutableStateFlow(ArtworkPriority.Prefetch)
        val x = mutableStateOf(0.dp)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                Box(Modifier.size(120.dp).clipToBounds()) {
                    Box(Modifier.offset(x = x.value).size(60.dp).artworkVisibility(priority))
                }
            } }
            compose.waitUntil(8_000) { priority.value == ArtworkPriority.Visible }
            compose.runOnIdle { x.value = 100.dp }
            compose.waitForIdle()
            check(priority.value == ArtworkPriority.Visible) // Partly visible posters still matter.
            compose.runOnIdle { x.value = 140.dp }
            compose.waitUntil(8_000) { priority.value == ArtworkPriority.Prefetch }
            compose.runOnIdle { x.value = 0.dp }
            compose.waitUntil(8_000) { priority.value == ArtworkPriority.Visible }
        }
    }
}

package com.atlas.atlastv.ui.media

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.MutableStateFlow

/** Clipped/offscreen/precomposed cards remain speculative without restarting their shared load. */
internal fun Modifier.artworkVisibility(priority: MutableStateFlow<ArtworkPriority>): Modifier =
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        priority.value = if (coordinates.isAttached && bounds.width > 0f && bounds.height > 0f)
            ArtworkPriority.Visible else ArtworkPriority.Prefetch
    }

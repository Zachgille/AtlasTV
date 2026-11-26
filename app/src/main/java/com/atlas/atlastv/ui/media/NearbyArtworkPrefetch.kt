package com.atlas.atlastv.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Only the next three offscreen posters; foreground loads retain three of four shared slots. */
@Composable
fun NearbyArtworkPrefetch(images: List<AtlasImageRef>, lastVisibleIndex: () -> Int) {
    val context = LocalContext.current.applicationContext
    val files = context.filesDir
    val index = remember(context) { AtlasProviderImageIndex.create(context) }
    val currentImages by rememberUpdatedState(images)
    val currentIndex by rememberUpdatedState(lastVisibleIndex)
    LaunchedEffect(files, images) {
        snapshotFlow { currentIndex() }.distinctUntilChanged().collectLatest { last ->
            if (last < 0) return@collectLatest
            delay(100) // Let remote focus and currently visible images run first.
            currentImages.drop(last + 1).take(3).mapNotNull { it.providerImageToken }.distinct().forEach { token ->
                AtlasProviderImageLoader.load(token, files, index, priority = ArtworkPriority.Prefetch)
            }
        }
    }
}

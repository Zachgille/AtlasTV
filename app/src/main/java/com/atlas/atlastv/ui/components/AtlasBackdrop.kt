package com.atlas.atlastv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.atlas.atlastv.ui.media.*

/** Reuses the bounded artwork loader; a missing/failed landscape keeps the supplied fallback. */
@Composable
fun AtlasBackdrop(image: AtlasImageRef, modifier: Modifier = Modifier, fallback: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current.applicationContext
    val index = remember(context) {
        AtlasProviderImageLoader.initialize(context)
        AtlasProviderImageIndex.create(context)
    }
    var size by remember { mutableStateOf<ArtworkSize?>(null) }
    var bitmap by remember(image.providerImageToken) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image, size) {
        val target = size ?: return@LaunchedEffect
        bitmap = if (image.state == AtlasImageState.ProviderImage)
            AtlasProviderImageLoader.load(image.providerImageToken, context.filesDir, index, target)
        else null
    }
    Box(modifier.onSizeChanged { if (it.width > 0 && it.height > 0) size = ArtworkSize.forPixels(it.width, it.height) }) {
        val loaded = bitmap
        if (loaded != null) Image(loaded, null, Modifier.matchParentSize().testTag("loaded_detail_backdrop"), contentScale = ContentScale.Crop)
        else Box(Modifier.matchParentSize().testTag("detail_artwork_fallback"), content = fallback)
    }
}

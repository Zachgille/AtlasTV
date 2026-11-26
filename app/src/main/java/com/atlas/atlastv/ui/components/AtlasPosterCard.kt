package com.atlas.atlastv.ui.components

import com.atlas.atlastv.ui.catalog.displayTitle
import com.atlas.atlastv.ui.catalog.CardWatchProgress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.media.AtlasProviderImageIndex
import com.atlas.atlastv.ui.media.AtlasProviderImageLoader
import com.atlas.atlastv.ui.media.ArtworkSize
import com.atlas.atlastv.ui.media.ArtworkPriority
import com.atlas.atlastv.ui.media.artworkVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import com.atlas.atlastv.ui.media.PosterArtworkFallback
import androidx.compose.runtime.staticCompositionLocalOf
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText

/** Scoped artwork transport seam; normal UI always uses the shared bounded provider loader. */
val LocalPosterArtworkLoader = staticCompositionLocalOf<(suspend (String, ArtworkSize) -> ImageBitmap?)?> { null }

@Composable
fun AtlasPosterCard(
    title: String,
    subtitle: String,
    badge: String,
    imageRef: AtlasImageRef,
    focused: Boolean,
    progressLabel: String?,
    modifier: Modifier = Modifier,
    showText: Boolean = true,
    artworkPadding: Dp = 14.dp,
    dimArtwork: Boolean = true,
    cardProgress: CardWatchProgress? = null,
    posterFallbacks: List<AtlasImageRef> = emptyList(),
    artworkIdentity: String? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface)
            .border(BorderStroke(if (focused) 4.dp else 1.dp, if (focused) AtlasAccent else AtlasSurfaceFocused), shape)
            .testTag("poster_card")
            .semantics { contentDescription = "poster_card" },
    ) {
        if (focused) Box(Modifier.matchParentSize().testTag("focused_poster_card"))
        Column(Modifier.fillMaxSize()) {
        AtlasFallbackPoster(
            title = title,
            subtitle = subtitle,
            badge = badge,
            imageRef = imageRef,
            posterFallbacks = posterFallbacks,
            artworkIdentity = artworkIdentity,
            progressLabel = progressLabel,
            modifier = Modifier.fillMaxWidth().weight(1f),
            showText = false,
            showAccent = false,
            artworkPadding = if (showText) 0.dp else artworkPadding,
            dimArtwork = if (showText) false else dimArtwork,
        )
        if (showText) cardProgress?.fraction?.let { AtlasCardProgressBar(it, Modifier.padding(horizontal = 4.dp)) }
        if (showText) Column(Modifier.fillMaxWidth().height(if (cardProgress != null || progressLabel != null) 72.dp else 60.dp).testTag("poster_card_footer").padding(horizontal = 10.dp, vertical = 6.dp)) {
            AtlasPosterLabel(displayTitle(title), color = AtlasText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = if (cardProgress != null) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("poster_card_title"))
            if (cardProgress != null) {
                cardProgress.episodeLabel?.let { AtlasPosterLabel(it, color = AtlasMutedText, fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card_episode_label")) }
                AtlasPosterLabel(cardProgress.timeLabel, color = AtlasMutedText, fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("card_time_label"))
            } else progressLabel?.let { AtlasPosterLabel(it, color = AtlasMutedText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        }
    }
}

@Composable
fun AtlasFallbackPoster(
    title: String,
    subtitle: String,
    badge: String,
    imageRef: AtlasImageRef,
    progressLabel: String?,
    modifier: Modifier = Modifier,
    showText: Boolean = true,
    showAccent: Boolean = true,
    artworkPadding: Dp = 14.dp,
    dimArtwork: Boolean = true,
    posterFallbacks: List<AtlasImageRef> = emptyList(),
    artworkIdentity: String? = null,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val customLoader = LocalPosterArtworkLoader.current
    val imageIndex = remember(context, customLoader) {
        if (customLoader == null) {
            AtlasProviderImageLoader.initialize(context)
            AtlasProviderImageIndex.create(context)
        } else null
    }
    var targetSize by remember { mutableStateOf<ArtworkSize?>(null) }
    val artworkPriority = remember { MutableStateFlow(ArtworkPriority.Prefetch) }
    var providerImage by remember(imageRef, artworkIdentity) { mutableStateOf<ImageBitmap?>(null) }
    var savedImage by remember(imageRef, artworkIdentity) { mutableStateOf(false) }
    var providerLoadAttempted by remember(imageRef, artworkIdentity) { mutableStateOf(false) }
    val fallback = remember(imageRef, artworkIdentity, targetSize, customLoader) { PosterArtworkFallback<ImageBitmap>() }
    LaunchedEffect(fallback, posterFallbacks) {
        val target = targetSize ?: return@LaunchedEffect
        val result = fallback.load(imageRef, posterFallbacks) { token ->
            if (customLoader != null) customLoader(token, target)
            else AtlasProviderImageLoader.load(token, filesDir, imageIndex, target, priorityChanges = artworkPriority)
        }
        if (result != null) { providerImage = result.value; savedImage = result.saved }
        providerLoadAttempted = true
    }
    val imageTag = if (providerImage != null) "provider_poster_image_state" else when (imageRef.state) {
        AtlasImageState.SafeImage -> "loaded_poster_image_state"
        AtlasImageState.ProviderImage -> if (providerLoadAttempted && providerImage == null) {
            "image_load_failure_fallback"
        } else {
            "provider_poster_image_state"
        }
        AtlasImageState.ProviderImageHidden -> "image_load_failure_fallback"
        AtlasImageState.Missing -> "fallback_poster_card"
    }
    Box(
        modifier = modifier
            .artworkVisibility(artworkPriority)
            .onSizeChanged { if (it.width > 0 && it.height > 0) targetSize = ArtworkSize.forPixels(it.width, it.height) }
            .background(
                Brush.verticalGradient(
                    listOf(AtlasSurfaceFocused.copy(alpha = 0.74f), AtlasSurface, AtlasSurface.copy(alpha = 0.92f)),
                ),
            )
            .testTag(imageTag)
            .semantics { contentDescription = imageTag }
            .padding(artworkPadding),
    ) {
        // Preserve the texture for transparency; opaque crop-filled posters completely cover it.
        val needsTexture = remember(providerImage) { providerImage?.asAndroidBitmap()?.hasAlpha() != false }
        if (needsTexture) PosterTexture(Modifier.matchParentSize())
        providerImage?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().testTag("loaded_provider_poster"),
            )
            if (savedImage) Box(Modifier.matchParentSize().testTag("saved_card_poster"))
            if (dimArtwork) Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AtlasSurface.copy(alpha = 0.06f),
                                AtlasSurface.copy(alpha = 0.28f),
                                AtlasSurface.copy(alpha = 0.82f),
                            ),
                        ),
                    ),
            )
        }
        if (showText) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = badge,
                    color = AtlasAccent,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = displayTitle(title),
                        color = AtlasText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        color = AtlasMutedText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    progressLabel?.let {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(AtlasAccent, RoundedCornerShape(4.dp))
                                .testTag("card_progress_bar")
                                .semantics { contentDescription = "card_progress_bar" },
                        )
                        Text(
                            text = it,
                            color = AtlasAccent,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else if (showAccent) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(AtlasAccent.copy(alpha = 0.88f), RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
private fun PosterTexture(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val step = 28.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(
                color = Color.White.copy(alpha = 0.035f),
                start = androidx.compose.ui.geometry.Offset(x, size.height),
                end = androidx.compose.ui.geometry.Offset(x + size.height, 0f),
                strokeWidth = 2.dp.toPx(),
            )
            x += step
        }
    }
}

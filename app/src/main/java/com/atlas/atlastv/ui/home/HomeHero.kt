package com.atlas.atlastv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.catalog.displayTitle
import com.atlas.atlastv.ui.components.AtlasBackdrop
import com.atlas.atlastv.ui.components.AtlasPosterCard
import com.atlas.atlastv.ui.components.AtlasPosterLabel
import com.atlas.atlastv.ui.media.isDisplayFallback
import com.atlas.atlastv.ui.theme.*

/** Fixed-height preview: optional artwork/text cannot push the first row below the viewport. */
@Composable
internal fun HomeHero(card: HomeCard?, metadata: HomeHeroMetadata, fromSaved: Boolean = false, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(12.dp))
        .background(AtlasSurface).testTag("hero_area")) {
        AtlasBackdrop(metadata.backdrop, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.65f)) {
            card?.let {
                AtlasPosterCard(it.title, "", "", if (metadata.poster.isDisplayFallback()) it.posterImage else metadata.poster,
                    focused = false, progressLabel = null, showText = false, artworkPadding = 0.dp, dimArtwork = false,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).width(94.dp).height(142.dp).testTag("hero_poster_fallback"))
            }
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to AtlasBackground, .3f to AtlasBackground.copy(alpha = .95f),
            .65f to AtlasBackground.copy(alpha = .72f), 1f to AtlasBackground.copy(alpha = .06f))))
        Column(Modifier.fillMaxWidth(.66f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f).testTag(if (fromSaved) "hero_saved_info" else "hero_online_info"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AtlasPosterLabel(displayTitle(card?.title ?: "Lumaire"), color = AtlasText, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("hero_title"))
                val line = listOfNotNull(metadata.releaseYear ?: card?.year,
                    metadata.genre ?: card?.let { if (it.contentType == HomeContentType.SeriesEpisode) "Show" else it.contentType.displayName },
                    com.atlas.atlastv.core.media.MediaRuntime.labelMinutes(metadata.durationMinutes)).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, color = AtlasMutedText, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("hero_metadata"))
                metadata.synopsis?.let { Text(it, color = AtlasText.copy(alpha = .88f), style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("hero_synopsis")) }
            }
            actions()
        }
    }
}

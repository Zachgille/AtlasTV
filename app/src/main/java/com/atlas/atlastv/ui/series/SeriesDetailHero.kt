package com.atlas.atlastv.ui.series

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
import com.atlas.atlastv.ui.catalog.releaseYearLabel
import com.atlas.atlastv.ui.components.AtlasBackdrop
import com.atlas.atlastv.ui.components.AtlasPosterCard
import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.media.isDisplayFallback
import com.atlas.atlastv.ui.theme.*

@Composable
internal fun SeriesDetailHero(title: String, poster: AtlasImageRef, metadata: SeriesShowMetadata,
    seasonCount: Int?, cacheStatus: String? = null, fromSaved: Boolean = false, refreshAction: @Composable () -> Unit = {}, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(264.dp).clip(RoundedCornerShape(12.dp))
        .background(AtlasSurface).testTag("series_detail_header")) {
        AtlasBackdrop(metadata.backdrop, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.66f)) {
            AtlasPosterCard(title, "", "", if (metadata.poster.isDisplayFallback()) poster else metadata.poster,
                focused = false, progressLabel = null, showText = false, artworkPadding = 0.dp, dimArtwork = false,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).width(148.dp).height(222.dp).testTag("series_detail_poster"))
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to AtlasBackground, .35f to AtlasBackground.copy(alpha = .96f),
            .66f to AtlasBackground.copy(alpha = .72f), 1f to AtlasBackground.copy(alpha = .08f))))
        Column(Modifier.fillMaxWidth(.66f).fillMaxHeight().padding(16.dp).testTag("series_detail_hero"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.testTag(if (fromSaved) "series_detail_saved_info" else "series_detail_online_info"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(displayTitle(title), color = AtlasText, style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("series_detail_title"))
                    refreshAction()
                }
                val line = listOfNotNull(cacheStatus, metadata.releaseYear ?: releaseYearLabel(title), metadata.genre,
                    seasonCount?.let { "$it ${if (it == 1) "season" else "seasons"}" }).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("series_detail_seasons"))
                metadata.synopsis?.let { Text(it, color = AtlasText.copy(alpha = .88f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("series_detail_synopsis")) }
            }
            actions()
        }
    }
}

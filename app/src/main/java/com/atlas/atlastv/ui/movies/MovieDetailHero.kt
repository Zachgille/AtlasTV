package com.atlas.atlastv.ui.movies

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
import com.atlas.atlastv.ui.media.isDisplayFallback
import com.atlas.atlastv.ui.theme.*

@Composable
internal fun MovieDetailHero(movie: MovieItem, metadata: MovieMetadata, resumeLabel: String?, cacheStatus: String? = null,
    fromSaved: Boolean = false, refreshAction: @Composable () -> Unit = {}, actions: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(264.dp).clip(RoundedCornerShape(12.dp))
        .background(AtlasSurface).testTag("movie_detail_hero")) {
        AtlasBackdrop(metadata.backdrop, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.66f)) {
            AtlasPosterCard(movie.title, "", "", if (metadata.poster.isDisplayFallback()) movie.posterImage else metadata.poster,
                focused = false, progressLabel = null, showText = false, artworkPadding = 0.dp, dimArtwork = false,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).width(148.dp).height(222.dp).testTag("movie_detail_poster"))
        }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to AtlasBackground, .35f to AtlasBackground.copy(alpha = .96f),
            .66f to AtlasBackground.copy(alpha = .72f), 1f to AtlasBackground.copy(alpha = .08f))))
        Column(Modifier.fillMaxWidth(.66f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.testTag(if (fromSaved) "movie_detail_saved_info" else "movie_detail_online_info"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(displayTitle(movie.title), color = AtlasText, style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("movie_detail_title"))
                    refreshAction()
                }
                val line = listOfNotNull(cacheStatus, metadata.releaseYear ?: releaseYearLabel(movie.title), metadata.genre,
                    com.atlas.atlastv.core.media.MediaRuntime.labelMinutes(metadata.durationMinutes)).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("movie_detail_year"))
                metadata.synopsis?.let { Text(it, color = AtlasText.copy(alpha = .88f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).testTag("movie_detail_synopsis")) }
                resumeLabel?.let { Text(it, color = AtlasMutedText, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, modifier = Modifier.testTag("movie_detail_resume_label")) }
            }
            actions()
        }
    }
}

package com.atlas.atlastv.ui.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.playlist.debug.DebugPlayableLiveStream
import com.atlas.atlastv.playlist.debug.PlaylistDebugCategoryCount
import com.atlas.atlastv.playlist.debug.PlaylistDebugLoadState
import com.atlas.atlastv.playlist.debug.PlaylistDebugSummary
import com.atlas.atlastv.playlist.debug.XtreamDebugLoadState
import com.atlas.atlastv.playlist.debug.XtreamDebugSummary
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasBackground
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText
import com.atlas.atlastv.xtream.mapper.CategoryCount

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DebugPlaylistLoaderScreen(
    stateHolder: DebugPlaylistLoaderStateHolder,
    onBack: () -> Unit,
    onPlayableLiveStreamSelected: (DebugPlayableLiveStream) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AtlasBackground)
            .verticalScroll(scrollState)
            .testTag("debug_loader_screen")
            .semantics { contentDescription = "debug_loader_screen" }
            .padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            DebugActionButton(text = "Back", onClick = onBack, width = 112)
            Text(
                text = "Debug Playlist Loader",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = "Xtream API debug loader",
            color = AtlasMutedText,
            style = MaterialTheme.typography.titleMedium,
        )

        if (stateHolder.hasLocalXtreamConfig) {
            DebugActionButton(
                text = "Load Local Xtream Catalog",
                onClick = stateHolder::loadLocalXtreamCatalog,
                width = 320,
            )
            Text(
                text = "Local Xtream config is available. Credentials are hidden.",
                color = AtlasMutedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        XtreamConfigStatusContent(stateHolder)
        XtreamLoadStateContent(
            state = stateHolder.xtreamLoadState,
            onPlayableLiveStreamSelected = onPlayableLiveStreamSelected,
        )

        Text(
            text = "Legacy M3U loader",
            color = AtlasMutedText,
            style = MaterialTheme.typography.titleMedium,
        )

        UrlInputField(
            value = stateHolder.urlInput,
            onValueChanged = stateHolder::onUrlChanged,
        )

        DebugActionButton(
            text = "Load Playlist",
            onClick = stateHolder::loadPlaylist,
            width = 220,
        )

        if (stateHolder.hasLocalDebugPlaylistUrl) {
            DebugActionButton(
                text = "Use Local Debug Playlist",
                onClick = stateHolder::loadLocalDebugPlaylist,
                width = 320,
            )
            Text(
                text = "Local debug URL is configured. The raw URL is hidden.",
                color = AtlasMutedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LoadStateContent(state = stateHolder.loadState)
    }
}

@Composable
private fun XtreamConfigStatusContent(stateHolder: DebugPlaylistLoaderStateHolder) {
    val status = stateHolder.xtreamConfigStatus
    StatusPanel(
        title = "Config status",
        body = listOf(
            "Base URL present: ${status.hasBaseUrl}",
            "Username present: ${status.hasUsername}",
            "Password present: ${status.hasPassword}",
            "Redacted base: ${status.redactedBaseUrl ?: "None"}",
            "Source: ${status.configSource}",
        ).joinToString("\n"),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamLoadStateContent(
    state: XtreamDebugLoadState,
    onPlayableLiveStreamSelected: (DebugPlayableLiveStream) -> Unit,
) {
    when (state) {
        XtreamDebugLoadState.Unavailable -> StatusPanel("No local Xtream config found", "Create local debug secrets to enable Xtream loading.")
        XtreamDebugLoadState.Ready -> StatusPanel("Ready to load local Xtream config", "Use the button above to fetch account info, categories, and catalog counts.")
        is XtreamDebugLoadState.Loading -> StatusPanel(state.stage, "Fetching Xtream API JSON.")
        is XtreamDebugLoadState.Error -> StatusPanel(
            "Error summary",
            listOf(
                "Failure category: ${state.failureCategory}",
                "Stage: ${state.endpointLabel ?: "Unknown"}",
                "HTTP status: ${state.httpStatus?.toString() ?: "None"}",
                "Response bytes: ${state.responseByteCount?.toString() ?: "Unknown"}",
                "Message: ${state.message}",
                "Endpoint: ${state.redactedUrl ?: "Unknown"}",
            ).joinToString("\n"),
        )
        is XtreamDebugLoadState.Success -> XtreamSummaryPanel(
            summary = state.summary,
            playableLiveStreams = state.playableLiveStreams,
            onPlayableLiveStreamSelected = onPlayableLiveStreamSelected,
        )
    }
}

@Composable
private fun UrlInputField(
    value: String,
    onValueChanged: (String) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    BasicTextField(
        value = value,
        onValueChange = onValueChanged,
        singleLine = true,
        textStyle = TextStyle(color = AtlasText, fontSize = 18.sp),
        cursorBrush = SolidColor(AtlasAccent),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(AtlasSurface, shape)
            .border(
                BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AtlasAccent else AtlasSurface),
                shape,
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        decorationBox = { innerTextField ->
            if (value.isBlank()) {
                Text(
                    text = "Paste or enter playlist URL",
                    color = AtlasMutedText,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            innerTextField()
        },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadStateContent(state: PlaylistDebugLoadState) {
    when (state) {
        PlaylistDebugLoadState.Idle -> StatusPanel("Ready", "Enter a playlist URL to download, parse, filter, and index.")
        is PlaylistDebugLoadState.Loading -> StatusPanel("Loading", "Fetching ${state.redactedUrl}")
        is PlaylistDebugLoadState.Error -> StatusPanel("Error", "${state.message}\n${state.redactedUrl}")
        is PlaylistDebugLoadState.Success -> SummaryPanel(state.summary)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusPanel(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = title, color = AtlasText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(text = body, color = AtlasMutedText, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SummaryPanel(summary: PlaylistDebugSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = "Summary", color = AtlasText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(text = summary.redactedUrl, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
        SummaryRow("Downloaded chars", summary.downloadedCharacterCount.toString())
        SummaryRow("Downloaded bytes", summary.downloadedByteCount?.toString() ?: "Unknown")
        SummaryRow("Parsed item count", summary.parsedItemCount.toString())
        SummaryRow("Parse warning count", summary.parseWarningCount.toString())
        SummaryRow("Kept item count", summary.keptItemCount.toString())
        SummaryRow("Excluded item count", summary.excludedItemCount.toString())
        SummaryRow("Live count", summary.liveCount.toString())
        SummaryRow("Movie count", summary.movieCount.toString())
        SummaryRow("Series count", summary.seriesCount.toString())
        SummaryRow("Unknown count", summary.unknownCount.toString())
        CategoryCounts("Top kept categories by count", summary.topKeptCategories)
        CategoryCounts("Top excluded categories by count", summary.topExcludedCategories)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamSummaryPanel(
    summary: XtreamDebugSummary,
    playableLiveStreams: List<DebugPlayableLiveStream>,
    onPlayableLiveStreamSelected: (DebugPlayableLiveStream) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(text = "Success summary", color = AtlasText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        SummaryRow("Auth", summary.auth?.toString() ?: "Unknown")
        SummaryRow("Status", summary.status ?: "Unknown")
        SummaryRow("Message", summary.message ?: "None")
        SummaryRow("Server protocol", summary.serverProtocol ?: "Unknown")
        SummaryRow("Server port", summary.serverPort ?: "Unknown")
        SummaryRow("Live categories", summary.liveCategoryCount.toString())
        SummaryRow("Live streams", summary.liveStreamCount.toString())
        SummaryRow("VOD categories", summary.vodCategoryCount.toString())
        SummaryRow("VOD streams", summary.vodStreamCount.toString())
        SummaryRow("Series categories", summary.seriesCategoryCount.toString())
        SummaryRow("Series count", summary.seriesCount.toString())
        SummaryRow("Kept live streams", summary.keptLiveStreamCount.toString())
        SummaryRow("Kept VOD streams", summary.keptVodStreamCount.toString())
        SummaryRow("Kept series", summary.keptSeriesCount.toString())
        SummaryRow("Excluded categories", summary.excludedCategoryCount.toString())
        SummaryRow("Excluded items", summary.excludedItemCount.toString())
        XtreamCategoryCounts("Top kept categories", summary.topKeptCategories)
        XtreamCategoryCounts("Top excluded categories", summary.topExcludedCategories)
        PlayableLiveStreams(
            streams = playableLiveStreams,
            onPlayableLiveStreamSelected = onPlayableLiveStreamSelected,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayableLiveStreams(
    streams: List<DebugPlayableLiveStream>,
    onPlayableLiveStreamSelected: (DebugPlayableLiveStream) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Playable live smoke test",
            color = AtlasText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (streams.isEmpty()) {
            Text(
                text = "No playable live streams with stream IDs were found.",
                color = AtlasMutedText,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            streams.forEach { stream ->
                PlayableLiveStreamRow(
                    stream = stream,
                    onClick = { onPlayableLiveStreamSelected(stream) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayableLiveStreamRow(
    stream: DebugPlayableLiveStream,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFocused) AtlasSurfaceFocused else AtlasBackground, shape)
            .border(BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = stream.name, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text = stream.categoryName, color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
        Text(text = stream.redactedStreamUrl, color = AtlasMutedText, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamCategoryCounts(title: String, counts: List<CategoryCount>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (counts.isEmpty()) {
            Text(text = "None", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
        } else {
            counts.forEach { count ->
                SummaryRow(count.category, count.count.toString())
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = AtlasMutedText, style = MaterialTheme.typography.bodyLarge)
        Text(text = value, color = AtlasText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryCounts(title: String, counts: List<PlaylistDebugCategoryCount>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (counts.isEmpty()) {
            Text(text = "None", color = AtlasMutedText, style = MaterialTheme.typography.bodyMedium)
        } else {
            counts.forEach { count ->
                SummaryRow(count.category, count.count.toString())
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DebugActionButton(
    text: String,
    onClick: () -> Unit,
    width: Int,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .width(width.dp)
            .height(54.dp)
            .background(if (isFocused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (isFocused) 3.dp else 1.dp, if (isFocused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = AtlasText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

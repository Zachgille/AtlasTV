package com.atlas.atlastv.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasMutedText
import com.atlas.atlastv.ui.theme.AtlasSurface
import com.atlas.atlastv.ui.theme.AtlasSurfaceFocused
import com.atlas.atlastv.ui.theme.AtlasText

@Composable
fun BrowseSearchOverlay(
    query: String,
    resultCount: Int,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onQueryCommit: (String) -> Unit = {},
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overlayTag: String,
    fieldTag: String,
    clearTag: String,
    countTag: String,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AtlasSurface, RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag(overlayTag)
            .semantics { contentDescription = overlayTag },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .background(AtlasSurfaceFocused, RoundedCornerShape(8.dp))
                .border(BorderStroke(2.dp, AtlasAccent), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = { value ->
                    onQueryChange(value)
                    onQueryCommit(value)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = AtlasText,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            onBack()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag(fieldTag)
                    .semantics { contentDescription = fieldTag },
                decorationBox = { innerTextField ->
                    if (query.isBlank()) {
                        Text("Search titles or categories", color = AtlasMutedText, style = MaterialTheme.typography.titleMedium)
                    }
                    innerTextField()
                },
            )
        }
        Text(
            "$resultCount results",
            color = AtlasMutedText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(countTag).semantics { contentDescription = countTag },
        )
        BrowseSearchButton("Clear", onClear, clearTag)
    }
}

@Composable
fun BrowseSearchButton(text: String, onClick: () -> Unit, testTag: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(118.dp)
            .height(54.dp)
            .background(if (focused) AtlasSurfaceFocused else AtlasSurface, shape)
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) AtlasAccent else AtlasSurface), shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = testTag },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AtlasText, style = MaterialTheme.typography.titleMedium)
    }
}

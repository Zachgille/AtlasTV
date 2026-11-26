package com.atlas.atlastv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.atlas.atlastv.ui.theme.AtlasAccent
import com.atlas.atlastv.ui.theme.AtlasMutedText

/** A non-focusable, determinate checkpoint indicator. Unknown duration has no bar. */
@Composable
fun AtlasCardProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val value = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
        .background(AtlasMutedText.copy(alpha = 0.24f)).testTag("card_progress_bar")
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }) {
        if (value > 0f) Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(AtlasAccent).testTag("card_progress_fill"))
    }
}

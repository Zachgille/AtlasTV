package com.atlas.atlastv.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle

/** Fixed-width poster labels retain TV styling/drawing without an unused layout callback. */
@Composable
fun AtlasPosterLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
) {
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    BasicText(
        text = text,
        // Preserve the TV Material drawing layer; only select the simple String layout path.
        // The simple node's lazy layout semantics need the actual bounded width, not a wider
        // wrap-content parent constraint. Poster footers already have a fixed width.
        modifier = modifier.fillMaxWidth().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        style = style.merge(color = textColor, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight),
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
    )
}

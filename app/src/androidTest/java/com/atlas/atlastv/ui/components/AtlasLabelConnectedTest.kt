package com.atlas.atlastv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.theme.LumaireTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compares actual glyph pixels before querying optional layout semantics; no account/provider. */
class AtlasLabelConnectedTest {
    @get:Rule val ui = createComposeRule()
    private data class Sample(val text: String, val max: Int = 2, val min: Int = 1,
        val size: TextUnit = TextUnit.Unspecified, val height: TextUnit = TextUnit.Unspecified,
        val weight: FontWeight? = null, val color: Color = Color.Unspecified,
        val style: TextStyle? = null, val wrap: Boolean = true)

    @Composable private fun pair(sample: Sample, index: Int, includeWrapReference: Boolean = false) {
        Row {
            for (kind in if (includeWrapReference) listOf("wrap", "old", "new") else listOf("old", "new")) {
                val tag = "${kind}_$index"
                Box(Modifier.width(300.dp).height(64.dp).testTag("panel_$tag")) {
                    val modifier = Modifier.testTag(tag)
                    val style = sample.style ?: LocalTextStyle.current
                    if (kind != "new") Text(sample.text, modifier = if (kind == "wrap") modifier else modifier.fillMaxWidth(), color = sample.color,
                        fontSize = sample.size, lineHeight = sample.height, fontWeight = sample.weight,
                        style = style, overflow = TextOverflow.Ellipsis, maxLines = sample.max,
                        minLines = sample.min, softWrap = sample.wrap)
                    else AtlasPosterLabel(sample.text, modifier = modifier, color = sample.color,
                        fontSize = sample.size, lineHeight = sample.height, fontWeight = sample.weight,
                        style = style, overflow = TextOverflow.Ellipsis, maxLines = sample.max,
                        minLines = sample.min, softWrap = sample.wrap)
                }
            }
        }
    }
    private fun pixelsMatch(count: Int) {
        ui.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            repeat(count) { index ->
                fun pixels(tag: String): IntArray {
                    val bounds = ui.onNodeWithTag("panel_${tag}_$index").fetchSemanticsNode().boundsInRoot
                    val w = bounds.width.toInt(); val h = bounds.height.toInt()
                    return IntArray(w * h).also { image.getPixels(it, 0, w, bounds.left.toInt(), bounds.top.toInt(), w, h) }
                }
                val before = pixels("old"); val after = pixels("new")
                assertArrayEquals("Actual glyphs differ for sample $index", before, after)
            }
        } finally { image.recycle() }
    }
    private fun originalVisiblePixelsMatch(count: Int) {
        ui.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            repeat(count) { index ->
                val panel = ui.onNodeWithTag("panel_wrap_$index").fetchSemanticsNode().boundsInRoot
                val boundedPanel = ui.onNodeWithTag("panel_new_$index").fetchSemanticsNode().boundsInRoot
                val text = ui.onNodeWithTag("wrap_$index").fetchSemanticsNode().boundsInRoot
                val w = panel.width.toInt(); val h = panel.height.toInt()
                assertEquals(panel.size, boundedPanel.size)
                val before = IntArray(w * h).also { image.getPixels(it, 0, w, panel.left.toInt(), panel.top.toInt(), w, h) }
                val after = IntArray(w * h).also { image.getPixels(it, 0, w, boundedPanel.left.toInt(), boundedPanel.top.toInt(), w, h) }
                if (index != 3) {
                    assertArrayEquals("Original glyphs moved for sample $index", before, after)
                } else {
                    // TV Text's wrap-content Offscreen layer clips the last t's right stroke.
                    // The bounded TV reference above must match the new label exactly. Here
                    // every old visible pixel remains exact; only that clipped edge gains ink.
                    val clippedEdge = (text.right - panel.left).toInt()
                    val differences = before.indices.filter { before[it] != after[it] }
                    assertTrue("Expected the previously clipped t stroke", differences.isNotEmpty())
                    differences.forEach { pixel ->
                        assertEquals("Glyph position changed", clippedEdge, pixel % w)
                        assertEquals("An original ink pixel changed", android.graphics.Color.BLACK, before[pixel])
                        assertNotEquals("The clipped stroke was not revealed", android.graphics.Color.BLACK, after[pixel])
                    }
                }
            }
        } finally { image.recycle() }
    }
    private fun layoutsMatch(samples: List<Sample>) {
        samples.forEachIndexed { index, sample ->
            fun layout(tag: String): TextLayoutResult {
                val result = mutableListOf<TextLayoutResult>()
                ui.onNodeWithTag("${tag}_$index").assertTextEquals(sample.text)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                return result.single()
            }
            val old = layout("old"); val new = layout("new")
            assertEquals(old.size, new.size)
            assertEquals(old.layoutInput.style, new.layoutInput.style)
            assertEquals(old.lineCount, new.lineCount)
            assertEquals(old.hasVisualOverflow, new.hasVisualOverflow)
            repeat(old.lineCount) { line ->
                assertEquals(old.getLineBottom(line), new.getLineBottom(line), .01f)
                assertEquals(old.isLineEllipsized(line), new.isLineEllipsized(line))
            }
        }
    }
    @Test fun plainLabelsRetainPixelsTypographyEllipsisAndAccessibleText() {
        val samples = listOf(
            Sample("Continue Watching", max = 1, weight = FontWeight.SemiBold),
            Sample("The Complete Adventures of a Very Long Movie Title With Many More Words And A Subtitle"),
            Sample("S2 E10 · 6m left", max = 1, size = 12.sp, height = 16.sp, color = Color.LightGray),
            Sample("Café — 用户 🔐 مرحبا\nA second line", style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = Color.Cyan)),
            Sample("", min = 2),
            Sample("A very long line that must ellipsize without wrapping around to a second line", max = 1, wrap = false))
        ui.setContent { LumaireTheme { CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                samples.forEachIndexed { index, sample -> pair(sample, index) }
            }
        } } }
        pixelsMatch(samples.size)
        layoutsMatch(samples)
    }
    @Test fun inheritedColorAndExplicitStyleOverridesRemainCorrectAfterUpdates() {
        var text by mutableStateOf("Saved")
        var contentColor by mutableStateOf(Color.Yellow)
        val explicit = TextStyle(color = Color.Cyan, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Light)
        fun samples() = listOf(Sample(text), Sample(text, style = explicit),
            Sample(text, style = explicit, color = Color.White, weight = FontWeight.Bold, size = 18.sp, height = 24.sp))
        ui.setContent { LumaireTheme { CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontSize = 16.sp, lineHeight = 22.sp), LocalContentColor provides contentColor) {
            Column(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                samples().forEachIndexed { index, sample -> pair(sample, index) }
            }
        } } }
        pixelsMatch(3)
        ui.runOnIdle { text = "Not saved — retry"; contentColor = Color.Green }
        pixelsMatch(3)
        layoutsMatch(samples())
    }
    @Test fun fixedFooterKeepsOriginalGlyphsAndRevealsClippedRightEdge() {
        val samples = listOf(Sample("Blindspot", max = 1),
            Sample("The Complete Adventures of a Very Long Movie Title With Many More Words"),
            Sample("S2 E10", max = 1, size = 12.sp, height = 16.sp),
            Sample("6m left", max = 1, size = 12.sp, height = 16.sp),
            Sample("Café — the final voyage 🛰️", max = 1),
            Sample("At 2:00", max = 1, size = 12.sp, height = 16.sp))
        ui.setContent { LumaireTheme { CompositionLocalProvider(
            LocalTextStyle provides TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
            LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxSize().background(Color.Black).padding(20.dp)) {
                samples.forEachIndexed { index, sample -> pair(sample, index, includeWrapReference = true) }
            }
        } } }
        // Compare exactly with bounded TV Text, then independently verify the original
        // wrap-content glyphs. Only the old layer's clipped right-edge stroke may differ.
        pixelsMatch(samples.size)
        originalVisiblePixelsMatch(samples.size)
    }
}

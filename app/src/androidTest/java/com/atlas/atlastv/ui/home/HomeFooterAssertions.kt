package com.atlas.atlastv.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

/** Check actual transformed geometry and painted lower border, without clipped semantic bounds. */
internal fun paintedBounds(node: SemanticsNodeInteraction): Rect {
    val c = node.fetchSemanticsNode().layoutInfo.coordinates
    val a = c.localToRoot(Offset.Zero)
    val b = c.localToRoot(Offset(c.size.width.toFloat(), c.size.height.toFloat()))
    return Rect(a, b)
}
internal fun assertHomeFooterVisible(poster: SemanticsNodeInteraction, viewport: SemanticsNodeInteraction,
    footer: SemanticsNodeInteraction, title: SemanticsNodeInteraction) {
    val card = paintedBounds(poster)
    val view = paintedBounds(viewport)
    val textArea = paintedBounds(footer)
    assertTrue("Whole focused poster inside viewport: $card in $view", card.top >= view.top - 1 && card.bottom <= view.bottom + 1 && card.left >= view.left - 1 && card.right <= view.right + 1)
    assertTrue("Whole footer inside poster", textArea.top >= card.top && textArea.bottom <= card.bottom + 1)
    val layouts = mutableListOf<TextLayoutResult>()
    title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    val layout = layouts.single()
    // Horizontal/max-line ellipsis is intentional; the displayed lines must fit vertically.
    val titleArea = paintedBounds(title)
    assertTrue("Title stays inside footer", titleArea.top >= textArea.top - 1 && titleArea.bottom <= textArea.bottom + 1)
    assertTrue("Last title line is complete", layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1)
    val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    try {
        val pixel = image.getPixel(card.center.x.toInt(), (card.bottom - 3).toInt())
        assertTrue("Actual complete blue bottom focus border", android.graphics.Color.blue(pixel) > 150 && android.graphics.Color.blue(pixel) > android.graphics.Color.red(pixel) * 1.5)
    } finally { image.recycle() }
}

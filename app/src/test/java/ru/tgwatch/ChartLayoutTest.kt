package ru.tgwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartLayoutTest {
    private val density = 2.75f
    // 11 sp label with the default typeface's line spacing (about 1.17 em) plus 8 dp padding.
    private fun labelHeight(fontScale: Float) = 11f * fontScale * density * 1.172f + 8f * density

    @Test fun defaultFontScaleKeepsTheLayoutMinimumHeight() {
        val minHeight = (120 * density).toInt()
        assertEquals(minHeight, ChartLayout.height(labelHeight(1f), 76f * density, minHeight))
        assertEquals(minHeight, ChartLayout.height(labelHeight(0.85f), 76f * density, minHeight))
    }

    @Test fun largeFontScaleGrowsTheViewAndKeepsThePlotHeight() {
        val minHeight = (120 * density).toInt()
        val label = labelHeight(2f)
        val height = ChartLayout.height(label, 76f * density, minHeight)
        assertTrue(height > minHeight)
        assertTrue("Bars keep at least the minimum plot height", height - 2f * label >= 76f * density)
    }

    @Test fun startLabelIsDroppedOnlyWhenItWouldOverlap() {
        assertTrue(ChartLayout.bothLabelsFit(100f, 150f, 20f, 270f))
        assertFalse(ChartLayout.bothLabelsFit(100f, 150f, 20f, 269f))
    }
}

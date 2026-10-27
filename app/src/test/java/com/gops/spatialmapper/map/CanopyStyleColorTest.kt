package com.gops.spatialmapper.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.gops.spatialmapper.data.TreeClass
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Unit tests for tree class color mapping in [CanopyStyle].
 *
 * - Exhaustiveness: all [TreeClass] values have a color
 * - Color correctness: colors match the specified palette
 * - Dashing logic: only UNCLASSIFIED has a dashed stroke
 */
class CanopyStyleColorTest {

    @Test
    fun fillColorIsExhaustiveForAllTreeClasses() {
        TreeClass.values().forEach { treeClass ->
            val color = canopyFillColor(treeClass)
            // Should not throw, and should return a valid Color
            assertTrue("Alpha should be in [0, 1]", color.alpha >= 0f && color.alpha <= 1f)
        }
    }

    @Test
    fun strokeColorIsExhaustiveForAllTreeClasses() {
        TreeClass.values().forEach { treeClass ->
            val color = canopyStrokeColor(treeClass)
            // Should not throw, and should return a valid Color
            assertTrue("Alpha should be in [0, 1]", color.alpha >= 0f && color.alpha <= 1f)
        }
    }

    @Test
    fun fillColorsMatchPalette() {
        val expected = mapOf(
            TreeClass.PALM to Color(0x66FF2D95),
            TreeClass.BROADLEAF_EVERGREEN to Color(0x662962FF),
            TreeClass.BROADLEAF_DECIDUOUS to Color(0x66FF6D00),
            TreeClass.BROADLEAF_UNCONFIRMED to Color(0x6600E5FF),
            TreeClass.BAMBOO to Color(0x6676FF03),
            TreeClass.OTHER to Color(0x66AA00FF),
            TreeClass.UNCLASSIFIED to Color(0x33888888)
        )
        expected.forEach { (treeClass, expectedColor) ->
            val actual = canopyFillColor(treeClass)
            assertEquals("Fill color for $treeClass should match palette", expectedColor.toArgb(), actual.toArgb())
        }
    }

    @Test
    fun strokeColorsMatchPalette() {
        val expected = mapOf(
            TreeClass.PALM to Color(0xFFFF2D95),
            TreeClass.BROADLEAF_EVERGREEN to Color(0xFF2962FF),
            TreeClass.BROADLEAF_DECIDUOUS to Color(0xFFFF6D00),
            TreeClass.BROADLEAF_UNCONFIRMED to Color(0xFF00E5FF),
            TreeClass.BAMBOO to Color(0xFF76FF03),
            TreeClass.OTHER to Color(0xFFAA00FF),
            TreeClass.UNCLASSIFIED to Color(0xFF666666)
        )
        expected.forEach { (treeClass, expectedColor) ->
            val actual = canopyStrokeColor(treeClass)
            assertEquals("Stroke color for $treeClass should match palette", expectedColor.toArgb(), actual.toArgb())
        }
    }

    @Test
    fun onlyUnclassifiedIsDashed() {
        TreeClass.values().forEach { treeClass ->
            val isDashed = canopyStrokeDashed(treeClass)
            if (treeClass == TreeClass.UNCLASSIFIED) {
                assertTrue("UNCLASSIFIED stroke should be dashed", isDashed)
            } else {
                assertFalse("$treeClass stroke should not be dashed", isDashed)
            }
        }
    }
}

package com.gops.spatialmapper.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the marks/raw-map gating in [globalMapLayers] and [reviewMapLayers]. */
class MapMarksTest {

    private val allDrawn = GlobalMapLayers(
        canopies = true,
        clusterOutlines = true,
        removedTrees = true,
        surveyTrack = true,
        classLegend = true,
        scoutPosition = true,
        toggleButton = true
    )

    @Test
    fun `marks shown draws every overlay group and the toggle`() {
        assertEquals(allDrawn, globalMapLayers(marksShown = true, markingMode = false))
    }

    @Test
    fun `raw map hides every overlay group but keeps the toggle`() {
        val layers = globalMapLayers(marksShown = false, markingMode = false)
        assertEquals(
            GlobalMapLayers(
                canopies = false,
                clusterOutlines = false,
                removedTrees = false,
                surveyTrack = false,
                classLegend = false,
                scoutPosition = false,
                toggleButton = true
            ),
            layers
        )
    }

    @Test
    fun `targeting forces every overlay group on even when marks were hidden`() {
        // The operator must see the trees around the one they are marking as removed.
        val layers = globalMapLayers(marksShown = false, markingMode = true)
        assertEquals(allDrawn.copy(toggleButton = false), layers)
    }

    @Test
    fun `targeting hides the toggle whatever the marks state`() {
        assertFalse(globalMapLayers(marksShown = true, markingMode = true).toggleButton)
        assertFalse(globalMapLayers(marksShown = false, markingMode = true).toggleButton)
    }

    @Test
    fun `targeting with marks shown draws everything`() {
        assertEquals(allDrawn.copy(toggleButton = false), globalMapLayers(marksShown = true, markingMode = true))
    }

    @Test
    fun `review marks shown draws canopy, outline, origin and handles`() {
        val layers = reviewMapLayers(marksShown = true)
        assertEquals(
            ReviewMapLayers(canopies = true, clusterOutline = true, originPin = true, dragHandles = true),
            layers
        )
    }

    @Test
    fun `review raw map hides everything including the drag handles`() {
        val layers = reviewMapLayers(marksShown = false)
        assertEquals(
            ReviewMapLayers(canopies = false, clusterOutline = false, originPin = false, dragHandles = false),
            layers
        )
    }

    @Test
    fun `review and global agree on what hiding means`() {
        // Same toggle, same meaning on both screens: hidden draws no marks, shown draws all of them.
        for (shown in listOf(true, false)) {
            val global = globalMapLayers(marksShown = shown, markingMode = false)
            val review = reviewMapLayers(marksShown = shown)
            assertEquals(shown, global.canopies)
            assertEquals(shown, review.canopies)
            assertEquals(shown, global.clusterOutlines)
            assertEquals(shown, review.clusterOutline)
        }
        assertTrue(globalMapLayers(marksShown = false, markingMode = false).toggleButton)
    }
}

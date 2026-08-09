package com.gops.spatialmapper.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the canopy shape generator's noise model.
 *
 * Only [canopyRadiiMeters] is covered here — [generateCanopyPolygon] just projects these radii with
 * SphericalUtil, which lives in an Android AAR and so isn't reachable from a JVM unit test.
 */
class CanopyRadiiTest {

    @Test
    fun `mean radius equals the measured base radius`() {
        // This is the property that matters most now that the radius is a MEASUREMENT: the organic
        // perturbation must not bias the size the operator framed.
        val radii = canopyRadiiMeters(baseRadiusMeters = 4.0, seed = 1234L)
        assertEquals(4.0, radii.average(), 1e-9)
    }

    @Test
    fun `mean radius holds across seeds and vertex counts`() {
        for (seed in 0L until 25L) {
            for (vertexCount in listOf(3, 8, 24, 64)) {
                val radii = canopyRadiiMeters(7.5, vertexCount = vertexCount, seed = seed)
                assertEquals(
                    "seed=$seed vertexCount=$vertexCount",
                    7.5,
                    radii.average(),
                    1e-9
                )
            }
        }
    }

    @Test
    fun `produces the requested number of vertices`() {
        assertEquals(CANOPY_VERTEX_COUNT, canopyRadiiMeters(3.0).size)
        assertEquals(11, canopyRadiiMeters(3.0, vertexCount = 11).size)
    }

    @Test
    fun `same seed gives the same shape`() {
        // Stability matters: dragging the radius handle must scale the crown, not reshuffle it.
        assertEquals(canopyRadiiMeters(5.0, seed = 42L), canopyRadiiMeters(5.0, seed = 42L))
    }

    @Test
    fun `different seeds give different shapes`() {
        assertNotEquals(canopyRadiiMeters(5.0, seed = 1L), canopyRadiiMeters(5.0, seed = 2L))
    }

    @Test
    fun `resizing scales every vertex proportionally`() {
        val small = canopyRadiiMeters(2.0, seed = 7L)
        val large = canopyRadiiMeters(6.0, seed = 7L)
        small.indices.forEach { i ->
            assertEquals("vertex $i", 3.0, large[i] / small[i], 1e-9)
        }
    }

    @Test
    fun `radii stay within the jitter envelope`() {
        // Smoothing pulls values inward, so the raw jitter fraction is a safe outer bound.
        val base = 10.0
        val jitter = 0.18
        val radii = canopyRadiiMeters(base, jitterFraction = jitter, seed = 99L)
        radii.forEach { radius ->
            assertTrue("radius $radius out of envelope", radius in base * (1 - jitter)..base * (1 + jitter))
        }
    }

    @Test
    fun `zero jitter gives a perfect circle`() {
        val radii = canopyRadiiMeters(3.0, jitterFraction = 0.0, seed = 5L)
        radii.forEach { assertEquals(3.0, it, 1e-12) }
    }

    @Test
    fun `noise is not degenerate`() {
        // Guards against a smoothing bug that flattens the shape back into a circle.
        val radii = canopyRadiiMeters(10.0, seed = 3L)
        assertTrue("expected some variation", radii.max() - radii.min() > 0.05)
    }

    @Test
    fun `rejects impossible inputs`() {
        assertTrue(
            runCatching { canopyRadiiMeters(1.0, vertexCount = 2) }
                .exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching { canopyRadiiMeters(0.0) }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching { canopyRadiiMeters(-1.0) }.exceptionOrNull() is IllegalArgumentException
        )
    }

    @Test
    fun `diameter formatting switches precision at ten meters`() {
        assertEquals("4.3 m", formatCanopyDiameter(4.25))
        assertEquals("9.9 m", formatCanopyDiameter(9.94))
        assertEquals("12 m", formatCanopyDiameter(12.4))
    }
}

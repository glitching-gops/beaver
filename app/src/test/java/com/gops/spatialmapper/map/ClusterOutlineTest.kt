package com.gops.spatialmapper.map

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.google.maps.android.SphericalUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cluster outline geometry, and the per-tree shape seeds. */
class ClusterOutlineTest {

    private val origin = LatLng(12.9716, 77.5946)

    private fun crownAt(bearing: Double, distance: Double, radius: Double, seed: Long) =
        generateCanopyPolygon(SphericalUtil.computeOffset(origin, distance, bearing), radius, seed = seed)

    private val crowns = listOf(
        crownAt(0.0, 0.0, 3.0, 1L),
        crownAt(60.0, 9.0, 2.5, 2L),
        crownAt(200.0, 7.0, 4.0, 3L)
    )

    @Test
    fun `the outline encloses every vertex of every crown with room to spare`() {
        val outline = paddedClusterOutline(crowns)
        crowns.flatten().forEach { vertex ->
            assertTrue("vertex $vertex outside the outline", PolyUtil.containsLocation(vertex, outline, false))
            // And not merely touching it: every vertex stands off the outline by about the padding.
            val gap = PolyUtil.locationIndexOnEdgeOrPath(vertex, outline, true, false,
                CLUSTER_OUTLINE_PADDING_METERS * 0.8)
            assertEquals("vertex $vertex is closer to the outline than the padding", -1, gap)
        }
    }

    @Test
    fun `padding is metres on the ground`() {
        // One square crown 10 m on a side, padded 1.5 m: the outline's east-west extent is ~13 m.
        val square = listOf(
            SphericalUtil.computeOffset(origin, 5.0 * Math.sqrt(2.0), 45.0),
            SphericalUtil.computeOffset(origin, 5.0 * Math.sqrt(2.0), 135.0),
            SphericalUtil.computeOffset(origin, 5.0 * Math.sqrt(2.0), 225.0),
            SphericalUtil.computeOffset(origin, 5.0 * Math.sqrt(2.0), 315.0)
        )
        val outline = paddedClusterOutline(listOf(square))
        val west = outline.minOf { it.longitude }
        val east = outline.maxOf { it.longitude }
        val width = SphericalUtil.computeDistanceBetween(LatLng(origin.latitude, west), LatLng(origin.latitude, east))
        assertEquals(10.0 + 2 * CLUSTER_OUTLINE_PADDING_METERS, width, 0.1)
    }

    @Test
    fun `only an actual cluster gets an outline, and nothing gives an empty one`() {
        assertFalse(needsClusterOutline(1))
        assertTrue(needsClusterOutline(2))
        assertTrue(paddedClusterOutline(emptyList()).isEmpty())
    }

    @Test
    fun `the hull keeps the corners and drops interior and collinear points`() {
        val hull = convexHull(listOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 0.0 to 4.0, 2.0 to 2.0, 2.0 to 0.0))
        assertEquals(setOf(0.0 to 0.0, 4.0 to 0.0, 4.0 to 4.0, 0.0 to 4.0), hull.toSet())
        assertEquals(4, hull.size)
    }

    // --- Seeds -----------------------------------------------------------------------------------

    @Test
    fun `the centre tree keeps the seed every pre-cluster capture used`() {
        assertEquals(canopySeedFor(1_700_000_000_000L), clusterTreeSeedFor(1_700_000_000_000L, 1))
    }

    @Test
    fun `members get distinct, reproducible seeds`() {
        val t = 1_700_000_000_000L
        val seeds = (1..10).map { clusterTreeSeedFor(t, it) }
        assertEquals("every tree in a capture has its own shape", 10, seeds.toSet().size)
        assertEquals(seeds, (1..10).map { clusterTreeSeedFor(t, it) })
        assertNotEquals(
            generateCanopyPolygon(origin, 3.0, seed = clusterTreeSeedFor(t, 2)),
            generateCanopyPolygon(origin, 3.0, seed = clusterTreeSeedFor(t, 3))
        )
    }
}

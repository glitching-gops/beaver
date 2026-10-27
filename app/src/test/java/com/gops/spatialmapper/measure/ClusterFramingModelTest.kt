package com.gops.spatialmapper.measure

import com.gops.spatialmapper.capture.AffineTransform2D
import com.gops.spatialmapper.capture.DepthAlignment
import com.gops.spatialmapper.capture.DepthMap
import com.gops.spatialmapper.telemetry.DistanceMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** Adding ellipses, and turning a member ellipse into a distance, a size and a place. */
class ClusterFramingModelTest {

    // --- Placement --------------------------------------------------------------------------------

    @Test
    fun `the cap is ten trees including the centre`() {
        assertEquals(10, MAX_CLUSTER_TREES)
        assertEquals(1, CENTRE_TREE_NUMBER)
    }

    @Test
    fun `a new ellipse is not placed on top of the existing ones and stays grabbable`() {
        val width = 1000f
        val height = 1500f
        val ellipses = mutableListOf(defaultCanopyEllipse(width, height))
        repeat(4) {
            val added = placeNewMemberEllipse(ellipses, width, height)
            ellipses.forEach { existing ->
                assertFalse(
                    "new centre (${added.centerX}, ${added.centerY}) is inside an existing ellipse",
                    existing.contains(added.centerX, added.centerY)
                )
            }
            // Same reachability rule the drag clamping enforces: right and top handles on the photo.
            assertTrue(added.centerX + added.radiusX <= width + 1e-3f)
            assertTrue(added.centerY - added.radiusY >= -1e-3f)
            assertEquals(width * NEW_MEMBER_DIAMETER_FRACTION / 2f, added.radiusX, 1e-3f)
            ellipses += added
        }
    }

    // --- Distances ---------------------------------------------------------------------------------

    // Sensor 640x480, photo 480x640 (rotated 90°), depth 160x120 covering the full image.
    private val alignment = DepthAlignment(
        90, AffineTransform2D(1.0 / 640, 0.0, 0.0, 0.0, 1.0 / 480, 0.0)
    )

    /** Sensor-oriented depth: raw top-left quadrant 10 m, raw top-right 20 m, bottom half no depth. */
    private val depth = DepthMap(160, 120, ShortArray(160 * 120) { i ->
        val x = i % 160
        val y = i / 160
        when {
            y >= 60 -> 0
            x < 80 -> 10_000
            else -> 20_000
        }.toShort()
    })

    private fun inputs(map: DepthMap? = depth, pitch: Double? = -80.0) = MemberDistanceInputs(
        photoWidthPixels = 480,
        photoHeightPixels = 640,
        focalLengthPixels = 500.0,
        depthMap = map,
        depthAlignment = alignment,
        pitchDegrees = pitch,
        cameraHeightMeters = 1.5
    )

    @Test
    fun `a member over valid depth takes the depth image median, from the correct quadrant`() {
        // Upright top-right quadrant = raw top-left = 10 m; upright bottom-right = raw top-right = 20 m.
        val topRight = resolveMemberDistance(PhotoEllipse(360.0, 160.0, 40.0, 40.0), inputs())!!
        assertEquals(10.0, topRight.meters, 1e-6)
        assertEquals(DistanceMethod.ARCORE_DEPTH_IMAGE, topRight.method)
        assertFalse(topRight.isFallback)

        val bottomRight = resolveMemberDistance(PhotoEllipse(360.0, 480.0, 40.0, 40.0), inputs())!!
        assertEquals(20.0, bottomRight.meters, 1e-6)
    }

    @Test
    fun `no valid depth at the ellipse falls back to the ground plane at its bottom edge`() {
        // Upright left half = raw bottom half = no depth.
        val ellipse = PhotoEllipse(120.0, 300.0, 40.0, 60.0)
        val distance = resolveMemberDistance(ellipse, inputs())!!
        assertEquals(DistanceMethod.GEOMETRY_FALLBACK, distance.method)
        assertTrue(distance.isFallback)
        val expected = groundPlaneDistanceAtRowMeters(1.5, -80.0, ellipse.bottomY, 640, 500.0)!!
        assertEquals("measured at the trunk base (bottom edge), not the centre", expected, distance.meters, 1e-9)
    }

    @Test
    fun `a non-depth device always uses the fallback`() {
        // Over the 10 m quadrant, but with no depth map; trunk base 120 px below the axis.
        val distance = resolveMemberDistance(PhotoEllipse(360.0, 400.0, 40.0, 40.0), inputs(map = null))!!
        assertEquals(DistanceMethod.GEOMETRY_FALLBACK, distance.method)
        // ...and with the axis only 10° down, a trunk base well ABOVE the axis is above the horizon.
        assertNull(resolveMemberDistance(PhotoEllipse(360.0, 160.0, 40.0, 40.0), inputs(map = null)))
    }

    @Test
    fun `no depth and no usable ground plane is no distance at all`() {
        // Left half has no depth, and pitch is unknown.
        assertNull(resolveMemberDistance(PhotoEllipse(120.0, 300.0, 40.0, 40.0), inputs(pitch = null)))
    }

    // --- A whole member ------------------------------------------------------------------------------

    @Test
    fun `a member is sized with axis depth, placed with line-of-sight range, and bears right`() {
        // Display == photo pixels (scale 1). Ellipse in the upright top-right quadrant -> 10 m depth.
        val member = measureClusterMember(
            treeNumber = 2,
            displayEllipse = CanopyEllipse(360f, 160f, 50f, 40f),
            displayToImageScale = 1f,
            captureHeadingDegrees = 100.0,
            inputs = inputs()
        )
        assertNotNull(member)
        member!!
        assertEquals(2, member.treeNumber)
        assertEquals(10.0, member.distance.meters, 1e-6)
        // Sizing: 100 px wide x 10 m / 500 px = 2 m, 80 px tall -> 1.6 m.
        assertEquals(2.0, member.canopy.horizontalDiameterMeters, 1e-6)
        assertEquals(1.6, member.canopy.verticalDiameterMeters, 1e-6)
        // Direction: 120 px right of the 240 px centre -> atan(120/500) clockwise of the heading.
        val offAxis = Math.toDegrees(kotlin.math.atan(120.0 / 500.0))
        assertEquals(100.0 + offAxis, member.bearingDegrees, 1e-9)
        // Placement: the ray is longer than the axis depth.
        assertEquals(10.0 / cos(Math.toRadians(offAxis)), member.rangeMeters, 1e-9)
        assertTrue(member.rangeMeters > member.distance.meters)
        // Persisted framing is in photo pixels.
        assertEquals(PhotoEllipse(360.0, 160.0, 50.0, 40.0), member.photoEllipse)
    }

    @Test
    fun `display pixels are scaled into photo pixels before anything is measured`() {
        // The same tree drawn at half size on screen must measure the same.
        val full = measureClusterMember(2, CanopyEllipse(360f, 160f, 50f, 40f), 1f, 100.0, inputs())!!
        val half = measureClusterMember(2, CanopyEllipse(180f, 80f, 25f, 20f), 2f, 100.0, inputs())!!
        assertEquals(full.canopy.diameterMeters, half.canopy.diameterMeters, 1e-6)
        assertEquals(full.bearingDegrees, half.bearingDegrees, 1e-9)
        assertEquals(full.photoEllipse, half.photoEllipse)
    }
}

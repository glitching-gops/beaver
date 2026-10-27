package com.gops.spatialmapper.measure

import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.distance.groundPlaneDistanceMeters
import com.gops.spatialmapper.projection.projectTargetLocation
import com.google.maps.android.SphericalUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Off-centre geometry for cluster members. The DIRECTION tests matter most: this project once
 * shipped a heading that was exactly 180° wrong, and a magnitude-only test would not have caught it.
 */
class ClusterGeometryTest {

    // A 2000 px wide upright photo with a 1000 px focal length: the right edge is atan(1000/1000) =
    // 45° off axis, a round number that makes every expectation below checkable by hand.
    private val width = 2000
    private val focal = 1000.0

    // --- Bearing ---------------------------------------------------------------------------------

    @Test
    fun `a tree right of centre is clockwise of the heading`() {
        val bearing = bearingToPixelDegrees(90.0, pixelX = 2000.0, imageWidthPixels = width, focalLengthPixels = focal)
        assertEquals(135.0, bearing, 1e-9)
        assertTrue("right of centre must be a LARGER bearing", bearing > 90.0)
    }

    @Test
    fun `a tree left of centre is anticlockwise of the heading`() {
        val bearing = bearingToPixelDegrees(90.0, pixelX = 0.0, imageWidthPixels = width, focalLengthPixels = focal)
        assertEquals(45.0, bearing, 1e-9)
        assertTrue("left of centre must be a SMALLER bearing", bearing < 90.0)
    }

    @Test
    fun `a tree at the centre is on the heading itself`() {
        assertEquals(213.4, bearingToPixelDegrees(213.4, 1000.0, width, focal), 1e-9)
    }

    @Test
    fun `bearings wrap through north in both directions`() {
        assertEquals(35.0, bearingToPixelDegrees(350.0, 2000.0, width, focal), 1e-9)
        assertEquals(325.0, bearingToPixelDegrees(10.0, 0.0, width, focal), 1e-9)
    }

    @Test
    fun `a right-of-centre tree projects onto the clockwise side of the camera's line of sight`() {
        // End to end through the real projection: facing due north, a tree right of centre must land
        // EAST of the north line, not west of it (and certainly not south of the camera).
        val origin = LatLng(12.9716, 77.5946)
        val bearing = bearingToPixelDegrees(0.0, 1600.0, width, focal)
        val tree = projectTargetLocation(origin, bearing, 20.0)
        assertTrue("east of the camera", tree.longitude > origin.longitude)
        assertTrue("in front of the camera", tree.latitude > origin.latitude)
    }

    // --- Sizing vs locating ----------------------------------------------------------------------

    @Test
    fun `sizing uses the axis depth as measured`() {
        assertEquals(12.5, sizingDepthMeters(12.5), 0.0)
        // So a 200 px crown at 10 m axis depth is 2 m across through the pinhole formula...
        val diameter = canopyDiameterMeters(200, sizingDepthMeters(10.0).toFloat(), focal.toFloat())!!
        assertEquals(2.0, diameter, 1e-6)
        // ...and NOT the 2.83 m the 45°-off-axis ray length would give.
        val wrong = canopyDiameterMeters(200, lineOfSightRangeMeters(10.0, 45.0).toFloat(), focal.toFloat())!!
        assertNotEquals(diameter, wrong, 0.1)
    }

    @Test
    fun `locating uses the longer line-of-sight range for an off-centre tree`() {
        assertEquals(10.0 * sqrt(2.0), lineOfSightRangeMeters(10.0, 45.0), 1e-9)
        assertEquals(10.0 * sqrt(2.0), lineOfSightRangeMeters(10.0, -45.0), 1e-9)
        assertEquals("on axis the two agree", 10.0, lineOfSightRangeMeters(10.0, 0.0), 1e-12)
    }

    @Test
    fun `range and bearing together put the tree at the right forward and lateral offset`() {
        // A point 10 m ahead (axis depth) and 45° right must sit 10 m ahead and 10 m to the side.
        val origin = LatLng(0.0, 0.0)
        val offAxis = horizontalOffAxisAngleDegrees(2000.0, width, focal)
        val tree = projectTargetLocation(origin, bearingToPixelDegrees(0.0, 2000.0, width, focal),
            lineOfSightRangeMeters(10.0, offAxis))
        val north = SphericalUtil.computeDistanceBetween(origin, LatLng(tree.latitude, 0.0))
        val east = SphericalUtil.computeDistanceBetween(origin, LatLng(0.0, tree.longitude))
        assertEquals(10.0, north, 0.01)
        assertEquals(10.0, east, 0.01)
    }

    // --- Ground-plane fallback at a row ----------------------------------------------------------

    private val height = 2000           // so the centre row is y = 1000

    @Test
    fun `at the centre row it is exactly the centre tree's fallback`() {
        val atRow = groundPlaneDistanceAtRowMeters(1.5, -80.0, 1000.0, height, focal)!!
        val centre = groundPlaneDistanceMeters(1.5f, -80f)!!
        assertEquals(centre.toDouble(), atRow, 1e-4)
        assertEquals(1.5 / tan(Math.toRadians(10.0)), atRow, 1e-9)
    }

    @Test
    fun `a trunk base below centre is nearer, above centre is farther`() {
        val centre = groundPlaneDistanceAtRowMeters(1.5, -80.0, 1000.0, height, focal)!!
        val below = groundPlaneDistanceAtRowMeters(1.5, -80.0, 1200.0, height, focal)!!
        val above = groundPlaneDistanceAtRowMeters(1.5, -80.0, 950.0, height, focal)!!
        assertTrue("below the axis looks more steeply down -> nearer ground", below < centre)
        assertTrue("above the axis looks less steeply down -> farther ground", above > centre)
        val expectedBelow = 1.5 / tan(Math.toRadians(10.0) + atan(200.0 / focal))
        assertEquals(expectedBelow, below, 1e-9)
    }

    @Test
    fun `a trunk base at or above the horizon has no ground-plane distance`() {
        // Axis 10° below the horizon; 500 px above centre is atan(0.5) = 26.6° up -> above horizon.
        assertNull(groundPlaneDistanceAtRowMeters(1.5, -80.0, 500.0, height, focal))
        assertNull("unknown pitch", groundPlaneDistanceAtRowMeters(1.5, null, 1200.0, height, focal))
        assertNull("no camera height", groundPlaneDistanceAtRowMeters(0.0, -80.0, 1200.0, height, focal))
    }
}

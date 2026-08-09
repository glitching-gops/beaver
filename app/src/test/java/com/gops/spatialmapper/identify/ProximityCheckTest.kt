package com.gops.spatialmapper.identify

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The proximity check must warn when it should and, more importantly, stay silent when it can't tell.
 *
 * The brief is explicit that this never blocks: an operator with no GPS fix, or a row saved without an
 * origin, has to be able to identify a plant anyway. Encoding "fine" and "can't tell" as the same null
 * is the thing worth pinning down, because a future refactor that returns 0.0 or Double.NaN for the
 * unknown case would turn a silent skip into a permanent warning banner.
 */
class ProximityCheckTest {

    // A point in Amethi district, UP — arbitrary, but a real Indian-subcontinent latitude so the
    // metres-per-degree the spherical maths produces is representative.
    private val origin = LatLng(26.1445, 81.9812)

    /** A point [meters] due east of [origin], so the expected distance is known exactly. */
    private fun eastOfOrigin(meters: Double): LatLng =
        SphericalUtil.computeOffset(origin, meters, 90.0)

    @Test
    fun `no warning when standing at the recorded origin`() {
        assertNull(proximityWarningDistanceMeters(origin, origin))
    }

    @Test
    fun `no warning comfortably inside the threshold`() {
        assertNull(proximityWarningDistanceMeters(origin, eastOfOrigin(10.0)))
        assertNull(proximityWarningDistanceMeters(origin, eastOfOrigin(49.0)))
    }

    @Test
    fun `warns beyond the threshold and reports the distance`() {
        val warning = proximityWarningDistanceMeters(origin, eastOfOrigin(120.0))
        assertNotNull(warning)
        assertEquals(120.0, warning!!, 1.0)
    }

    @Test
    fun `the boundary itself does not warn`() {
        // Strictly greater-than. Exactly at the threshold is "close enough" — an off-by-one here would
        // flash the banner at operators standing right where the tolerance says they should be.
        assertNull(
            proximityWarningDistanceMeters(
                origin,
                eastOfOrigin(IDENTIFY_PROXIMITY_WARNING_METERS),
                thresholdMeters = IDENTIFY_PROXIMITY_WARNING_METERS
            )
        )
        assertNotNull(
            proximityWarningDistanceMeters(
                origin,
                eastOfOrigin(IDENTIFY_PROXIMITY_WARNING_METERS + 5.0),
                thresholdMeters = IDENTIFY_PROXIMITY_WARNING_METERS
            )
        )
    }

    @Test
    fun `silent when there is no GPS fix`() {
        assertNull(proximityWarningDistanceMeters(origin, null))
    }

    @Test
    fun `silent when the session has no recorded origin`() {
        assertNull(proximityWarningDistanceMeters(null, eastOfOrigin(5000.0)))
    }

    @Test
    fun `silent when neither is known`() {
        assertNull(proximityWarningDistanceMeters(null, null))
    }

    @Test
    fun `the threshold is adjustable without touching the call site`() {
        val hundredMetresAway = eastOfOrigin(100.0)
        assertNull(proximityWarningDistanceMeters(origin, hundredMetresAway, thresholdMeters = 500.0))
        assertNotNull(proximityWarningDistanceMeters(origin, hundredMetresAway, thresholdMeters = 25.0))
    }

    @Test
    fun `warning distance is symmetric`() {
        // Comparing a fix to an origin is the same measurement either way round; a sign or argument
        // swap here would produce a plausible but wrong number in the banner.
        val far = eastOfOrigin(300.0)
        assertEquals(
            proximityWarningDistanceMeters(origin, far)!!,
            proximityWarningDistanceMeters(far, origin)!!,
            1e-6
        )
    }

    // --- Reference point selection ---------------------------------------------------------------

    @Test
    fun `reference point prefers the recorded origin`() {
        val reference = sessionReferencePoint(
            latitude = 26.0,
            longitude = 81.0,
            projectedLatitude = 27.0,
            projectedLongitude = 82.0
        )
        assertEquals(LatLng(26.0, 81.0), reference)
    }

    @Test
    fun `reference point falls back to the projected target`() {
        val reference = sessionReferencePoint(
            latitude = null,
            longitude = null,
            projectedLatitude = 27.0,
            projectedLongitude = 82.0
        )
        assertEquals(LatLng(27.0, 82.0), reference)
    }

    @Test
    fun `reference point is null when a row has neither coordinate pair`() {
        assertNull(sessionReferencePoint(null, null, null, null))
        // A half-populated pair is not a coordinate.
        assertNull(sessionReferencePoint(26.0, null, null, 82.0))
    }
}

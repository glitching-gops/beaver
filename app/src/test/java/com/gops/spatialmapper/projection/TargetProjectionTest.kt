package com.gops.spatialmapper.projection

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the projection math (no Android framework needed — LatLng/SphericalUtil
 * are plain computation). Verifies direction correctness and the round-trip distance invariant that
 * the on-screen sanity readout relies on.
 *
 * REGRESSION NOTE (bearing-inversion bug): these tests pass `headingDegrees` directly and assert
 * that heading 0°→north, 90°→east, 180°→south — i.e. they define the CONTRACT that heading is a
 * clockwise-from-true-north bearing pointing where the CAMERA looks. The bug was upstream in
 * TelemetryTracker.onSensorChanged, which derived that heading from the wrong coordinate frame
 * (raw getOrientation on an upright phone) and produced the 180°-opposite bearing. The fix is the
 * remapCoordinateSystem(AXIS_X, AXIS_Z) call there. These assertions did not encode the old buggy
 * convention (they were always correct for a proper camera-facing heading), so they are unchanged —
 * but if heading ever regresses to the flat-frame azimuth, real captures will invert again even
 * though these pass, so the guard lives at the remap site, not here.
 */
class TargetProjectionTest {

    private val origin = LatLng(12.9716, 77.5946) // Bengaluru

    @Test
    fun northProjection_increasesLatitude_keepsLongitude() {
        val target = projectTargetLocation(origin, headingDegrees = 0.0, distanceMeters = 100.0)
        assertTrue("north should increase latitude", target.latitude > origin.latitude)
        assertEquals("north should not change longitude", origin.longitude, target.longitude, 1e-9)
    }

    @Test
    fun eastProjection_increasesLongitude() {
        val target = projectTargetLocation(origin, headingDegrees = 90.0, distanceMeters = 100.0)
        assertTrue("east should increase longitude", target.longitude > origin.longitude)
    }

    @Test
    fun southProjection_decreasesLatitude() {
        val target = projectTargetLocation(origin, headingDegrees = 180.0, distanceMeters = 100.0)
        assertTrue("south should decrease latitude", target.latitude < origin.latitude)
    }

    @Test
    fun roundTripDistance_matchesInputDistance() {
        val headings = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 359.0)
        val distances = listOf(1.0, 10.0, 100.0, 250.0, 1000.0)
        for (heading in headings) {
            for (distance in distances) {
                val target = projectTargetLocation(origin, heading, distance)
                val roundTrip = roundTripDistanceMeters(origin, target)
                assertEquals(
                    "round-trip distance must equal input (heading=$heading, distance=$distance)",
                    distance,
                    roundTrip,
                    0.01
                )
            }
        }
    }
}

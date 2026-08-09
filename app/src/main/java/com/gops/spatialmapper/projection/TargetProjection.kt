package com.gops.spatialmapper.projection

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil

/**
 * Projects the real-world coordinate of the photographed target: start at [origin] (where the user
 * is standing) and travel [distanceMeters] along compass bearing [headingDegrees].
 *
 * Thin, pure wrapper over [SphericalUtil.computeOffset] — intentionally kept free of any UI/sensor
 * dependencies so it can be unit-tested in isolation.
 *
 * HEADING CONVENTION: computeOffset expects the heading as degrees CLOCKWISE FROM TRUE NORTH
 * (0° = N, 90° = E). That is exactly what Phase 3 produces — the rotation-vector azimuth, normalized
 * to [0, 360) and corrected to true north via GeomagneticField declination. So the value flows
 * straight through with NO conversion. computeOffset's parameter order is (from, distance, heading);
 * distance is in meters.
 */
fun projectTargetLocation(origin: LatLng, headingDegrees: Double, distanceMeters: Double): LatLng =
    SphericalUtil.computeOffset(origin, distanceMeters, headingDegrees)

/**
 * Great-circle distance between two coordinates, in meters.
 *
 * The general-purpose name for what [roundTripDistanceMeters] does — introduced when a second caller
 * appeared (the "are you still near this plant?" check on the identification flow) that has nothing
 * to do with round-tripping a projection. Same one-line wrapper, honest name; [roundTripDistanceMeters]
 * stays as the projection-specific alias so the sanity-check call site still reads as what it is.
 */
fun groundDistanceMeters(from: LatLng, to: LatLng): Double =
    SphericalUtil.computeDistanceBetween(from, to)

/**
 * Round-trip consistency check: the great-circle distance between [origin] and [projected] must
 * equal the distance that was fed into [projectTargetLocation], to within floating-point / spherical
 * rounding. A large discrepancy is a red flag for a units bug or a swapped lat/lon order — both are
 * classic projection mistakes.
 */
fun roundTripDistanceMeters(origin: LatLng, projected: LatLng): Double =
    groundDistanceMeters(origin, projected)

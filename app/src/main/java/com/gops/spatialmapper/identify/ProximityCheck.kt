package com.gops.spatialmapper.identify

import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.projection.groundDistanceMeters

/**
 * How far the operator may drift from a session's recorded origin before the identification screen
 * warns them they might be photographing a different plant.
 *
 * 50 m is chosen to sit well clear of the noise floor and well inside the "different tree" range:
 * a phone GPS fix under tree cover routinely wanders 10–20 m, so a tighter threshold would cry wolf
 * on someone standing at the correct trunk, while a looser one would stay silent across a whole
 * plantation row. Adjust here — it is referenced from exactly one place.
 */
const val IDENTIFY_PROXIMITY_WARNING_METERS = 50.0

/**
 * Distance from [currentLocation] to [sessionOrigin] if it exceeds [thresholdMeters], otherwise null.
 *
 * Null means "no warning" and deliberately covers three different situations: the operator is close
 * enough, there is no GPS fix yet, or the session was saved without an origin. The brief is explicit
 * that this check must never block, so "can't tell" and "fine" are the same answer to the caller —
 * encoding them as one null keeps the UI from inventing a third state it would have to explain.
 *
 * Pure, so the boundary behaviour is unit-testable without a device: the comparison is strictly
 * greater-than, so exactly [thresholdMeters] away does not warn.
 */
fun proximityWarningDistanceMeters(
    sessionOrigin: LatLng?,
    currentLocation: LatLng?,
    thresholdMeters: Double = IDENTIFY_PROXIMITY_WARNING_METERS
): Double? {
    if (sessionOrigin == null || currentLocation == null) return null
    val distance = groundDistanceMeters(currentLocation, sessionOrigin)
    if (!distance.isFinite()) return null
    return if (distance > thresholdMeters) distance else null
}

/**
 * The point a session should be compared against.
 *
 * The recorded ORIGIN — where the operator stood at capture — is the reference, per the brief. Falls
 * back to the projected target when a row has no origin, since a session with a mapped canopy but no
 * origin is still worth checking against something. Null only when the row has neither.
 */
fun sessionReferencePoint(
    latitude: Double?,
    longitude: Double?,
    projectedLatitude: Double?,
    projectedLongitude: Double?
): LatLng? = when {
    latitude != null && longitude != null -> LatLng(latitude, longitude)
    projectedLatitude != null && projectedLongitude != null ->
        LatLng(projectedLatitude, projectedLongitude)
    else -> null
}

package com.gops.spatialmapper.map

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import kotlin.math.roundToInt
import kotlin.random.Random

/** Vertices in a generated canopy outline. Enough to read as organic, few enough to store cheaply. */
const val CANOPY_VERTEX_COUNT = 24

/** How far each vertex may deviate from the base radius, as a fraction of it. */
const val CANOPY_JITTER_FRACTION = 0.18

/** Clamp for the operator's radius-drag handle, in meters. */
const val MIN_CANOPY_RADIUS_METERS = 0.25
const val MAX_CANOPY_RADIUS_METERS = 40.0

/**
 * Per-vertex radii for a noise-perturbed canopy outline, in meters.
 *
 * Pure and deterministic given [seed], which is what makes it unit-testable and what keeps a saved
 * session's outline stable: the same capture always regenerates the same shape, so resizing with the
 * drag handle scales the crown rather than reshuffling it.
 *
 * The noise is a circular moving average of uniform jitter, so neighbouring vertices stay correlated
 * and the result reads as a lobed crown instead of a starburst. The radii are then renormalized so
 * their MEAN is exactly [baseRadiusMeters] — important because the radius is a measurement rather
 * than a freehand guess: the perturbation must not bias the size the operator framed.
 */
fun canopyRadiiMeters(
    baseRadiusMeters: Double,
    vertexCount: Int = CANOPY_VERTEX_COUNT,
    jitterFraction: Double = CANOPY_JITTER_FRACTION,
    seed: Long = 0L
): List<Double> {
    require(vertexCount >= 3) { "a polygon needs at least 3 vertices, got $vertexCount" }
    require(baseRadiusMeters > 0.0) { "baseRadiusMeters must be positive, got $baseRadiusMeters" }
    if (jitterFraction <= 0.0) return List(vertexCount) { baseRadiusMeters }

    val random = Random(seed)
    val raw = DoubleArray(vertexCount) { 1.0 + (random.nextDouble() * 2.0 - 1.0) * jitterFraction }

    // Circular 3-tap smoothing: correlate each vertex with its neighbours (wrapping at the seam).
    val smoothed = DoubleArray(vertexCount) { i ->
        val previous = raw[(i - 1 + vertexCount) % vertexCount]
        val next = raw[(i + 1) % vertexCount]
        (previous + raw[i] * 2.0 + next) / 4.0
    }

    val mean = smoothed.average()
    return smoothed.map { baseRadiusMeters * it / mean }
}

/**
 * Builds a closed, noise-perturbed canopy outline of mean radius [baseRadiusMeters] around [center].
 *
 * Vertices are placed with [SphericalUtil.computeOffset], so the outline is metrically correct on the
 * ground rather than a lat/lon ellipse that would skew with latitude.
 *
 * [baseRadiusMeters] normally comes from the framing screen (half the measured canopy diameter), and
 * afterwards from the operator's radius-drag handle if they nudge it on the map.
 */
fun generateCanopyPolygon(
    center: LatLng,
    baseRadiusMeters: Double,
    vertexCount: Int = CANOPY_VERTEX_COUNT,
    jitterFraction: Double = CANOPY_JITTER_FRACTION,
    seed: Long = 0L
): List<LatLng> {
    val radii = canopyRadiiMeters(baseRadiusMeters, vertexCount, jitterFraction, seed)
    val step = 360.0 / vertexCount
    return radii.mapIndexed { index, radius ->
        SphericalUtil.computeOffset(center, radius, index * step)
    }
}

/**
 * A stable seed for one capture's canopy shape.
 *
 * Derived from the capture timestamp so a given session's outline is reproducible across
 * recompositions and app restarts, while two plants captured seconds apart don't share a silhouette.
 */
fun canopySeedFor(timestampMillis: Long): Long = timestampMillis

/** Formats a canopy diameter for the operator, rounded to the precision the method can support. */
fun formatCanopyDiameter(diameterMeters: Double): String =
    if (diameterMeters >= 10.0) {
        "${diameterMeters.roundToInt()} m"
    } else {
        "%.1f m".format(diameterMeters)
    }

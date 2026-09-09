package com.gops.spatialmapper.scout

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import java.util.Locale

/**
 * The tuning knobs for Scout mode, and the two pure decisions that use them: is this fix good enough
 * to trust, and is it far enough from the last one to be worth drawing.
 *
 * Everything here is pure `(inputs) -> value` with no Android dependency, so the filtering and
 * thinning rules — the parts that decide what the operator actually sees — are unit-testable off
 * device. The service ([ScoutLocationService]) supplies the fixes; [ScoutSession] holds the state;
 * this file holds the judgement.
 */

/**
 * How often to ask for a fix, in milliseconds.
 *
 * 3 s is a walking-pace compromise: at ~1.2 m/s it is a fix roughly every 3.5 m, which is about the
 * [SCOUT_MIN_TRAIL_SPACING_METERS] thinning distance — so while the operator is actually walking,
 * nearly every fix becomes a trail point and the path stays faithful, while standing still costs a
 * fix every 3 s that thinning throws away rather than a continuous jitter cloud.
 *
 * Faster is not better here: PRIORITY_HIGH_ACCURACY at 1 s (what the capture screen uses for its
 * brief, foreground-only reading) would roughly triple the GNSS duty cycle for a session that may
 * run for hours with the screen off.
 */
const val SCOUT_UPDATE_INTERVAL_MILLIS = 3_000L

/**
 * The floor on delivery interval. Fused location will hand us a fix faster than
 * [SCOUT_UPDATE_INTERVAL_MILLIS] if another app on the device has already paid for one; taking those
 * is free, so this lets them through rather than discarding work already done.
 */
const val SCOUT_MIN_UPDATE_INTERVAL_MILLIS = 1_500L

/**
 * Worst horizontal accuracy, in meters, that still counts as a usable fix.
 *
 * Under canopy is exactly where this app is used and exactly where GNSS degrades — multipath off
 * trunks and attenuation through foliage routinely push `Location.accuracy` from ~5 m in the open to
 * 30-50 m under cover. Drawing those would drag the "you are here" marker tens of meters sideways and
 * scribble the trail through trees the operator never walked past.
 *
 * 20 m is deliberately loose rather than tight: too strict and the marker freezes solid under exactly
 * the canopy the officer is working in, which is worse than a slightly noisy one. Tune here.
 */
const val SCOUT_MAX_ACCURACY_METERS = 20f

/**
 * Minimum ground distance, in meters, between consecutive kept trail points.
 *
 * Without this, standing still for five minutes adds ~100 near-identical points that render as a
 * blob and make the line look like the operator wandered in circles. 4 m sits in the middle of the
 * 3-5 m band: comfortably above the jitter of an accepted (sub-20 m) fix cluster, well below the
 * distance between two trees worth distinguishing on the trail.
 */
const val SCOUT_MIN_TRAIL_SPACING_METERS = 4.0

/**
 * Guardrail on the in-memory trail, in points.
 *
 * The trail is never persisted and lives only for the current Scout session, but a service that
 * survives screen-lock can legitimately run all day: at walking pace with 4 m thinning that is on the
 * order of 1,200 points per hour. 10,000 is roughly a full day's walking — past that the list stops
 * growing rather than being silently truncated from the front, because dropping the START of a
 * survey track is a worse failure than stopping the end of it, and the UI shows the point count so
 * the state is visible rather than mysterious.
 */
const val SCOUT_MAX_TRAIL_POINTS = 10_000

/**
 * Is this fix trustworthy enough to move the marker and extend the trail?
 *
 * A null accuracy means the provider declined to say — treated as NOT usable, because an unquantified
 * fix is exactly the kind we are trying to filter out. (In practice fused location always reports
 * one; this is the defensive branch.)
 */
fun isFixUsable(
    accuracyMeters: Float?,
    thresholdMeters: Float = SCOUT_MAX_ACCURACY_METERS
): Boolean {
    if (accuracyMeters == null) return false
    if (accuracyMeters.isNaN() || accuracyMeters <= 0f) return false
    return accuracyMeters <= thresholdMeters
}

/**
 * THE thinning decision: is [candidate] far enough from [lastKept] to be worth recording?
 *
 * Extracted so the live breadcrumb and a recorded survey track share one rule rather than each
 * carrying its own copy of it. That is not tidiness — a persisted track built with a different
 * spacing than the line the operator watched themselves draw would be a quietly different path from
 * the one they thought they were recording, and nothing on screen would ever say so.
 *
 * A null [lastKept] means nothing has been kept yet, and the first point is always taken: there is
 * nothing to be far from.
 *
 * Distance is [SphericalUtil.computeDistanceBetween], i.e. real ground meters, for the same reason
 * the canopy geometry uses it: a degree-based comparison would silently change meaning with latitude.
 */
fun shouldKeepPoint(
    lastKept: LatLng?,
    candidate: LatLng,
    minSpacingMeters: Double = SCOUT_MIN_TRAIL_SPACING_METERS
): Boolean {
    if (lastKept == null) return true
    return SphericalUtil.computeDistanceBetween(lastKept, candidate) >= minSpacingMeters
}

/**
 * Appends [candidate] to [trail] if [shouldKeepPoint] says so, otherwise returns [trail] unchanged.
 *
 * Returning the SAME list instance on rejection is deliberate: the caller holds this in a StateFlow,
 * and an unchanged reference means no emission, no recomposition and no Polyline rebuild while the
 * operator stands still.
 */
fun appendToTrail(
    trail: List<LatLng>,
    candidate: LatLng,
    minSpacingMeters: Double = SCOUT_MIN_TRAIL_SPACING_METERS,
    maxPoints: Int = SCOUT_MAX_TRAIL_POINTS
): List<LatLng> {
    if (trail.size >= maxPoints) return trail
    if (!shouldKeepPoint(trail.lastOrNull(), candidate, minSpacingMeters)) return trail
    return trail + candidate
}

/**
 * Total walked distance along [trail], in meters.
 *
 * Thinning means this UNDER-reports a wandering path slightly (a 4 m detour between two kept points
 * is invisible) and the accuracy filter means it ignores stretches walked under heavy canopy. It is
 * an at-a-glance "how far have I come" readout, not a survey measurement, and is labelled as such.
 */
fun trailLengthMeters(trail: List<LatLng>): Double {
    if (trail.size < 2) return 0.0
    var total = 0.0
    for (i in 0 until trail.size - 1) {
        total += SphericalUtil.computeDistanceBetween(trail[i], trail[i + 1])
    }
    return total
}

/**
 * Formats a walked distance for the readout: meters up to a kilometre, then kilometres.
 *
 * Locale-aware on purpose — this is operator-facing text, so a comma decimal separator is correct
 * where that is what the operator reads. (Contrast the export path, which pins numbers to a dot
 * because a machine consumes them.) Stated explicitly rather than left to the implicit default so
 * the choice is visible.
 */
fun formatTrailLength(meters: Double): String =
    if (meters < 1_000.0) {
        "${meters.toInt()} m"
    } else {
        "%.2f km".format(Locale.getDefault(), meters / 1_000.0)
    }

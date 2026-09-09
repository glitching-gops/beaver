package com.gops.spatialmapper.removed

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.gops.spatialmapper.data.RemovedTreeEntity
import com.gops.spatialmapper.projection.groundDistanceMeters
import com.gops.spatialmapper.scout.ScoutSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "SpatialMapperRemoved"

/**
 * How recent a Scout fix has to be for the marking flow to reuse it instead of asking for a new one.
 *
 * Scout delivers a fix every 3 s, so in normal use the position in hand is a second or two old and
 * this always hits. 30 s is the width of the "the officer has not meaningfully moved, and waiting for
 * a new fix would only add latency" window: at walking pace that is ~35 m, which is the same order as
 * the accuracy of the fix itself, so a newer one would not be more truthful — just slower.
 */
const val OFFICER_FIX_FRESHNESS_MILLIS = 30_000L

/**
 * How long a one-shot fix may take before marking proceeds without an officer position.
 *
 * The brief is explicit that this must not block: a mark is a fast, single-tap action and an officer
 * standing in a clearing should never be left watching a spinner. 4 s is enough for a warm GNSS fix
 * outdoors and short enough that a cold or indoor one fails fast into the perfectly valid
 * "officer position unknown" record rather than holding up the save.
 */
const val OFFICER_FIX_TIMEOUT_MILLIS = 4_000L

/** The officer's own position at marking time, if one could be obtained. */
data class OfficerFix(val position: LatLng, val accuracyMeters: Float?)

/**
 * Ground distance from a removed tree to where the officer stood, or null if either is unknown.
 *
 * THIS IS THE CONFIDENCE SIGNAL, and it is deliberately just a number. There is no confidence field
 * and no prompt: a few metres means the officer marked the stump while standing on it, a few hundred
 * means they marked it off the imagery from elsewhere, and null means there was no fix to compare
 * against. A reader can apply their own threshold to a real measurement, which is more honest than a
 * self-reported dropdown — and it costs the officer no taps.
 *
 * Pure, so the null-handling and the units are testable without a device.
 */
fun proximityMeters(treePosition: LatLng, officerPosition: LatLng?): Double? {
    if (officerPosition == null) return null
    val distance = groundDistanceMeters(treePosition, officerPosition)
    return if (distance.isFinite()) distance else null
}

/**
 * Assembles the row to save. Kept separate from the UI and from location acquisition so the
 * all-or-nothing nullability rule (officer lat, lon and accuracy are present together or not at all)
 * lives in one testable place instead of being re-derived at the call site.
 */
fun buildRemovedTree(
    treePosition: LatLng,
    officerFix: OfficerFix?,
    markedAtMillis: Long
): RemovedTreeEntity = RemovedTreeEntity(
    latitude = treePosition.latitude,
    longitude = treePosition.longitude,
    officerLatitude = officerFix?.position?.latitude,
    officerLongitude = officerFix?.position?.longitude,
    officerAccuracyMeters = officerFix?.accuracyMeters,
    proximityMeters = proximityMeters(treePosition, officerFix?.position),
    markedAtMillis = markedAtMillis
)

/**
 * Renders [proximityMeters] for a human, WITHOUT interpreting it.
 *
 * Deliberately no "field verified" / "remote" labels: the phase's decision is that confidence is
 * inferred from the number, and inventing categories here would quietly reintroduce the confidence
 * field the schema does not have — with a threshold nobody agreed on. Show the metres; let the
 * reader judge.
 */
fun formatProximity(meters: Double?): String = when {
    meters == null -> "position not recorded"
    meters < 1_000.0 -> "%.0f m from where you stood".format(Locale.getDefault(), meters)
    else -> "%.1f km from where you stood".format(Locale.getDefault(), meters / 1_000.0)
}

/**
 * The officer's position for a mark happening right now, or null if none can be had quickly.
 *
 * Two sources, in order:
 *  1. Scout mode's live position, when Scout is running and its last accepted fix is younger than
 *     [OFFICER_FIX_FRESHNESS_MILLIS]. This is free — the foreground service already paid for it —
 *     and it is the common case when marking during a walk.
 *  2. Otherwise a one-shot [com.google.android.gms.location.FusedLocationProviderClient.getCurrentLocation],
 *     capped at [OFFICER_FIX_TIMEOUT_MILLIS].
 *
 * Returns null rather than throwing or waiting on: no permission, no fix in time, provider error.
 * "The officer marked this from a desk" is a supported record, not a failure to handle.
 */
suspend fun resolveOfficerFix(context: Context): OfficerFix? {
    reusableScoutFix()?.let {
        Log.d(TAG, "Reusing Scout's live position for the mark")
        return it
    }
    return requestOneShotFix(context)
}

/** Scout's position if it is live, accepted and recent enough to stand in for a fresh fix. */
private fun reusableScoutFix(): OfficerFix? {
    val scout = ScoutSession.state.value
    if (!scout.active) return null
    val position = scout.position ?: return null
    val fixedAt = scout.positionAtMillis ?: return null
    if (System.currentTimeMillis() - fixedAt > OFFICER_FIX_FRESHNESS_MILLIS) return null
    return OfficerFix(position, scout.accuracyMeters)
}

@SuppressLint("MissingPermission") // Guarded by hasLocationPermission immediately below.
private suspend fun requestOneShotFix(context: Context): OfficerFix? {
    if (!hasLocationPermission(context)) {
        Log.w(TAG, "No location permission; marking without an officer position")
        return null
    }
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cancellation = CancellationTokenSource()

    val location = withTimeoutOrNull(OFFICER_FIX_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            // Cancel the in-flight request if the coroutine is cancelled (timeout, or the operator
            // leaving the screen) — otherwise the GNSS request outlives the mark that wanted it.
            continuation.invokeOnCancellation { cancellation.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { error ->
                    Log.w(TAG, "One-shot location request failed", error)
                    continuation.resume(null)
                }
        }
    }

    if (location == null) {
        Log.i(TAG, "No officer fix within ${OFFICER_FIX_TIMEOUT_MILLIS}ms; marking without one")
        return null
    }
    return OfficerFix(
        position = LatLng(location.latitude, location.longitude),
        accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
    )
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

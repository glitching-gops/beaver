package com.gops.spatialmapper.survey

import android.content.Context
import android.util.Log
import com.gops.spatialmapper.data.SurveyTrackEntity
import com.gops.spatialmapper.data.SurveyTrackRepository
import com.gops.spatialmapper.scout.ScoutSession
import com.gops.spatialmapper.scout.SurveyRecording
import com.gops.spatialmapper.scout.trailLengthMeters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "SpatialMapperSurvey"

/**
 * Turns an in-memory [SurveyRecording] into a persisted row, and owns every path by which that
 * happens.
 *
 * There are three of them and they must not diverge, which is the reason this file exists rather
 * than the logic living at each call site:
 *   1. the operator taps End survey,
 *   2. the operator turns Scout off with a survey still running,
 *   3. the foreground service is torn down with a survey still running.
 *
 * All three funnel through [saveRecording]. [ScoutSession.stop] and [ScoutSession.onServiceGone]
 * both RETURN any recording they were holding precisely so paths 2 and 3 cannot quietly drop one.
 *
 * WHAT A PROCESS KILL COSTS, stated plainly: a recording lives in memory until End. If Android kills
 * the process mid-walk without running the service's onDestroy, that walk is gone. The foreground
 * service makes this unlikely and path 3 covers the graceful case, but it is a real gap. Closing it
 * properly means writing the row at Start and flushing points periodically — which is why
 * [SurveyTrackEntity.endedAtMillis] is nullable and why the list already renders an unfinished
 * track. That is a deliberate follow-up, not an oversight; this phase writes completed surveys only.
 */

/** Label format: enough to tell two walks on the same day apart, short enough for a list row. */
private val labelFormat = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())

/** Date/time shown on a survey row. Matches the timestamp format the History list already uses. */
private val startedAtFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

/**
 * The auto-generated name for a survey started at [startedAtMillis].
 *
 * There is no naming UI on purpose. An officer who has just finished walking a block wants the
 * record filed, not a text field and a keyboard in the rain; and a start timestamp is a better
 * identifier than most things a person would type under those conditions. Renaming can be added
 * later without touching a single stored row.
 */
fun surveyLabelFor(startedAtMillis: Long): String = "Survey ${labelFormat.format(Date(startedAtMillis))}"

/** The start date/time as a survey list row shows it. */
fun formatSurveyStart(startedAtMillis: Long): String =
    startedAtFormat.format(Date(startedAtMillis))

/**
 * Elapsed time as `h:mm:ss` (or `m:ss` under an hour).
 *
 * A null [endedAtMillis] renders as an em dash rather than as a duration measured to "now": a track
 * with no end time is one that was interrupted, and showing it ticking up forever would claim a walk
 * is still happening when nothing is recording it.
 */
fun formatSurveyDuration(startedAtMillis: Long, endedAtMillis: Long?): String {
    if (endedAtMillis == null) return "—"
    return formatElapsed(endedAtMillis - startedAtMillis)
}

/** Live elapsed time for the in-progress readout, where counting up IS the correct behaviour. */
fun formatElapsed(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.getDefault(), hours, minutes, seconds)
    } else {
        "%d:%02d".format(Locale.getDefault(), minutes, seconds)
    }
}

/** Walked distance along a recorded track, reusing Scout's own length calculation. */
fun surveyLengthMeters(recording: SurveyRecording): Double =
    trailLengthMeters(recording.points.map { it.position })

/**
 * Writes one finished recording. Returns the new row id, or null if the write failed.
 *
 * Saves even a one-point or zero-point track. That looks wrong until you consider the alternative:
 * silently discarding a record the operator explicitly asked for, because the app decided it was not
 * interesting enough. A short track is a true statement about a short walk, the list shows its point
 * count, and deleting it is one tap. Inventing a minimum here would be the app overruling the person
 * holding it.
 */
suspend fun saveRecording(
    context: Context,
    recording: SurveyRecording,
    endedAtMillis: Long = System.currentTimeMillis()
): Long? {
    val entity = SurveyTrackEntity(
        label = surveyLabelFor(recording.startedAtMillis),
        startedAtMillis = recording.startedAtMillis,
        endedAtMillis = endedAtMillis,
        pointCount = recording.points.size,
        points = recording.points
    )
    return runCatching { SurveyTrackRepository.get(context).insert(entity) }
        .onSuccess { id ->
            Log.i(
                TAG,
                "Saved survey id=$id '${entity.label}' with ${entity.pointCount} point(s) over " +
                    formatSurveyDuration(recording.startedAtMillis, endedAtMillis)
            )
        }
        .onFailure { Log.e(TAG, "Could not save survey recording", it) }
        .getOrNull()
}

/**
 * Path 1: the operator tapped End survey. Detaches the recording from [ScoutSession] and saves it,
 * leaving Scout itself running — ending a survey is not the same action as stopping tracking.
 *
 * Returns the saved row id, or null if nothing was recording.
 */
suspend fun endAndSaveSurvey(context: Context): Long? {
    val recording = ScoutSession.takeSurvey() ?: return null
    return saveRecording(context, recording)
}

/**
 * Paths 2 and 3: Scout is being turned off (or torn down) and may be holding a recording.
 *
 * [recording] is whatever [ScoutSession.stop] or [ScoutSession.onServiceGone] handed back. Null is
 * the normal case — most Scout sessions never record a survey — so this is a no-op rather than an
 * error when there is nothing to save.
 */
suspend fun saveIfRecording(context: Context, recording: SurveyRecording?): Long? {
    if (recording == null) return null
    Log.i(TAG, "Scout ended with a survey still recording; saving it rather than dropping it")
    return saveRecording(context, recording)
}

package com.gops.spatialmapper.scout

import android.location.Location
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.data.SurveyPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "SpatialMapperScout"

/**
 * What the Scout overlay draws, all of it.
 *
 * @param active            whether Scout mode is on. Owned by [ScoutSession.start]/[ScoutSession.stop].
 * @param position          the last ACCEPTED fix — where the marker sits. Null until the first good
 *                          fix arrives, which is why the marker can be absent while [accuracyMeters]
 *                          already has a value.
 * @param accuracyMeters    the last fix's reported accuracy, accepted or REJECTED. Displayed either
 *                          way: "±38 m" is the single most useful thing to show an operator standing
 *                          under a canopy wondering why the marker stopped moving.
 * @param lastFixUsable     whether that last fix passed [isFixUsable] — lets the readout distinguish
 *                          "tracking, ±6 m" from "signal too weak, ±38 m, holding last position".
 * @param positionAtMillis  when [position] was fixed, or null if there has never been a usable one.
 *                          Only bumped by ACCEPTED fixes, so it dates the marker's position rather
 *                          than the last time any signal arrived. Read by the removed-tree marking
 *                          flow to decide whether this position is fresh enough to reuse instead of
 *                          paying for a new one-shot fix.
 * @param trail             accepted, thinned breadcrumb for this session. Ephemeral by design: it is
 *                          never written to the database and [stop] discards it.
 * @param survey            non-null while a survey is being RECORDED. Distinct from [trail] in the
 *                          only way that matters: the trail is always on while Scout is, and is
 *                          thrown away; this exists because the operator asked for it and is kept.
 *                          Both are fed by the same fixes and thinned by the same rule.
 * @param acceptedFixCount  how many fixes passed the filter, and
 * @param rejectedFixCount  how many were dropped. Shown together so a operator can tell a quiet
 *                          signal from a broken one — 0 of 40 accepted is a very different situation
 *                          from 38 of 40.
 */
/**
 * A survey being recorded right now: when the operator pressed Start, and every point kept since.
 *
 * In memory only, and deliberately so — see [com.gops.spatialmapper.survey.SurveyRecorder] for what
 * happens at End and for the honest note on what a process kill costs.
 */
data class SurveyRecording(
    val startedAtMillis: Long,
    val points: List<SurveyPoint> = emptyList()
)

data class ScoutState(
    val active: Boolean = false,
    val position: LatLng? = null,
    val accuracyMeters: Float? = null,
    val lastFixUsable: Boolean = false,
    val positionAtMillis: Long? = null,
    val trail: List<LatLng> = emptyList(),
    val survey: SurveyRecording? = null,
    val acceptedFixCount: Int = 0,
    val rejectedFixCount: Int = 0
)

/**
 * The single source of truth for Scout mode, shared between [ScoutLocationService] (which writes it)
 * and the Map tab (which reads it).
 *
 * WHY A PROCESS-WIDE OBJECT rather than a bound service or a ViewModel:
 *
 *  - A bound service would tie the location stream to the UI's binding lifetime, which is precisely
 *    what must NOT happen here — Scout has to keep tracking with the screen locked and the Map tab
 *    torn down. Unbinding on screen-off would stop the very thing the foreground service exists to
 *    protect. A started foreground service plus shared state is the shape that matches the
 *    requirement.
 *  - A ViewModel dies with its owner and this app has no DI graph or ViewModel layer at all (see the
 *    navigation note in MainActivity — state is hoisted, not injected). Adding one for this would be
 *    a bigger change than the feature.
 *  - The state must outlive the composable: the operator switches to the History tab mid-walk, comes
 *    back, and the trail is still there because it never lived in the composition.
 *
 * The tradeoff is honest: this is global mutable state, which is the thing DI exists to avoid. It is
 * acceptable here because there is exactly one Scout session per process by definition (one device,
 * one operator, one position), the surface is four methods, and everything interesting about it is
 * pure and tested in [ScoutTrail].
 *
 * Thread-safety: fixes arrive on the service's callback Looper while the UI collects on the main
 * dispatcher. [MutableStateFlow.update] applies its transform atomically via compare-and-set, so
 * concurrent readers always see a consistent [ScoutState] rather than a half-applied one.
 */
object ScoutSession {

    private val _state = MutableStateFlow(ScoutState())
    val state: StateFlow<ScoutState> = _state.asStateFlow()

    /**
     * Bumped on every [start]. Lets a dying service prove the session it is reporting the death of is
     * still the CURRENT one — see [onServiceGone].
     *
     * @Volatile because it is written from the UI thread (the toggle) and read from the service's
     * onDestroy, which the system may run on a different thread than the one that started it.
     */
    @Volatile
    private var sessionToken: Long = 0L

    /** The token a starting service should remember and hand back when it dies. */
    val currentToken: Long get() = sessionToken

    /** True while the service should be running; read by the service to guard late callbacks. */
    val isActive: Boolean get() = _state.value.active

    /**
     * Begins a session, clearing anything left from the previous one.
     *
     * Idempotent: re-activating an already-active session is a no-op rather than a silent trail
     * wipe, so a duplicate start (a double tap, a service restart) cannot destroy the walk so far.
     */
    fun start() {
        if (_state.value.active) {
            Log.d(TAG, "start() ignored — Scout already active")
            return
        }
        sessionToken++
        Log.i(TAG, "Scout session started (token=$sessionToken)")
        // Fresh state, so a survey can never leak from a previous session into a new one.
        _state.value = ScoutState(active = true)
    }

    // -----------------------------------------------------------------------------------------
    // Survey recording
    // -----------------------------------------------------------------------------------------

    /**
     * Begins recording a survey on top of the running Scout session.
     *
     * Refuses when Scout is off: a survey is a recording OF the scout position stream, and there is
     * no stream to record without it. That is enforced here as well as in the UI, because "the
     * button was hidden" is not the same guarantee as "the state machine cannot enter that state".
     *
     * Seeds the track with the current live position when there is one, so the recorded path starts
     * where the operator was standing when they pressed Start rather than wherever the next fix
     * happens to land up to three seconds later. Idempotent: starting an already-running survey is
     * ignored rather than silently discarding the walk so far.
     */
    fun startSurvey(atMillis: Long = System.currentTimeMillis()) {
        _state.update { current ->
            if (!current.active) {
                Log.w(TAG, "startSurvey() ignored — Scout is not active")
                return@update current
            }
            if (current.survey != null) {
                Log.d(TAG, "startSurvey() ignored — a survey is already recording")
                return@update current
            }
            val seed = current.position?.let { listOf(SurveyPoint(it, atMillis)) } ?: emptyList()
            Log.i(TAG, "Survey recording started with ${seed.size} seed point(s)")
            current.copy(survey = SurveyRecording(startedAtMillis = atMillis, points = seed))
        }
    }

    /**
     * Detaches the in-progress survey and hands it to the caller, who is responsible for persisting
     * it. Returns null when nothing was recording.
     *
     * "Take", not "end": this is the ONLY way a recording leaves this object, and it always leaves by
     * being given to somebody. There is deliberately no method that just discards one — losing a walk
     * the operator explicitly asked to record is the single worst thing this feature could do, so the
     * shape of the API makes dropping it require going out of your way.
     */
    fun takeSurvey(): SurveyRecording? {
        var taken: SurveyRecording? = null
        _state.update { current ->
            taken = current.survey
            if (current.survey == null) current else current.copy(survey = null)
        }
        taken?.let { Log.i(TAG, "Survey recording taken for saving: ${it.points.size} point(s)") }
        return taken
    }

    /**
     * Ends the session, DISCARDS the trail, and RETURNS any survey that was still recording.
     *
     * Discarding the trail is the specified behaviour ("the breadcrumb is ephemeral — it clears when
     * Scout is turned off"), and resetting to a fresh [ScoutState] rather than just flipping `active`
     * is what guarantees no stale marker or path survives into the next activation.
     *
     * The survey is the exception, and the return value is how that is enforced. Turning Scout off
     * with a survey running must not orphan it, so this hands it back and makes the caller decide —
     * a caller that ignores the return value is visibly throwing away a recording rather than
     * silently letting one evaporate. Every caller in the app persists it; see [SurveyRecorder].
     */
    fun stop(): SurveyRecording? {
        if (!_state.value.active) return null
        val finished = _state.value
        Log.i(
            TAG,
            "Scout session stopped after ${finished.trail.size} trail point(s), " +
                "${finished.acceptedFixCount} accepted / ${finished.rejectedFixCount} rejected fixes" +
                (finished.survey?.let { ", surrendering a survey of ${it.points.size} point(s)" } ?: "")
        )
        _state.value = ScoutState()
        return finished.survey
    }

    /**
     * Folds one incoming fix into the state.
     *
     * The two halves are deliberately independent: accuracy is recorded for EVERY fix (the operator
     * needs to see a bad signal, not an unexplained frozen marker), while position and trail only
     * move on a fix that passes [isFixUsable]. A rejected fix therefore updates the readout and the
     * reject counter and changes nothing else — the marker holds its last trusted position.
     */
    fun onFix(location: Location) {
        onFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
        )
    }

    /**
     * The actual fold, over plain numbers rather than an [android.location.Location].
     *
     * Split out purely so it is testable: the mockable android.jar used by local unit tests stubs
     * every Location method to throw, so a fold that took one could only ever be exercised on a
     * device. This is the interesting half — the filtering, the thinning, the survey accumulation —
     * and it has no business needing hardware to verify.
     *
     * `internal` rather than public: the service calls the [Location] overload above, and nothing
     * outside this module should be synthesising fixes.
     */
    internal fun onFix(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        atMillis: Long = System.currentTimeMillis()
    ) {
        _state.update { current ->
            if (!current.active) return@update current

            val accuracy = accuracyMeters
            val usable = isFixUsable(accuracy)
            if (!usable) {
                return@update current.copy(
                    accuracyMeters = accuracy,
                    lastFixUsable = false,
                    rejectedFixCount = current.rejectedFixCount + 1
                )
            }

            val position = LatLng(latitude, longitude)
            // Wall-clock time, NOT location.time: this is compared against "now" when deciding
            // whether the fix is stale, and location.time can be a GPS-clock timestamp offset from
            // wall time. Defaulted at the call above rather than read here so tests can pin it.
            val now = atMillis
            current.copy(
                position = position,
                accuracyMeters = accuracy,
                lastFixUsable = true,
                positionAtMillis = now,
                trail = appendToTrail(current.trail, position),
                // THE SURVEY TAPS THIS STREAM — it does not open its own.
                //
                // A recorded track is built here, in the same atomic update, from the same fix that
                // just moved the live breadcrumb. There is exactly one location subscription in the
                // app (ScoutLocationService's) and exactly one funnel out of it (this method), so a
                // survey cannot drift from the line the operator is watching, cannot double the GNSS
                // duty cycle, and cannot keep recording after Scout stops.
                //
                // Reaching this branch already means the fix passed isFixUsable above, so the
                // accuracy filter is shared by construction rather than by being applied twice; the
                // thinning rule is shared explicitly through shouldKeepPoint.
                survey = current.survey?.let { recording ->
                    val lastKept = recording.points.lastOrNull()?.position
                    if (recording.points.size >= SCOUT_MAX_TRAIL_POINTS ||
                        !shouldKeepPoint(lastKept, position)
                    ) {
                        recording
                    } else {
                        recording.copy(points = recording.points + SurveyPoint(position, now))
                    }
                },
                acceptedFixCount = current.acceptedFixCount + 1
            )
        }
    }

    /**
     * Clears state when the service dies without a deliberate [stop] — killed by the system, or a
     * failed start. Without this the UI would keep claiming Scout is active with a marker that never
     * moves again.
     *
     * [token] guards a genuine race: Stop immediately followed by Start hands ActivityManager a
     * stopService and a startForegroundService back to back, and the OLD instance's onDestroy can be
     * delivered AFTER the new session is already live. Without the check, a fumbled double-tap would
     * silently switch Scout off again a second after the operator turned it back on. A service that
     * passes a token from a previous session is reporting a death that no longer matters, so it is
     * logged and ignored. Callers with no token (an outright start failure, where no session was
     * ever handed out) pass null and always clear.
     */
    fun onServiceGone(reason: String, token: Long? = null): SurveyRecording? {
        if (!_state.value.active) return null
        if (token != null && token != sessionToken) {
            Log.d(TAG, "Ignoring stale service teardown ($reason) for token=$token")
            return null
        }
        val abandoned = _state.value.survey
        Log.w(
            TAG,
            "Scout service ended unexpectedly ($reason); clearing state" +
                (abandoned?.let { " and surrendering a survey of ${it.points.size} point(s)" } ?: "")
        )
        _state.value = ScoutState()
        // Same contract as stop(): an unexpected teardown still hands the recording back rather than
        // dropping it, so the service's onDestroy gets a last chance to save a walk in progress.
        return abandoned
    }
}

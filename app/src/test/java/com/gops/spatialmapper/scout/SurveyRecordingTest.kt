package com.gops.spatialmapper.scout

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Survey recording as it lives inside [ScoutSession]: that it only runs while Scout does, that it is
 * fed by the SAME fixes as the live breadcrumb rather than a stream of its own, and that it is never
 * dropped on the floor.
 *
 * These run locally because [ScoutSession.onFix]'s fold takes plain numbers — the
 * `android.location.Location` overload is a two-line adapter over it, precisely so the interesting
 * half does not need a device to verify.
 */
class SurveyRecordingTest {

    private val origin = LatLng(12.9716, 77.5946)

    /** A point [meters] due east of [from] — real ground distance, not a degree offset. */
    private fun east(from: LatLng, meters: Double): LatLng =
        SphericalUtil.computeOffset(from, meters, 90.0)

    /** Drives the same entry point the foreground service uses. */
    private fun feed(position: LatLng, accuracy: Float = 5f, atMillis: Long = 0L) =
        ScoutSession.onFix(position.latitude, position.longitude, accuracy, atMillis)

    @Before
    fun resetSession() {
        // ScoutSession is a process-wide object, so state would otherwise leak between tests.
        ScoutSession.stop()
    }

    @After
    fun clearSession() {
        ScoutSession.stop()
    }

    // --- Lifecycle rules ----------------------------------------------------------------------

    @Test
    fun `a survey cannot start while scout is off`() {
        ScoutSession.startSurvey()
        assertNull(
            "a survey records the Scout position stream; there is none when Scout is off",
            ScoutSession.state.value.survey
        )
    }

    @Test
    fun `starting a survey while scouting begins a recording`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 1_000L)

        val survey = ScoutSession.state.value.survey
        assertNotNull(survey)
        assertEquals(1_000L, survey!!.startedAtMillis)
        // No fix yet, so nothing to seed with — a legitimate state, not an error.
        assertTrue(survey.points.isEmpty())
    }

    @Test
    fun `starting a survey seeds it with the current position`() {
        ScoutSession.start()
        feed(origin, atMillis = 500L)

        ScoutSession.startSurvey(atMillis = 1_000L)

        val points = ScoutSession.state.value.survey!!.points
        assertEquals("the track should start where the operator was standing", 1, points.size)
        assertEquals(origin.latitude, points[0].position.latitude, 1e-9)
        assertEquals(1_000L, points[0].timestampMillis)
    }

    @Test
    fun `starting twice does not restart the recording`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 1_000L)
        ScoutSession.startSurvey(atMillis = 9_000L)

        assertEquals(
            "a duplicate Start must not discard the walk so far",
            1_000L,
            ScoutSession.state.value.survey!!.startedAtMillis
        )
    }

    @Test
    fun `taking the survey detaches it and leaves scout running`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 1_000L)

        val taken = ScoutSession.takeSurvey()

        assertNotNull("takeSurvey must hand the recording to the caller", taken)
        assertNull("and clear it from the session", ScoutSession.state.value.survey)
        assertTrue("ending a survey is not stopping Scout", ScoutSession.state.value.active)
    }

    @Test
    fun `taking when nothing is recording returns null`() {
        ScoutSession.start()
        assertNull(ScoutSession.takeSurvey())
    }

    // --- One stream, not two --------------------------------------------------------------------

    @Test
    fun `survey points and the live trail come from the same fixes`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 0L)

        // A 50 m walk in 2.5 m steps, which at the 4 m thinning distance keeps every second one.
        (0 until 20).forEach { step -> feed(east(origin, step * 2.5), atMillis = step * 3_000L) }

        val state = ScoutSession.state.value
        assertEquals(
            "the recorded track and the drawn breadcrumb must be the same path",
            state.trail,
            state.survey!!.points.map { it.position }
        )
    }

    @Test
    fun `a fix too inaccurate to draw is also too inaccurate to record`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 0L)
        feed(origin, atMillis = 1_000L)

        val before = ScoutSession.state.value.survey!!.points.size
        // Well past SCOUT_MAX_ACCURACY_METERS, and far enough away to clear the spacing rule — so
        // only the accuracy filter can be keeping it out.
        feed(east(origin, 50.0), accuracy = 80f, atMillis = 4_000L)
        val state = ScoutSession.state.value

        assertEquals("a rejected fix must not enter the track", before, state.survey!!.points.size)
        assertEquals("nor the trail", 1, state.trail.size)
        assertEquals("but it is still counted as rejected", 1, state.rejectedFixCount)
        assertEquals("and its accuracy is still shown", 80f, state.accuracyMeters!!, 1e-6f)
    }

    @Test
    fun `standing still adds nothing to the track`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 0L)
        feed(origin, atMillis = 1_000L)

        // A jitter cloud within a couple of metres, which is what a stationary phone produces.
        repeat(40) { i -> feed(east(origin, (i % 4) * 0.5), atMillis = 2_000L + i * 3_000L) }

        assertEquals(1, ScoutSession.state.value.survey!!.points.size)
    }

    @Test
    fun `points carry the time they were recorded`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 0L)
        feed(east(origin, 0.0), atMillis = 10_000L)
        feed(east(origin, 10.0), atMillis = 13_000L)
        feed(east(origin, 20.0), atMillis = 16_000L)

        val points = ScoutSession.state.value.survey!!.points
        assertEquals(3, points.size)
        assertEquals(listOf(10_000L, 13_000L, 16_000L), points.map { it.timestampMillis })
    }

    @Test
    fun `fixes arriving before Start are not retroactively recorded`() {
        ScoutSession.start()
        feed(origin, atMillis = 1_000L)
        feed(east(origin, 20.0), atMillis = 4_000L)
        // Two points already on the live trail...
        assertEquals(2, ScoutSession.state.value.trail.size)

        ScoutSession.startSurvey(atMillis = 5_000L)

        // ...but the survey begins now, seeded only with where the operator currently is.
        assertEquals(1, ScoutSession.state.value.survey!!.points.size)
    }

    @Test
    fun `fixes arriving after End are not recorded`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 0L)
        feed(origin, atMillis = 1_000L)
        val taken = ScoutSession.takeSurvey()!!

        feed(east(origin, 30.0), atMillis = 4_000L)

        assertEquals("the taken recording is a snapshot, not a live view", 1, taken.points.size)
        assertNull(ScoutSession.state.value.survey)
        assertEquals("the live trail keeps going, though", 2, ScoutSession.state.value.trail.size)
    }

    // --- The no-orphan guarantee ----------------------------------------------------------------

    @Test
    fun `stopping scout surrenders an in-progress survey instead of dropping it`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 1_000L)

        val surrendered = ScoutSession.stop()

        assertNotNull(
            "turning Scout off must hand back a running survey so the caller can save it",
            surrendered
        )
        assertEquals(1_000L, surrendered!!.startedAtMillis)
        assertNull(ScoutSession.state.value.survey)
    }

    @Test
    fun `stopping with no survey surrenders nothing`() {
        ScoutSession.start()
        assertNull(ScoutSession.stop())
    }

    @Test
    fun `an unexpected service teardown also surrenders the survey`() {
        ScoutSession.start()
        val token = ScoutSession.currentToken
        ScoutSession.startSurvey(atMillis = 1_000L)

        val surrendered = ScoutSession.onServiceGone("killed", token)

        assertNotNull("a system kill is exactly when a walk must not be lost", surrendered)
        assertEquals(1_000L, surrendered!!.startedAtMillis)
    }

    @Test
    fun `a stale teardown surrenders nothing and leaves the current survey alone`() {
        ScoutSession.start()
        ScoutSession.stop()
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 2_000L)

        // A superseded service instance reporting its own death with an old token.
        val surrendered = ScoutSession.onServiceGone("stale", token = 0L)

        assertNull(surrendered)
        assertEquals(
            "the live survey must survive a stale teardown",
            2_000L,
            ScoutSession.state.value.survey!!.startedAtMillis
        )
    }

    @Test
    fun `a new scout session does not inherit the previous one's recording`() {
        ScoutSession.start()
        ScoutSession.startSurvey(atMillis = 1_000L)
        ScoutSession.stop()

        ScoutSession.start()

        assertNull(ScoutSession.state.value.survey)
    }

    // --- Shared thinning ------------------------------------------------------------------------

    @Test
    fun `the trail and a survey thin identically because they share one rule`() {
        // Both sides of the app go through shouldKeepPoint. This pins that they agree point for
        // point over a walk, which is what stops a saved track from diverging from the line the
        // operator watched themselves draw.
        val walk = (0 until 30).map { east(origin, it * 2.5) }

        var trail = emptyList<LatLng>()
        var survey = emptyList<LatLng>()
        walk.forEach { position ->
            trail = appendToTrail(trail, position)
            if (shouldKeepPoint(survey.lastOrNull(), position)) survey = survey + position
        }

        assertEquals("identical rule, identical result", trail, survey)
        assertTrue(trail.isNotEmpty())
    }

    @Test
    fun `the shared predicate is what appendToTrail uses`() {
        val trail = listOf(origin)
        val near = east(origin, 1.0)
        val far = east(origin, 10.0)

        assertSame("a rejected point returns the same list", trail, appendToTrail(trail, near))
        assertEquals(shouldKeepPoint(origin, near), appendToTrail(trail, near) !== trail)
        assertEquals(shouldKeepPoint(origin, far), appendToTrail(trail, far) !== trail)
    }
}

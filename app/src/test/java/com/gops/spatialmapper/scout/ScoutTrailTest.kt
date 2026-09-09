package com.gops.spatialmapper.scout

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The two decisions Scout mode makes about every incoming fix: trust it, and draw it.
 *
 * Local unit tests — [SphericalUtil] and [LatLng] are plain JVM classes, so the filtering and
 * thinning rules can be pinned down without a device, which matters because the failure modes here
 * (a marker that never moves, a trail scribbled through trees) are miserable to reproduce in a forest.
 */
class ScoutTrailTest {

    private val start = LatLng(12.9716, 77.5946)

    // formatTrailLength is deliberately locale-aware (operator-facing), so the km expectations below
    // are pinned rather than inheriting whatever the build machine uses — a comma-decimal locale
    // would otherwise fail them for the wrong reason.
    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.UK)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /** A point [meters] due east of [from] — real ground distance, not a degree offset. */
    private fun east(from: LatLng, meters: Double): LatLng =
        SphericalUtil.computeOffset(from, meters, 90.0)

    // --- Accuracy filtering -------------------------------------------------------------------

    @Test
    fun `a fix at or inside the threshold is usable`() {
        assertTrue(isFixUsable(1f))
        assertTrue(isFixUsable(SCOUT_MAX_ACCURACY_METERS / 2f))
        // Boundary: exactly at the threshold is accepted, not rejected.
        assertTrue(isFixUsable(SCOUT_MAX_ACCURACY_METERS))
    }

    @Test
    fun `a fix worse than the threshold is rejected`() {
        assertFalse(isFixUsable(SCOUT_MAX_ACCURACY_METERS + 0.1f))
        // The realistic under-canopy case this filter exists for.
        assertFalse(isFixUsable(38f))
    }

    @Test
    fun `an unquantified or nonsensical accuracy is rejected`() {
        assertFalse("no accuracy reported means no confidence", isFixUsable(null))
        assertFalse(isFixUsable(Float.NaN))
        assertFalse(isFixUsable(0f))
        assertFalse(isFixUsable(-1f))
    }

    @Test
    fun `the threshold is tunable per call`() {
        assertFalse(isFixUsable(25f))
        assertTrue(isFixUsable(25f, thresholdMeters = 30f))
    }

    // --- Trail thinning -----------------------------------------------------------------------

    @Test
    fun `the first point is always kept`() {
        assertEquals(listOf(start), appendToTrail(emptyList(), start))
    }

    @Test
    fun `a point closer than the spacing is dropped`() {
        val trail = listOf(start)
        // 1 m of standing-still jitter, well inside the 4 m spacing.
        val result = appendToTrail(trail, east(start, 1.0))
        assertEquals(trail, result)
        // Same instance, so a StateFlow holding it emits nothing and the Polyline is not rebuilt.
        assertSame("rejection must not allocate a new list", trail, result)
    }

    @Test
    fun `a point beyond the spacing is kept`() {
        val trail = listOf(start)
        val moved = east(start, SCOUT_MIN_TRAIL_SPACING_METERS + 1.0)
        assertEquals(listOf(start, moved), appendToTrail(trail, moved))
    }

    @Test
    fun `spacing is measured from the last KEPT point not the last seen one`() {
        // Three 2 m steps: each is under the 4 m spacing on its own, but the third is 6 m from the
        // only point that was actually kept, so it must land. Measuring against the last *seen*
        // point instead would drop it and the trail would stall while the operator walked away.
        var trail = listOf(start)
        val stepOne = east(start, 2.0)
        val stepTwo = east(start, 4.5)
        val stepThree = east(start, 6.0)

        trail = appendToTrail(trail, stepOne)
        assertEquals(1, trail.size)
        trail = appendToTrail(trail, stepTwo)
        assertEquals("4.5 m from the kept point clears the 4 m spacing", 2, trail.size)
        trail = appendToTrail(trail, stepThree)
        assertEquals("only 1.5 m from the new kept point", 2, trail.size)
    }

    @Test
    fun `standing still adds nothing however many fixes arrive`() {
        var trail = listOf(start)
        repeat(100) { i ->
            // A jitter cloud within a couple of metres, which is what a stationary phone produces.
            trail = appendToTrail(trail, east(start, (i % 4) * 0.5))
        }
        assertEquals(1, trail.size)
    }

    @Test
    fun `walking a straight line keeps one point per spacing interval`() {
        var trail = emptyList<LatLng>()
        // 20 fixes at 2.5 m apart. Every second one is 5 m from the last kept point, so the trail is
        // 0, 5, 10 ... 45 m — ten points over 45 m walked.
        //
        // The step is 2.5 m and not 2 m ON PURPOSE: a 2 m step puts every other candidate at exactly
        // the 4 m spacing, where the answer is decided by whether computeOffset/computeDistanceBetween
        // round the round-trip to 3.9999... or 4.0000... That is a real property of the threshold, but
        // it makes for a test that asserts floating-point luck rather than behaviour.
        repeat(20) { i -> trail = appendToTrail(trail, east(start, i * 2.5)) }
        assertEquals(10, trail.size)
        assertEquals(45.0, trailLengthMeters(trail), 0.5)
    }

    @Test
    fun `the spacing is tunable per call`() {
        val trail = listOf(start)
        val candidate = east(start, 2.0)
        assertEquals(1, appendToTrail(trail, candidate).size)
        assertEquals(2, appendToTrail(trail, candidate, minSpacingMeters = 1.0).size)
    }

    @Test
    fun `the trail stops growing at the cap instead of dropping its start`() {
        val cap = 5
        var trail = emptyList<LatLng>()
        repeat(20) { i -> trail = appendToTrail(trail, east(start, i * 10.0), maxPoints = cap) }
        assertEquals(cap, trail.size)
        // The FIRST point survives — losing the start of a survey track is worse than losing the end.
        assertEquals(start.latitude, trail.first().latitude, 1e-9)
        assertEquals(start.longitude, trail.first().longitude, 1e-9)
    }

    // --- Distance readout ---------------------------------------------------------------------

    @Test
    fun `a trail shorter than two points has no length`() {
        assertEquals(0.0, trailLengthMeters(emptyList()), 0.0)
        assertEquals(0.0, trailLengthMeters(listOf(start)), 0.0)
    }

    @Test
    fun `length sums the legs rather than measuring end to end`() {
        // Out 30 m and back: 60 m walked, 0 m displaced. Summing legs is the point.
        val outAndBack = listOf(start, east(start, 30.0), start)
        assertEquals(60.0, trailLengthMeters(outAndBack), 0.5)
    }

    @Test
    fun `length switches to kilometres past a thousand metres`() {
        assertEquals("0 m", formatTrailLength(0.0))
        assertEquals("240 m", formatTrailLength(240.7))
        assertEquals("999 m", formatTrailLength(999.9))
        assertEquals("1.00 km", formatTrailLength(1_000.0))
        assertEquals("2.35 km", formatTrailLength(2_345.0))
    }

    // --- The constants themselves -------------------------------------------------------------

    @Test
    fun `the tuning constants stay within their documented intent`() {
        assertEquals("3 s walking-pace interval", 3_000L, SCOUT_UPDATE_INTERVAL_MILLIS)
        assertEquals(20f, SCOUT_MAX_ACCURACY_METERS, 0f)
        // The brief specifies 3-5 m thinning; this guards against an accidental edit outside it.
        assertTrue(
            "thinning distance should stay in the 3-5 m band",
            SCOUT_MIN_TRAIL_SPACING_METERS in 3.0..5.0
        )
        assertTrue(SCOUT_MIN_UPDATE_INTERVAL_MILLIS <= SCOUT_UPDATE_INTERVAL_MILLIS)
    }
}

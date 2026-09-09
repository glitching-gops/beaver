package com.gops.spatialmapper.removed

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The pure half of removed-tree marking: the proximity signal and the all-or-nothing nullability of
 * the officer fields.
 *
 * These matter more than their size suggests. [proximityMeters] is the ONLY confidence information
 * the schema carries — there is no confidence column by explicit decision — so a units bug or a
 * silently-swallowed null here would not surface as a crash, it would surface as a survey where
 * every mark looks equally trustworthy.
 */
class RemovedTreeMarkingTest {

    private val tree = LatLng(12.9716, 77.5946)

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

    // --- Proximity ----------------------------------------------------------------------------

    @Test
    fun `proximity is the ground distance between the tree and the officer`() {
        val officer = SphericalUtil.computeOffset(tree, 15.0, 90.0)
        assertEquals(15.0, proximityMeters(tree, officer)!!, 0.1)
    }

    @Test
    fun `standing on the tree gives roughly zero`() {
        assertEquals(0.0, proximityMeters(tree, tree)!!, 0.01)
    }

    @Test
    fun `no officer position means no proximity, not zero`() {
        // Zero would read as "the officer was standing on it" — the strongest possible confidence
        // claim — from the case where we know the least. Null is the only honest answer.
        assertNull(proximityMeters(tree, null))
    }

    @Test
    fun `a desk mark far away still produces a real number`() {
        val faraway = SphericalUtil.computeOffset(tree, 4_200.0, 210.0)
        assertEquals(4_200.0, proximityMeters(tree, faraway)!!, 5.0)
    }

    // --- Row assembly -------------------------------------------------------------------------

    @Test
    fun `a field mark carries the officer position and the computed proximity`() {
        val officer = SphericalUtil.computeOffset(tree, 8.0, 0.0)
        val entity = buildRemovedTree(
            treePosition = tree,
            officerFix = OfficerFix(officer, accuracyMeters = 4.5f),
            markedAtMillis = 1_700_000_000_000L
        )

        assertEquals(tree.latitude, entity.latitude, 1e-9)
        assertEquals(tree.longitude, entity.longitude, 1e-9)
        assertEquals(officer.latitude, entity.officerLatitude!!, 1e-9)
        assertEquals(officer.longitude, entity.officerLongitude!!, 1e-9)
        assertEquals(4.5f, entity.officerAccuracyMeters!!, 1e-6f)
        assertEquals(8.0, entity.proximityMeters!!, 0.1)
        assertEquals(1_700_000_000_000L, entity.markedAtMillis)
    }

    @Test
    fun `a mark with no fix leaves every officer field null together`() {
        val entity = buildRemovedTree(
            treePosition = tree,
            officerFix = null,
            markedAtMillis = 1_700_000_000_000L
        )

        // The tree itself is always recorded — that is the point of the record.
        assertEquals(tree.latitude, entity.latitude, 1e-9)
        assertEquals(tree.longitude, entity.longitude, 1e-9)
        // All four officer-derived fields are absent as a group; no partial row.
        assertNull(entity.officerLatitude)
        assertNull(entity.officerLongitude)
        assertNull(entity.officerAccuracyMeters)
        assertNull(entity.proximityMeters)
    }

    @Test
    fun `a fix with no accuracy still yields a position and a proximity`() {
        // getCurrentLocation can return a location without an accuracy estimate. That is enough to
        // compute distance, so the record should keep everything it can rather than discarding it.
        val officer = SphericalUtil.computeOffset(tree, 20.0, 45.0)
        val entity = buildRemovedTree(tree, OfficerFix(officer, accuracyMeters = null), 1L)

        assertNotNull(entity.officerLatitude)
        assertNull(entity.officerAccuracyMeters)
        assertEquals(20.0, entity.proximityMeters!!, 0.2)
    }

    // --- Display ------------------------------------------------------------------------------

    @Test
    fun `proximity formatting reports metres without interpreting them`() {
        assertEquals("0 m from where you stood", formatProximity(0.4))
        assertEquals("15 m from where you stood", formatProximity(15.0))
        assertEquals("999 m from where you stood", formatProximity(999.0))
        assertEquals("4.2 km from where you stood", formatProximity(4_200.0))
        assertEquals("position not recorded", formatProximity(null))
    }

    @Test
    fun `formatting never labels a distance as verified or remote`() {
        // Guards the explicit phase decision: confidence is inferred by the reader from the number,
        // so no threshold-based wording may creep in here.
        listOf(0.5, 15.0, 150.0, 5_000.0).forEach { meters ->
            val text = formatProximity(meters).lowercase(Locale.ROOT)
            listOf("verified", "remote", "desk", "confident", "approximate").forEach { banned ->
                assert(!text.contains(banned)) { "formatProximity($meters) must not say '$banned'" }
            }
        }
    }

    @Test
    fun `the freshness window is wide enough to reuse a scout fix but not a stale one`() {
        // Scout delivers every 3 s, so the window must comfortably clear that...
        assert(OFFICER_FIX_FRESHNESS_MILLIS > 3_000L)
        // ...while staying short enough that the reused position still describes where the officer
        // is standing now. 30 s at walking pace is ~35 m, the same order as the fix's own accuracy.
        assert(OFFICER_FIX_FRESHNESS_MILLIS <= 60_000L)
        // And the one-shot must give up well before an operator would think the app had hung.
        assert(OFFICER_FIX_TIMEOUT_MILLIS in 1_000L..5_000L)
    }
}

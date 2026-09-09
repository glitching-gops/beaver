package com.gops.spatialmapper.area

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Area calculation. Runs locally because [SphericalUtil] and [LatLng] are plain JVM classes from
 * android-maps-utils / the Maps SDK model — no android.jar stubbing involved.
 */
class CanopyAreaTest {

    // formatArea is deliberately locale-aware (it is operator-facing), so the expectations below are
    // pinned to one locale rather than inheriting whatever the build machine happens to use.
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

    @Test
    fun `fewer than three vertices has no area`() {
        assertNull(polygonAreaSquareMeters(emptyList()))
        assertNull(polygonAreaSquareMeters(listOf(LatLng(12.0, 77.0))))
        assertNull(polygonAreaSquareMeters(listOf(LatLng(12.0, 77.0), LatLng(12.0001, 77.0))))
    }

    @Test
    fun `square of known side length has the expected area`() {
        // Built with computeOffset so the sides are true 20 m ground distances rather than a
        // degree-count that would skew with latitude — the exact failure mode the spherical
        // calculation exists to avoid.
        val southWest = LatLng(12.9716, 77.5946)
        val side = 20.0
        val southEast = SphericalUtil.computeOffset(southWest, side, 90.0)
        val northEast = SphericalUtil.computeOffset(southEast, side, 0.0)
        val northWest = SphericalUtil.computeOffset(southWest, side, 0.0)

        val area = polygonAreaSquareMeters(listOf(southWest, southEast, northEast, northWest))

        // 400 m² to within a few cm² of spherical-vs-planar difference at this scale.
        assertEquals(400.0, area!!, 0.5)
    }

    @Test
    fun `area is unsigned so winding does not matter`() {
        val vertices = listOf(
            LatLng(12.9716, 77.5946),
            LatLng(12.9716, 77.5948),
            LatLng(12.9718, 77.5948),
            LatLng(12.9718, 77.5946)
        )
        assertEquals(
            polygonAreaSquareMeters(vertices)!!,
            polygonAreaSquareMeters(vertices.reversed())!!,
            1e-6
        )
    }

    @Test
    fun `an unclosed path is closed by the calculation itself`() {
        val open = listOf(
            LatLng(12.9716, 77.5946),
            LatLng(12.9716, 77.5948),
            LatLng(12.9718, 77.5948),
            LatLng(12.9718, 77.5946)
        )
        val closed = open + open.first()
        assertEquals(polygonAreaSquareMeters(open)!!, polygonAreaSquareMeters(closed)!!, 1e-6)
    }

    @Test
    fun `formatting switches to hectares only at one hectare`() {
        assertEquals("— (not computed)", formatArea(null))
        // Below a hectare: square meters only, precision falling away as the number grows.
        assertEquals("7.35 m²", formatArea(7.3456))
        assertEquals("73.5 m²", formatArea(73.456))
        assertEquals("735 m²", formatArea(734.56))
        assertEquals("9999 m²", formatArea(9999.0))
        // At and above a hectare: hectares, with the m² kept alongside for cross-checking.
        assertEquals("1.00 ha (10000 m²)", formatArea(SQUARE_METERS_PER_HECTARE))
        assertEquals("2.50 ha (25000 m²)", formatArea(25_000.0))
    }
}

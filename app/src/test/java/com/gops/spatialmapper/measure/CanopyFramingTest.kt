package com.gops.spatialmapper.measure

import com.gops.spatialmapper.capture.CameraIntrinsicsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the manual-framing measurement math.
 *
 * These are the functions whose bugs would silently produce plausible-but-wrong field data — a
 * forgotten display-scale factor, say, would under-report every canopy by a consistent factor without
 * ever looking broken — so they're tested away from Compose and Android entirely.
 */
class CanopyFramingTest {

    // --- canopyDiameterMeters (pinhole) -------------------------------------------------------

    @Test
    fun `pinhole formula scales linearly with distance`() {
        // 100 px wide at 10 m with f = 500 px  →  100 * 10 / 500 = 2.0 m
        assertEquals(2.0, canopyDiameterMeters(100, 10f, 500f)!!, 1e-9)
        // Twice as far, same apparent size → twice the real size.
        assertEquals(4.0, canopyDiameterMeters(100, 20f, 500f)!!, 1e-9)
    }

    @Test
    fun `pinhole formula scales inversely with focal length`() {
        assertEquals(2.0, canopyDiameterMeters(100, 10f, 500f)!!, 1e-9)
        assertEquals(1.0, canopyDiameterMeters(100, 10f, 1000f)!!, 1e-9)
    }

    @Test
    fun `pinhole formula scales linearly with pixel span`() {
        val narrow = canopyDiameterMeters(50, 10f, 500f)!!
        val wide = canopyDiameterMeters(200, 10f, 500f)!!
        assertEquals(4.0, wide / narrow, 1e-9)
    }

    @Test
    fun `realistic capture produces a sane canopy size`() {
        // A crown spanning 900 px of a 1440 px-wide frame, 12 m away, f = 1200 px → about 9 m.
        assertEquals(9.0, canopyDiameterMeters(900, 12f, 1200f)!!, 1e-6)
    }

    @Test
    fun `missing inputs yield null rather than a bogus number`() {
        assertNull(canopyDiameterMeters(100, null, 500f))
        assertNull(canopyDiameterMeters(100, 10f, null))
    }

    @Test
    fun `non-physical inputs yield null`() {
        assertNull(canopyDiameterMeters(0, 10f, 500f))
        assertNull(canopyDiameterMeters(-5, 10f, 500f))
        assertNull(canopyDiameterMeters(100, 0f, 500f))
        assertNull(canopyDiameterMeters(100, -1f, 500f))
        assertNull(canopyDiameterMeters(100, 10f, 0f))
        assertNull(canopyDiameterMeters(100, Float.NaN, 500f))
        assertNull(canopyDiameterMeters(100, 10f, Float.POSITIVE_INFINITY))
    }

    // --- displayToImagePixelScale -------------------------------------------------------------

    @Test
    fun `display scale is capture pixels per displayed pixel`() {
        // A 4000 px capture drawn 1000 px wide on screen → each screen pixel covers 4 capture pixels.
        assertEquals(4f, displayToImagePixelScale(4000, 1000f)!!, 1e-6f)
    }

    @Test
    fun `display scale is one when the photo is shown at full resolution`() {
        assertEquals(1f, displayToImagePixelScale(1080, 1080f)!!, 1e-6f)
    }

    @Test
    fun `display scale below one when the photo is shown magnified`() {
        assertEquals(0.5f, displayToImagePixelScale(500, 1000f)!!, 1e-6f)
    }

    @Test
    fun `display scale rejects degenerate sizes`() {
        assertNull(displayToImagePixelScale(0, 1000f))
        assertNull(displayToImagePixelScale(-10, 1000f))
        assertNull(displayToImagePixelScale(4000, 0f))
        assertNull(displayToImagePixelScale(4000, -1f))
        assertNull(displayToImagePixelScale(4000, Float.NaN))
    }

    // --- uprightImageSize ---------------------------------------------------------------------

    @Test
    fun `upright size swaps axes for a quarter-turn sensor image`() {
        // The AR path saves the raw sensor image; SENSOR_ORIENTATION is 90 on virtually every phone.
        val ar = CameraIntrinsicsSnapshot(1200f, 1920, 1080, rotationDegrees = 90)
        assertEquals(1080 to 1920, ar.uprightImageSize())
    }

    @Test
    fun `upright size is unchanged for an already-upright jpeg`() {
        // CameraX applies the target rotation itself and reports 0.
        val cameraX = CameraIntrinsicsSnapshot(1200f, 3000, 4000, rotationDegrees = 0)
        assertEquals(3000 to 4000, cameraX.uprightImageSize())
    }

    @Test
    fun `upright size handles 180 and 270 and negative rotations`() {
        assertEquals(
            1920 to 1080,
            CameraIntrinsicsSnapshot(1f, 1920, 1080, rotationDegrees = 180).uprightImageSize()
        )
        assertEquals(
            1080 to 1920,
            CameraIntrinsicsSnapshot(1f, 1920, 1080, rotationDegrees = 270).uprightImageSize()
        )
        assertEquals(
            1080 to 1920,
            CameraIntrinsicsSnapshot(1f, 1920, 1080, rotationDegrees = -90).uprightImageSize()
        )
    }

    @Test
    fun `upright size does not change the longest side`() {
        // The whole point of scaling by the longest side: rotation must not perturb it.
        val raw = CameraIntrinsicsSnapshot(1200f, 4032, 3024, rotationDegrees = 90)
        val (w, h) = raw.uprightImageSize()
        assertEquals(raw.longestSidePixels, maxOf(w, h))
    }

    // --- canopyMeasurementFromEllipse ---------------------------------------------------------

    @Test
    fun `ellipse radii convert through the display scale to real diameters`() {
        // 50 display px radius → 100 display px diameter → ×4 → 400 capture px.
        // 400 px at 10 m with f = 1000 px → 4.0 m on both axes.
        val measurement = canopyMeasurementFromEllipse(
            ellipse = CanopyEllipse(centerX = 200f, centerY = 300f, radiusX = 50f, radiusY = 50f),
            displayToImageScale = 4f,
            distanceMeters = 10f,
            focalLengthPixels = 1000f
        )!!
        assertEquals(4.0, measurement.horizontalDiameterMeters, 1e-6)
        assertEquals(4.0, measurement.verticalDiameterMeters, 1e-6)
        assertEquals(4.0, measurement.diameterMeters, 1e-6)
    }

    @Test
    fun `ignoring the display scale would under-report - the factor really is applied`() {
        val ellipse = CanopyEllipse(0f, 0f, radiusX = 50f, radiusY = 50f)
        val scaled = canopyMeasurementFromEllipse(ellipse, 4f, 10f, 1000f)!!
        val unscaled = canopyMeasurementFromEllipse(ellipse, 1f, 10f, 1000f)!!
        assertEquals(4.0, scaled.diameterMeters / unscaled.diameterMeters, 1e-6)
    }

    @Test
    fun `the two axes are measured independently and averaged`() {
        // radiusX 100 → 200 display px → 200 capture px → 2.0 m
        // radiusY  50 → 100 display px → 100 capture px → 1.0 m
        val measurement = canopyMeasurementFromEllipse(
            ellipse = CanopyEllipse(0f, 0f, radiusX = 100f, radiusY = 50f),
            displayToImageScale = 1f,
            distanceMeters = 10f,
            focalLengthPixels = 1000f
        )!!
        assertEquals(2.0, measurement.horizontalDiameterMeters, 1e-6)
        assertEquals(1.0, measurement.verticalDiameterMeters, 1e-6)
        assertEquals(1.5, measurement.diameterMeters, 1e-6)
    }

    @Test
    fun `averaging is order-independent between the axes`() {
        val wide = canopyMeasurementFromEllipse(CanopyEllipse(0f, 0f, 100f, 50f), 1f, 10f, 1000f)!!
        val tall = canopyMeasurementFromEllipse(CanopyEllipse(0f, 0f, 50f, 100f), 1f, 10f, 1000f)!!
        assertEquals(wide.diameterMeters, tall.diameterMeters, 1e-9)
    }

    @Test
    fun `radius is half the averaged diameter`() {
        val measurement = canopyMeasurementFromEllipse(
            CanopyEllipse(0f, 0f, 100f, 50f), 1f, 10f, 1000f
        )!!
        assertEquals(measurement.diameterMeters / 2.0, measurement.radiusMeters, 1e-9)
    }

    @Test
    fun `ellipse center does not affect the measured size`() {
        val centered = canopyMeasurementFromEllipse(CanopyEllipse(500f, 500f, 80f, 60f), 2f, 8f, 900f)!!
        val corner = canopyMeasurementFromEllipse(CanopyEllipse(0f, 0f, 80f, 60f), 2f, 8f, 900f)!!
        assertEquals(centered.diameterMeters, corner.diameterMeters, 1e-9)
    }

    @Test
    fun `degenerate ellipse or camera model yields null`() {
        val ellipse = CanopyEllipse(0f, 0f, 50f, 50f)
        assertNull(canopyMeasurementFromEllipse(ellipse, 0f, 10f, 1000f))
        assertNull(canopyMeasurementFromEllipse(ellipse, -1f, 10f, 1000f))
        assertNull(canopyMeasurementFromEllipse(ellipse, Float.NaN, 10f, 1000f))
        assertNull(canopyMeasurementFromEllipse(ellipse, 1f, null, 1000f))
        assertNull(canopyMeasurementFromEllipse(ellipse, 1f, 10f, null))
        // A collapsed axis rounds to a zero-pixel span, which the pinhole conversion rejects.
        assertNull(canopyMeasurementFromEllipse(CanopyEllipse(0f, 0f, 50f, 0f), 1f, 10f, 1000f))
    }

    // --- sanity bounds ------------------------------------------------------------------------

    @Test
    fun `plausible bounds are inclusive at both ends`() {
        assertTrue(isPlausibleCanopyDiameter(MIN_PLAUSIBLE_CANOPY_DIAMETER_METERS))
        assertTrue(isPlausibleCanopyDiameter(MAX_PLAUSIBLE_CANOPY_DIAMETER_METERS))
        assertTrue(isPlausibleCanopyDiameter(5.0))
    }

    @Test
    fun `implausibly small and large diameters are flagged`() {
        assertFalse(isPlausibleCanopyDiameter(0.05))
        assertFalse(isPlausibleCanopyDiameter(120.0))
        assertFalse(isPlausibleCanopyDiameter(Double.NaN))
        assertFalse(isPlausibleCanopyDiameter(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `an out-of-bounds measurement is still produced, only flagged`() {
        // The whole difference from the old segmentation gate: implausible is a warning, not a block.
        // 4 display px diameter at 10 m with f = 1000 px → 0.04 m.
        val measurement = canopyMeasurementFromEllipse(
            CanopyEllipse(0f, 0f, 2f, 2f), 1f, 10f, 1000f
        )
        assertNotNull("a tiny ellipse must still measure, not fail", measurement)
        assertFalse(measurement!!.isPlausible)
        assertTrue(measurement.diameterMeters > 0.0)
    }

    // --- default ellipse ----------------------------------------------------------------------

    @Test
    fun `default ellipse is centered and about a third of the shorter side`() {
        val ellipse = defaultCanopyEllipse(displayedWidth = 900f, displayedHeight = 1200f)
        assertEquals(450f, ellipse.centerX, 1e-3f)
        assertEquals(600f, ellipse.centerY, 1e-3f)
        // Shorter side is 900 → diameter 300 → radius 150, on both axes (a circle).
        assertEquals(150f, ellipse.radiusX, 1e-3f)
        assertEquals(150f, ellipse.radiusY, 1e-3f)
    }

    @Test
    fun `default ellipse fits inside the photo on both axes`() {
        val width = 1000f
        val height = 400f
        val ellipse = defaultCanopyEllipse(width, height)
        assertTrue(ellipse.centerX - ellipse.radiusX >= 0f)
        assertTrue(ellipse.centerX + ellipse.radiusX <= width)
        assertTrue(ellipse.centerY - ellipse.radiusY >= 0f)
        assertTrue(ellipse.centerY + ellipse.radiusY <= height)
    }

    @Test
    fun `default ellipse is never degenerate`() {
        // Guards the "sensible default rather than a zero-size ellipse" requirement even if the
        // photo area is measured as vanishingly small during the first layout pass.
        val ellipse = defaultCanopyEllipse(displayedWidth = 0f, displayedHeight = 0f)
        assertTrue(ellipse.radiusX > 0f)
        assertTrue(ellipse.radiusY > 0f)
    }
}

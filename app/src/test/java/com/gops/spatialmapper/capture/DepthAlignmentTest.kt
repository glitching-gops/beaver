package com.gops.spatialmapper.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Photo pixel -> depth pixel, tested by CONTENT, not by dimensions.
 *
 * The rotation bug this project shipped once passed tests that only checked that width and height
 * had swapped — which a rotation in the wrong direction (or a transpose) also does. So every test
 * here looks a value up in a synthetic depth image whose four quadrants hold four different
 * distances, from photo points whose correct quadrant is known, and asserts the VALUE that comes
 * back. A wrong rotation direction, a swapped axis, or a mirrored one each read a different quadrant
 * and fail.
 *
 * The expected corners are the same ones ImageRotationInstrumentedTest pins for the real bitmap
 * rotation (90° clockwise: raw top-left -> upright top-right, raw top-right -> upright bottom-right,
 * raw bottom-left -> upright top-left, raw bottom-right -> upright bottom-left), so the photo and the
 * depth lookup are held to one convention.
 */
class DepthAlignmentTest {

    // A typical back camera: landscape 640x480 sensor image, saved upright as a 480x640 portrait photo.
    private val rawWidth = 640
    private val rawHeight = 480
    private val photoWidth = rawHeight      // after the 90° rotation
    private val photoHeight = rawWidth

    // 160x120 depth, sensor-oriented, covering the whole camera image (ARCore's common case).
    private val depthWidth = 160
    private val depthHeight = 120
    private val fullCoverage = AffineTransform2D(
        a = 1.0 / rawWidth, b = 0.0, c = 0.0,
        d = 0.0, e = 1.0 / rawHeight, f = 0.0
    )

    private val rawTopLeftMm = 1000
    private val rawTopRightMm = 2000
    private val rawBottomLeftMm = 3000
    private val rawBottomRightMm = 4000

    /** Quadrant-coded depth image IN SENSOR ORIENTATION, the way ARCore delivers it. */
    private val quadrants = DepthMap(
        depthWidth,
        depthHeight,
        ShortArray(depthWidth * depthHeight) { i ->
            val x = i % depthWidth
            val y = i / depthWidth
            val left = x < depthWidth / 2
            val top = y < depthHeight / 2
            when {
                top && left -> rawTopLeftMm
                top -> rawTopRightMm
                left -> rawBottomLeftMm
                else -> rawBottomRightMm
            }.toShort()
        }
    )

    /** The value the photo point ([u], [v]) reads, in mm. */
    private fun readAt(u: Double, v: Double, alignment: DepthAlignment, photoW: Int = photoWidth, photoH: Int = photoHeight): Int {
        val pixel = photoPixelToDepthPixel(u, v, photoW, photoH, alignment, depthWidth, depthHeight)
        assertNotNull("($u, $v) should map inside the depth image", pixel)
        return quadrants.millimetersAt(pixel!!.first, pixel.second)
    }

    // Centres of the four quadrants of the UPRIGHT photo.
    private val uprightTopLeft = photoWidth * 0.25 to photoHeight * 0.25
    private val uprightTopRight = photoWidth * 0.75 to photoHeight * 0.25
    private val uprightBottomLeft = photoWidth * 0.25 to photoHeight * 0.75
    private val uprightBottomRight = photoWidth * 0.75 to photoHeight * 0.75

    @Test
    fun `90 degree photo reads each quadrant from the right sensor quadrant`() {
        val alignment = DepthAlignment(90, fullCoverage)
        assertEquals("upright top-left = raw bottom-left", rawBottomLeftMm,
            readAt(uprightTopLeft.first, uprightTopLeft.second, alignment))
        assertEquals("upright top-right = raw top-left", rawTopLeftMm,
            readAt(uprightTopRight.first, uprightTopRight.second, alignment))
        assertEquals("upright bottom-left = raw bottom-right", rawBottomRightMm,
            readAt(uprightBottomLeft.first, uprightBottomLeft.second, alignment))
        assertEquals("upright bottom-right = raw top-right", rawTopRightMm,
            readAt(uprightBottomRight.first, uprightBottomRight.second, alignment))
    }

    @Test
    fun `the wrong rotation direction reads different quadrants`() {
        // The SAME photo points under 270° land in the diagonally opposite sensor quadrants. If the
        // code ever used the inverse rotation by mistake, the test above would fail with exactly
        // these values — this test proves the two are distinguishable at all.
        val wrongWay = DepthAlignment(270, fullCoverage)
        assertEquals(rawTopRightMm, readAt(uprightTopLeft.first, uprightTopLeft.second, wrongWay))
        assertEquals(rawBottomRightMm, readAt(uprightTopRight.first, uprightTopRight.second, wrongWay))
        assertEquals(rawTopLeftMm, readAt(uprightBottomLeft.first, uprightBottomLeft.second, wrongWay))
        assertEquals(rawBottomLeftMm, readAt(uprightBottomRight.first, uprightBottomRight.second, wrongWay))
    }

    @Test
    fun `a swapped axis would read the wrong quadrant`() {
        // A transpose — x and y exchanged without the flip a rotation needs — is the other classic
        // mistake, and it also swaps dimensions. Simulate it and show the correct mapping disagrees.
        val (u, v) = uprightTopLeft
        val transposed = quadrants.millimetersAt(
            (v / rawWidth * depthWidth).toInt(),
            (u / rawHeight * depthHeight).toInt()
        )
        val correct = readAt(u, v, DepthAlignment(90, fullCoverage))
        assertNotEquals("a transpose must not pass for the real mapping", transposed, correct)
        assertEquals(rawTopLeftMm, transposed)
        assertEquals(rawBottomLeftMm, correct)
    }

    @Test
    fun `the top of the upright photo is the sensor's left edge`() {
        // Physical sanity, independent of the quadrant fixture: with the phone upright and a
        // SENSOR_ORIENTATION of 90, the sky at the top of the photo is the sensor image's x = 0 side.
        val (x, y) = uprightPhotoToRawImagePixel(240.0, 10.0, photoWidth, photoHeight, 90)
        assertEquals(10.0, x, 1e-9)
        assertEquals(240.0, y, 1e-9)
    }

    @Test
    fun `unrotated and upside-down photos map consistently`() {
        // 0°: the photo IS the sensor image.
        val identity = DepthAlignment(0, fullCoverage)
        assertEquals(rawTopLeftMm, readAt(160.0, 120.0, identity, rawWidth, rawHeight))
        assertEquals(rawBottomRightMm, readAt(480.0, 360.0, identity, rawWidth, rawHeight))
        // 180°: everything swaps corner to corner.
        val flipped = DepthAlignment(180, fullCoverage)
        assertEquals(rawBottomRightMm, readAt(160.0, 120.0, flipped, rawWidth, rawHeight))
        assertEquals(rawTopLeftMm, readAt(480.0, 360.0, flipped, rawWidth, rawHeight))
    }

    @Test
    fun `inverse rotation round-trips the forward rotation for every orientation`() {
        // Forward mappings written out independently of the implementation, from the corner rules.
        val forward: Map<Int, (Double, Double) -> Pair<Double, Double>> = mapOf(
            0 to { x, y -> x to y },
            90 to { x, y -> (rawHeight - y) to x },
            180 to { x, y -> (rawWidth - x) to (rawHeight - y) },
            270 to { x, y -> y to (rawWidth - x) }
        )
        val samples = listOf(10.0 to 20.0, 600.0 to 50.0, 33.3 to 470.0, 320.0 to 240.0)
        forward.forEach { (rotation, toUpright) ->
            val (w, h) = if (rotation % 180 == 0) rawWidth to rawHeight else rawHeight to rawWidth
            samples.forEach { (x, y) ->
                val (u, v) = toUpright(x, y)
                val (backX, backY) = uprightPhotoToRawImagePixel(u, v, w, h, rotation)
                assertEquals("x at $rotation°", x, backX, 1e-9)
                assertEquals("y at $rotation°", y, backY, 1e-9)
            }
        }
    }

    @Test
    fun `a cropped depth image maps through ARCore's transform and rejects the cropped margin`() {
        // A device whose depth covers only the middle 75% of the camera image horizontally:
        // s = (x / W - 0.125) / 0.75.
        val crop = AffineTransform2D(
            a = 1.0 / (rawWidth * 0.75), b = 0.0, c = -0.125 / 0.75,
            d = 0.0, e = 1.0 / rawHeight, f = 0.0
        )
        val alignment = DepthAlignment(90, crop)
        // Photo top edge region -> raw x near 0 -> inside the cropped-away margin -> no depth.
        assertNull(photoPixelToDepthPixel(240.0, 20.0, photoWidth, photoHeight, alignment, depthWidth, depthHeight))
        // The photo centre still lands at the depth centre.
        assertEquals(
            depthWidth / 2 to depthHeight / 2,
            photoPixelToDepthPixel(240.0, 320.0, photoWidth, photoHeight, alignment, depthWidth, depthHeight)
        )
    }

    @Test
    fun `points outside the photo have no depth pixel`() {
        val alignment = DepthAlignment(90, fullCoverage)
        assertNull(photoPixelToDepthPixel(-1.0, 10.0, photoWidth, photoHeight, alignment, depthWidth, depthHeight))
        assertNull(photoPixelToDepthPixel(10.0, photoHeight + 1.0, photoWidth, photoHeight, alignment, depthWidth, depthHeight))
    }

    @Test
    fun `the affine fit recovers a known transform from three points and refuses collinear ones`() {
        val truth = AffineTransform2D(0.0015, 0.0001, -0.12, -0.0002, 0.002, 0.05)
        val source = listOf(160.0 to 120.0, 480.0 to 120.0, 160.0 to 360.0)
        val target = source.map { (x, y) -> truth.applyX(x, y) to truth.applyY(x, y) }
        val fitted = AffineTransform2D.fromPointPairs(source, target)!!
        listOf(truth.a to fitted.a, truth.b to fitted.b, truth.c to fitted.c,
            truth.d to fitted.d, truth.e to fitted.e, truth.f to fitted.f
        ).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-12) }
        // And it predicts a fourth point it never saw.
        assertEquals(truth.applyX(480.0, 360.0), fitted.applyX(480.0, 360.0), 1e-12)

        val collinear = listOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0)
        assertNull(AffineTransform2D.fromPointPairs(collinear, target))
    }

    @Test
    fun `the stored transform round-trips through its column text and rejects junk`() {
        val transform = AffineTransform2D(1.5625E-3, 0.0, 0.0, 0.0, 2.0833333333333333E-3, -0.01)
        assertEquals(transform, AffineTransform2D.parse(transform.serialize()))
        assertNull(AffineTransform2D.parse(null))
        assertNull(AffineTransform2D.parse("1,2,3"))
        assertNull(AffineTransform2D.parse("1,2,3,4,5,x"))
        assertNull(AffineTransform2D.parse("1,2,3,4,5,NaN"))
    }
}

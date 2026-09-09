package com.gops.spatialmapper.capture

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [rotateBitmapClockwise] rotates image CONTENT in the correct direction — not just that
 * width and height swap.
 *
 * This is the check the existing pure-Kotlin tests (CanopyFramingTest's uprightImageSize cases)
 * structurally cannot do: those only exercise synthetic width/height numbers, where a rotation
 * applied in the WRONG direction (counterclockwise instead of clockwise) would swap the dimensions
 * identically to a correct one and pass just as easily. Only the resulting pixel content differs
 * between the two directions, which requires an actual [Bitmap]/[android.graphics.Matrix] — not
 * available in local unit tests, since the mockable android.jar stubs them out. Hence this lives in
 * androidTest, run against a real Android graphics stack (device or emulator).
 */
@RunWith(AndroidJUnit4::class)
class ImageRotationInstrumentedTest {

    /** A 4-wide x 2-tall bitmap with a distinct color marking each corner. */
    private fun markedBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        for (x in 0 until 4) {
            for (y in 0 until 2) {
                bitmap.setPixel(x, y, Color.DKGRAY)
            }
        }
        bitmap.setPixel(0, 0, Color.RED)    // top-left
        bitmap.setPixel(3, 0, Color.GREEN)  // top-right
        bitmap.setPixel(0, 1, Color.BLUE)   // bottom-left
        bitmap.setPixel(3, 1, Color.YELLOW) // bottom-right
        return bitmap
    }

    @Test
    fun rotating90DegreesMovesContentClockwiseNotCounterclockwise() {
        val rotated = rotateBitmapClockwise(markedBitmap(), 90)

        // A 4x2 image rotated 90 degrees becomes 2x4 either way round — dimension-swap alone can't
        // distinguish direction, which is exactly the point of this test.
        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)

        // Physically rotating a photo 90 degrees CLOCKWISE: the top edge becomes the right edge, and
        // the left edge becomes the top edge. So the original top-left corner (red) must land at the
        // new TOP-RIGHT corner. A backwards (counterclockwise) rotation would instead put it at the
        // new BOTTOM-LEFT corner — same dimensions, wrong content, exactly the failure mode a
        // dimension-only test cannot catch.
        assertEquals("original top-left (red) should land at new top-right", Color.RED, rotated.getPixel(1, 0))
        assertEquals("original top-right (green) should land at new bottom-right", Color.GREEN, rotated.getPixel(1, 3))
        assertEquals("original bottom-left (blue) should land at new top-left", Color.BLUE, rotated.getPixel(0, 0))
        assertEquals("original bottom-right (yellow) should land at new bottom-left", Color.YELLOW, rotated.getPixel(0, 3))
    }

    @Test
    fun rotating270DegreesIsTheOppositeOf90() {
        // 270 clockwise == 90 counterclockwise: the inverse mapping of the test above. Pinning both
        // directions is what would have caught a sign error in the degrees passed to postRotate.
        val rotated = rotateBitmapClockwise(markedBitmap(), 270)

        assertEquals(2, rotated.width)
        assertEquals(4, rotated.height)
        assertEquals("original top-left (red) should land at new bottom-left", Color.RED, rotated.getPixel(0, 3))
        assertEquals("original top-right (green) should land at new top-left", Color.GREEN, rotated.getPixel(0, 0))
        assertEquals("original bottom-left (blue) should land at new bottom-right", Color.BLUE, rotated.getPixel(1, 3))
        assertEquals("original bottom-right (yellow) should land at new top-right", Color.YELLOW, rotated.getPixel(1, 0))
    }

    @Test
    fun zeroDegreesReturnsTheSameBitmapUntouched() {
        val original = markedBitmap()
        val result = rotateBitmapClockwise(original, 0)
        assertEquals("no rotation should be a true no-op, not a copy", original, result)
    }

    @Test
    fun threeSixtyDegreesNormalizesToANoOp() {
        val original = markedBitmap()
        val result = rotateBitmapClockwise(original, 360)
        assertEquals(original, result)
    }
}

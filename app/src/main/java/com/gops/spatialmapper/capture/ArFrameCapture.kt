package com.gops.spatialmapper.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

private const val TAG = "SpatialMapperCapture"

private const val JPEG_QUALITY = 90

/**
 * Saves the ARCore camera image for [frame] to [file] as JPEG, physically rotated [rotationDegrees]
 * clockwise so the bytes on disk are upright. In ARCore-depth mode CameraX is not running (ARCore owns
 * the camera), so the shutter's photo has to come from the AR frame itself — which, unlike CameraX's
 * ImageCapture, does not apply the device's target rotation on its own.
 *
 * PHYSICAL re-orientation (not EXIF metadata) is deliberate: an EXIF-orientation tag depends on every
 * downstream decoder honoring it, and the app's other photo readers (history thumbnails, the
 * identification screen) do a plain `BitmapFactory.decodeFile` with no EXIF handling. Standardizing on
 * "the pixel bytes are already correct" means every reader is correct by construction instead of by
 * convention.
 *
 * [rotationDegrees] MUST be the same value passed to the matching [com.gops.spatialmapper.capture.cameraIntrinsicsSnapshot]
 * call for this frame — that function reports dimensions swapped to match what this function writes.
 *
 * Best-effort and fully guarded: any failure returns false and is logged, never thrown — the
 * telemetry snapshot (the phase's primary deliverable) is logged by the caller regardless.
 *
 * Must be called from inside onSessionUpdated, where the frame's camera image is valid.
 */
fun saveArFrameAsJpeg(frame: Frame, file: File, rotationDegrees: Int): Boolean {
    val image = try {
        frame.acquireCameraImage()
    } catch (e: NotYetAvailableException) {
        Log.w(TAG, "Camera image not yet available for capture")
        return false
    } catch (e: Exception) {
        Log.w(TAG, "acquireCameraImage failed", e)
        return false
    }

    return image.use { img ->
        try {
            val nv21 = yuv420ToNv21(img)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, img.width, img.height, null)
            val rawJpeg = ByteArrayOutputStream().use { buffer ->
                yuvImage.compressToJpeg(Rect(0, 0, img.width, img.height), JPEG_QUALITY, buffer)
                buffer.toByteArray()
            }

            val normalized = ((rotationDegrees % 360) + 360) % 360
            val savedWidth = if (normalized == 90 || normalized == 270) img.height else img.width
            val savedHeight = if (normalized == 90 || normalized == 270) img.width else img.height
            Log.d(
                TAG,
                "AR frame capture: raw ${img.width}x${img.height}, rotationDegrees=$rotationDegrees " +
                    "(normalized=$normalized, applied clockwise) -> saving ${savedWidth}x${savedHeight}"
            )

            val outputBytes = if (normalized == 0) {
                rawJpeg
            } else {
                rotateJpegBytesClockwise(rawJpeg, normalized) ?: run {
                    Log.w(TAG, "Rotating AR frame failed; saving un-rotated as a last resort")
                    rawJpeg
                }
            }

            FileOutputStream(file).use { out -> out.write(outputBytes) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Encoding AR frame to JPEG failed", e)
            false
        }
    }
}

/** Decodes, rotates, and re-encodes [jpegBytes]. Returns null (never throws) if the decode fails. */
private fun rotateJpegBytesClockwise(jpegBytes: ByteArray, degrees: Int): ByteArray? {
    val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
    val rotated = rotateBitmapClockwise(decoded, degrees)
    return ByteArrayOutputStream().use { buffer ->
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
        rotated.recycle()
        buffer.toByteArray()
    }
}

/** Converts a YUV_420_888 [Image] to an NV21 byte array, honoring row/pixel strides. */
private fun yuv420ToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val out = ByteArray(ySize + ySize / 2)

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    // Luma plane.
    var pos = 0
    if (yPlane.pixelStride == 1 && yPlane.rowStride == width) {
        yBuffer.get(out, 0, ySize)
        pos = ySize
    } else {
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                out[pos++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }
    }

    // Chroma planes, interleaved as V,U for NV21 (2x2 subsampled).
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    for (row in 0 until chromaHeight) {
        val uRowStart = row * uPlane.rowStride
        val vRowStart = row * vPlane.rowStride
        for (col in 0 until chromaWidth) {
            out[pos++] = vBuffer.get(vRowStart + col * vPlane.pixelStride)
            out[pos++] = uBuffer.get(uRowStart + col * uPlane.pixelStride)
        }
    }
    return out
}

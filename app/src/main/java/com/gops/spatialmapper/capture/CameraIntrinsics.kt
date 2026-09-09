package com.gops.spatialmapper.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.google.ar.core.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val TAG = "SpatialMapperIntrinsics"

/**
 * Rotates [bitmap] clockwise by [degrees] (normalized to [0, 360)). Returns [bitmap] itself,
 * unrecycled, when no rotation is needed.
 *
 * The one shared implementation of "make this image upright" in the app — used both when a captured
 * JPEG is physically re-oriented at save time ([com.gops.spatialmapper.capture.saveArFrameAsJpeg]) and
 * when a photo is decoded for display. A rotation-direction bug fixed in one copy and left in a second
 * is exactly how this class of bug survives; there must only be one copy.
 */
fun rotateBitmapClockwise(bitmap: Bitmap, degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return bitmap
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/**
 * The camera model for a single capture: focal length in PIXELS plus the pixel dimensions those
 * pixels refer to.
 *
 * Focal length in pixels is what the pinhole conversion needs (see
 * [com.gops.spatialmapper.measure.canopyDiameterMeters]); it is meaningless without the image
 * dimensions it was measured against, so the two always travel together.
 *
 * @param focalLengthPixels horizontal focal length, in pixels of a [imageWidthPixels] x
 *   [imageHeightPixels] image. Phone sensors have square pixels, so fx ≈ fy and this single value is
 *   valid for measurements along either axis.
 * @param rotationDegrees clockwise rotation that must be applied to the saved JPEG to make it
 *   upright (world-up towards the top of the image). Rotation does not change the focal length —
 *   it only swaps which image axis is which — but the framing screen needs an upright image so that
 *   the ellipse's horizontal diameter means horizontal in the world.
 */
data class CameraIntrinsicsSnapshot(
    val focalLengthPixels: Float,
    val imageWidthPixels: Int,
    val imageHeightPixels: Int,
    val rotationDegrees: Int
) {
    /** Longest side in pixels — the rotation-invariant handle used to rescale mask measurements. */
    val longestSidePixels: Int get() = max(imageWidthPixels, imageHeightPixels)
}

/**
 * Reads the intrinsics of the CPU image backing this AR [Frame].
 *
 * `Frame.camera.imageIntrinsics` describes exactly the image `acquireCameraImage()` returns.
 * (`textureIntrinsics` would describe the GPU texture instead, which is a different resolution; using
 * it here would silently scale every measurement.)
 *
 * [rotationDegrees] must be the SAME value passed to [saveArFrameAsJpeg] for this frame — that
 * function physically rotates the JPEG's pixel data by this amount before writing it to disk, so the
 * dimensions reported here are swapped to match the file AS SAVED (post-rotation), and the returned
 * snapshot's own `rotationDegrees` is always 0: nothing downstream owes this image any further
 * rotation. Previously this reported ARCore's raw (pre-rotation) dimensions with the pending rotation
 * left for consumers to apply — which meant a screen that forgot to apply it (or a fallback path that
 * assumed 0 for an unrelated reason) would silently swap width and height. Standardizing on
 * "the file on disk is always upright" removes that whole class of bug rather than requiring every
 * consumer to get it right.
 *
 * MUST be called from inside `onSessionUpdated`, where the frame is valid. Returns null on any
 * failure rather than throwing — a capture without intrinsics is gated later, not crashed on.
 */
fun Frame.cameraIntrinsicsSnapshot(rotationDegrees: Int): CameraIntrinsicsSnapshot? = try {
    val intrinsics = camera.imageIntrinsics
    val focalLength = intrinsics.focalLength       // [fx, fy] in pixels
    val dimensions = intrinsics.imageDimensions    // [width, height] in pixels, PRE-rotation
    if (focalLength.size < 2 || dimensions.size < 2 || focalLength[0] <= 0f) {
        Log.w(TAG, "ARCore returned unusable intrinsics")
        null
    } else {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        val (width, height) = if (normalized == 90 || normalized == 270) {
            dimensions[1] to dimensions[0]
        } else {
            dimensions[0] to dimensions[1]
        }
        Log.d(
            TAG,
            "AR intrinsics: raw ${dimensions[0]}x${dimensions[1]}, rotationDegrees=$rotationDegrees " +
                "(normalized=$normalized) -> reporting ${width}x${height} @ rotationDegrees=0"
        )
        CameraIntrinsicsSnapshot(
            focalLengthPixels = focalLength[0],
            imageWidthPixels = width,
            imageHeightPixels = height,
            rotationDegrees = 0
        )
    }
} catch (e: Exception) {
    Log.w(TAG, "Reading ARCore camera intrinsics failed", e)
    null
}

/**
 * Camera2 fallback for the non-ARCore capture path, where there is no AR frame to ask.
 *
 * Derives focal length in pixels from the lens focal length and the physical sensor size:
 *
 *     focalPixels = focalLengthMm * longestImageSidePx / longestSensorSideMm
 *
 * Both ratios are taken along the sensor's long axis, which is the axis the image's long side always
 * maps to regardless of how the JPEG was rotated for display. Because phone pixels are square, the
 * resulting value applies to both image axes.
 *
 * [imageWidthPixels]/[imageHeightPixels] are the dimensions of the JPEG that was actually saved.
 * Returns null if the device doesn't report the characteristics we need.
 */
fun backCameraIntrinsics(
    context: Context,
    imageWidthPixels: Int,
    imageHeightPixels: Int
): CameraIntrinsicsSnapshot? {
    if (imageWidthPixels <= 0 || imageHeightPixels <= 0) return null
    val characteristics = backCameraCharacteristics(context) ?: return null
    return try {
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        if (physicalSize == null || focalLengths == null || focalLengths.isEmpty()) {
            Log.w(TAG, "Back camera reports no physical size / focal lengths")
            return null
        }
        val sensorLongMm = max(physicalSize.width, physicalSize.height)
        val focalLengthMm = focalLengths[0]
        if (sensorLongMm <= 0f || focalLengthMm <= 0f) return null

        val longestImageSide = max(imageWidthPixels, imageHeightPixels)
        CameraIntrinsicsSnapshot(
            focalLengthPixels = focalLengthMm * longestImageSide / sensorLongMm,
            imageWidthPixels = imageWidthPixels,
            imageHeightPixels = imageHeightPixels,
            // Every capture path writes its JPEG bytes already rotated upright before this function
            // ever runs: CameraX's ImageCapture applies the target rotation itself, and the AR path
            // physically rotates in saveArFrameAsJpeg (see cameraIntrinsicsSnapshot's doc). This
            // function is also reached as the AR path's fallback when ARCore's own intrinsics read
            // fails for a frame — rotationDegrees = 0 must stay correct for BOTH origins, not just
            // CameraX's, or this silently reintroduces the sideways-photo bug it once caused.
            rotationDegrees = 0
        )
    } catch (e: Exception) {
        Log.w(TAG, "Reading Camera2 intrinsics failed", e)
        null
    }
}

/**
 * Resolves intrinsics for a JPEG written by the CameraX fallback path, where there was no AR frame to
 * read them from. Reads only the JPEG header for its dimensions (no full decode), off the main
 * thread. Returns null if the file or the camera characteristics are unusable.
 */
suspend fun fallbackIntrinsicsForPhoto(
    context: Context,
    photoPath: String
): CameraIntrinsicsSnapshot? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(photoPath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        Log.w(TAG, "Couldn't read dimensions of $photoPath")
        return@withContext null
    }
    backCameraIntrinsics(context, bounds.outWidth, bounds.outHeight)
}

/**
 * SENSOR_ORIENTATION of the back camera: the clockwise rotation needed to bring the raw sensor image
 * upright when the device is held in its natural (portrait) orientation. Almost always 90 on phones.
 *
 * The AR path's raw sensor image is physically rotated by this amount before it is written to disk
 * (see [saveArFrameAsJpeg]). This value is only correct while the device IS in its natural
 * orientation at capture time — the app is locked to portrait (see AndroidManifest.xml) specifically
 * so this constant stays valid; there is no runtime check for the device's current rotation.
 * Defaults to 90 if unavailable, which is the near-universal value for a phone back camera.
 */
fun backCameraSensorOrientation(context: Context): Int {
    val characteristics = backCameraCharacteristics(context) ?: return DEFAULT_SENSOR_ORIENTATION
    val value = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: DEFAULT_SENSOR_ORIENTATION
    Log.d(TAG, "Back camera SENSOR_ORIENTATION = $value°")
    return value
}

private const val DEFAULT_SENSOR_ORIENTATION = 90

private fun backCameraCharacteristics(context: Context): CameraCharacteristics? {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    return try {
        cameraManager.cameraIdList
            .map { cameraManager.getCameraCharacteristics(it) }
            .firstOrNull { it.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
    } catch (e: Exception) {
        Log.w(TAG, "Enumerating cameras failed", e)
        null
    }
}

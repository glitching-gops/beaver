package com.gops.spatialmapper.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.google.ar.core.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

private const val TAG = "SpatialMapperIntrinsics"

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
 * `Frame.camera.imageIntrinsics` describes exactly the image `acquireCameraImage()` returns — which
 * is the image [saveArFrameAsJpeg] writes to disk — so the focal length and the JPEG are guaranteed
 * to be in the same pixel space. (`textureIntrinsics` would describe the GPU texture instead, which
 * is a different resolution; using it here would silently scale every measurement.)
 *
 * MUST be called from inside `onSessionUpdated`, where the frame is valid. Returns null on any
 * failure rather than throwing — a capture without intrinsics is gated later, not crashed on.
 */
fun Frame.cameraIntrinsicsSnapshot(rotationDegrees: Int): CameraIntrinsicsSnapshot? = try {
    val intrinsics = camera.imageIntrinsics
    val focalLength = intrinsics.focalLength       // [fx, fy] in pixels
    val dimensions = intrinsics.imageDimensions    // [width, height] in pixels
    if (focalLength.size < 2 || dimensions.size < 2 || focalLength[0] <= 0f) {
        Log.w(TAG, "ARCore returned unusable intrinsics")
        null
    } else {
        CameraIntrinsicsSnapshot(
            focalLengthPixels = focalLength[0],
            imageWidthPixels = dimensions[0],
            imageHeightPixels = dimensions[1],
            rotationDegrees = rotationDegrees
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
            // CameraX's ImageCapture already writes the JPEG upright (it applies the target
            // rotation), so nothing further is needed on this path.
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
 * The AR path saves the raw sensor image without rotating it, so this is the rotation the framing
 * screen has to apply before "horizontal" means anything in world terms. Defaults to 90 if unavailable,
 * which is the near-universal value for a phone back camera.
 */
fun backCameraSensorOrientation(context: Context): Int {
    val characteristics = backCameraCharacteristics(context) ?: return DEFAULT_SENSOR_ORIENTATION
    return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: DEFAULT_SENSOR_ORIENTATION
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

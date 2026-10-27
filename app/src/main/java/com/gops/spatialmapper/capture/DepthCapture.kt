package com.gops.spatialmapper.capture

import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.math.abs

private const val TAG = "SpatialMapperDepth"

/**
 * Where a raw CPU-image point must land, in texture-normalized units, for the sampled transform to be
 * accepted as affine. Generous (a tenth of a depth pixel at 160 px) — a real failure is off by far
 * more than this, a float rounding never is.
 */
private const val AFFINE_CHECK_TOLERANCE = 1e-3

/** The depth image saved for one capture, and what's needed to look photo pixels up in it. */
data class SavedDepthImage(
    val path: String,
    val width: Int,
    val height: Int,
    val alignment: DepthAlignment,
    val fileBytes: Int
)

/**
 * Saves [frame]'s 16-bit depth image to [file] (the format in DepthMap.kt) alongside the photo, plus
 * the alignment that maps photo pixels into it. Returns null — never throws — if there is no depth
 * for this frame or ARCore's coordinate transform can't be captured; the cluster members then fall
 * back to ground-plane geometry, which is also what a non-depth device does.
 *
 * This is IN ADDITION to the centre reading, not instead of it: the centre tree's distance still
 * comes from [com.gops.spatialmapper.distance.depthAtCenterMeters] exactly as before.
 *
 * [rotationDegrees] is the clockwise rotation the photo from this same frame is saved with
 * (saveArFrameAsJpeg). Must be called inside onSessionUpdated, where [frame] is valid.
 */
fun saveDepthImage(frame: Frame, file: File, rotationDegrees: Int): SavedDepthImage? {
    val transform = try {
        cpuToTextureNormalizedTransform(frame)
    } catch (e: Exception) {
        Log.w(TAG, "Sampling ARCore's image->texture transform failed", e)
        null
    } ?: return null

    val map = try {
        frame.acquireDepthImage16Bits()
    } catch (e: NotYetAvailableException) {
        Log.w(TAG, "No depth image for the shutter frame yet; members will use the fallback")
        return null
    } catch (e: Exception) {
        Log.w(TAG, "acquireDepthImage16Bits failed", e)
        return null
    }.use { image ->
        val plane = image.planes[0]
        // ARCore documents the plane as little-endian; say so rather than rely on the platform.
        val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(image.width * image.height)
        for (y in 0 until image.height) {
            val rowStart = y * plane.rowStride
            for (x in 0 until image.width) {
                samples[y * image.width + x] = buffer.getShort(rowStart + x * plane.pixelStride)
            }
        }
        DepthMap(image.width, image.height, samples)
    }

    return try {
        val bytes = encodeDepthMap(map)
        FileOutputStream(file).use { it.write(bytes) }
        // The per-capture size, for the record: raw is width*height*2 bytes.
        Log.i(
            TAG,
            "Saved depth ${map.width}x${map.height} to ${file.name}: ${bytes.size} bytes " +
                "(raw ${map.width * map.height * 2}), transform=${transform.serialize()}"
        )
        SavedDepthImage(
            path = file.absolutePath,
            width = map.width,
            height = map.height,
            alignment = DepthAlignment(rotationDegrees, transform),
            fileBytes = bytes.size
        )
    } catch (e: Exception) {
        Log.w(TAG, "Writing the depth image failed", e)
        null
    }
}

/**
 * ARCore's IMAGE_PIXELS -> TEXTURE_NORMALIZED map for this frame, recovered as an affine transform
 * from three sample points and checked against a fourth.
 *
 * The points sit well inside the image (25% / 75%) so they are inside the depth image's coverage
 * even on a device where it is a crop: ARCore reports points in the cropped-away margin as negative,
 * and fitting through those would be fitting through a sentinel.
 */
private fun cpuToTextureNormalizedTransform(frame: Frame): AffineTransform2D? {
    val dimensions = frame.camera.imageIntrinsics.imageDimensions   // raw CPU image, [w, h]
    if (dimensions.size < 2 || dimensions[0] <= 0 || dimensions[1] <= 0) return null
    val w = dimensions[0].toFloat()
    val h = dimensions[1].toFloat()

    val input = floatArrayOf(
        0.25f * w, 0.25f * h,
        0.75f * w, 0.25f * h,
        0.25f * w, 0.75f * h,
        0.75f * w, 0.75f * h     // check point
    )
    val output = FloatArray(input.size)
    frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, input, Coordinates2d.TEXTURE_NORMALIZED, output)
    if (output.any { it < 0f || it > 1f || !it.isFinite() }) {
        Log.w(TAG, "Texture coordinates out of range: ${output.joinToString()}")
        return null
    }

    fun point(values: FloatArray, i: Int) = values[2 * i].toDouble() to values[2 * i + 1].toDouble()
    val transform = AffineTransform2D.fromPointPairs(
        source = (0..2).map { point(input, it) },
        target = (0..2).map { point(output, it) }
    ) ?: return null

    val (checkX, checkY) = point(input, 3)
    val (expectedS, expectedT) = point(output, 3)
    if (abs(transform.applyX(checkX, checkY) - expectedS) > AFFINE_CHECK_TOLERANCE ||
        abs(transform.applyY(checkX, checkY) - expectedT) > AFFINE_CHECK_TOLERANCE
    ) {
        Log.w(TAG, "ARCore's image->texture transform is not affine on this device; not saving depth")
        return null
    }
    return transform
}

/** The depth file that goes with [photoFile]: same name, [DEPTH_FILE_EXTENSION] instead of .jpg. */
fun depthFileFor(photoFile: File): File =
    File(photoFile.parentFile, photoFile.nameWithoutExtension + "." + DEPTH_FILE_EXTENSION)

/** Reads a saved depth file; null for a missing or unreadable one. Blocking — call off-main. */
fun loadDepthMap(path: String?): DepthMap? {
    if (path.isNullOrBlank()) return null
    return runCatching { decodeDepthMap(File(path).readBytes()) }
        .onFailure { Log.w(TAG, "Couldn't read depth file $path", it) }
        .getOrNull()
}

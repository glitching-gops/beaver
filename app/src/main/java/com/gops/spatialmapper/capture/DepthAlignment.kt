package com.gops.spatialmapper.capture

import kotlin.math.abs
import kotlin.math.floor

/**
 * How a pixel of the UPRIGHT saved photo finds its pixel in the saved depth image.
 *
 * Two different coordinate systems, and the two halves of the mapping come from two different
 * places — which is the whole reason this is written down rather than inlined:
 *
 *  1. Upright photo -> raw camera (CPU) image: undoing OUR OWN rotation. The AR path physically
 *     rotates the raw sensor image [rotationDegrees] clockwise before writing the JPEG
 *     (saveArFrameAsJpeg + rotateBitmapClockwise). The depth image is never rotated, so a photo
 *     point has to be taken back into the sensor's orientation first. That rotation is ours, so the
 *     inverse is ours too: [uprightPhotoToRawImagePixel].
 *
 *  2. Raw camera image -> depth image: ARCORE'S transform. The depth image is not just a scaled copy
 *     of the CPU image — on some devices it is a crop of it with a different aspect. ARCore exposes
 *     this as `Frame.transformCoordinates2d(IMAGE_PIXELS -> TEXTURE_NORMALIZED)`, and the depth image
 *     is aligned to TEXTURE_NORMALIZED (ARCore developer guide, "Converting coordinates between camera
 *     images and depth images"). That API needs a live Frame, and framing happens after the frame is
 *     gone, so at shutter time the transform is SAMPLED at three points and stored as the exact affine
 *     map it is ([cpuToTextureNormalized]); see DepthCapture.kt.
 *
 * Why ARCore's API for half 2 rather than "depth = CPU image scaled by width ratio": hand-derived
 * scaling is exactly right on devices where the two share a field of view and silently wrong (a
 * shifted lookup, not a crash) on devices where they don't. The API knows which device it is on.
 * Half 1 cannot come from ARCore, because ARCore never sees our rotation.
 *
 * @param rotationDegrees clockwise rotation applied to the raw image to produce the saved photo
 *   (the back camera's SENSOR_ORIENTATION; 90 on almost every phone).
 * @param cpuToTextureNormalized raw CPU-image pixels (x, y) -> texture-normalized (s, t) in [0, 1],
 *   which times the depth image's size is a depth pixel.
 */
data class DepthAlignment(
    val rotationDegrees: Int,
    val cpuToTextureNormalized: AffineTransform2D
)

/**
 * x' = a·x + b·y + c,  y' = d·x + e·y + f.
 *
 * Between ARCore's IMAGE_PIXELS and TEXTURE_NORMALIZED this is in practice a per-axis scale plus an
 * offset (both are in the sensor's orientation), but the general form costs nothing and means a
 * device that also flips an axis is handled rather than assumed away.
 */
data class AffineTransform2D(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double
) {
    fun applyX(x: Double, y: Double): Double = a * x + b * y + c
    fun applyY(x: Double, y: Double): Double = d * x + e * y + f

    /** "a,b,c,d,e,f" — the form stored in sessions.depthTransform. Locale-independent toString. */
    fun serialize(): String = listOf(a, b, c, d, e, f).joinToString(",")

    companion object {
        /** Inverse of [serialize]; null for anything that isn't exactly six finite numbers. */
        fun parse(text: String?): AffineTransform2D? {
            val parts = text?.split(",")?.map { it.trim().toDoubleOrNull() } ?: return null
            if (parts.size != 6 || parts.any { it == null || !it.isFinite() }) return null
            val v = parts.map { it!! }
            return AffineTransform2D(v[0], v[1], v[2], v[3], v[4], v[5])
        }

        /**
         * The unique affine map taking the three [source] points onto the three [target] points.
         * Returns null when the source points are collinear (no unique answer).
         *
         * Used at shutter time to recover ARCore's IMAGE_PIXELS -> TEXTURE_NORMALIZED transform from
         * three transformCoordinates2d samples.
         */
        fun fromPointPairs(
            source: List<Pair<Double, Double>>,
            target: List<Pair<Double, Double>>
        ): AffineTransform2D? {
            require(source.size == 3 && target.size == 3) { "need exactly three point pairs" }
            val (x0, y0) = source[0]
            val (x1, y1) = source[1]
            val (x2, y2) = source[2]
            val det = x0 * (y1 - y2) - y0 * (x1 - x2) + (x1 * y2 - x2 * y1)
            if (abs(det) < 1e-12) return null

            // Cramer's rule, once per output coordinate.
            fun solve(v0: Double, v1: Double, v2: Double): Triple<Double, Double, Double> {
                val p = (v0 * (y1 - y2) - y0 * (v1 - v2) + (v1 * y2 - v2 * y1)) / det
                val q = (x0 * (v1 - v2) - v0 * (x1 - x2) + (x1 * v2 - x2 * v1)) / det
                val r = (x0 * (y1 * v2 - y2 * v1) - y0 * (x1 * v2 - x2 * v1) + v0 * (x1 * y2 - x2 * y1)) / det
                return Triple(p, q, r)
            }
            val (a, b, c) = solve(target[0].first, target[1].first, target[2].first)
            val (d, e, f) = solve(target[0].second, target[1].second, target[2].second)
            return AffineTransform2D(a, b, c, d, e, f)
        }
    }
}

/**
 * Inverse of the clockwise rotation the photo received: a point ([u], [v]) in the upright photo of
 * size [photoWidth] x [photoHeight] -> the same scene point in the RAW camera image, as (x, y).
 *
 * Continuous coordinates (a pixel's centre is at +0.5), so no off-by-one creeps in at the edges.
 *
 * The forward mapping, which this inverts, is the one the bitmap rotation test pins down
 * (ImageRotationInstrumentedTest): rotating 90° clockwise puts the raw TOP-LEFT corner at the
 * upright TOP-RIGHT corner, i.e. u = rawHeight − y, v = x. So for 90°: x = v, y = photoWidth − u.
 */
fun uprightPhotoToRawImagePixel(
    u: Double,
    v: Double,
    photoWidth: Int,
    photoHeight: Int,
    rotationDegrees: Int
): Pair<Double, Double> = when (((rotationDegrees % 360) + 360) % 360) {
    0 -> u to v
    90 -> v to (photoWidth - u)
    180 -> (photoWidth - u) to (photoHeight - v)
    270 -> (photoHeight - v) to u
    else -> throw IllegalArgumentException("rotation must be a multiple of 90°, got $rotationDegrees")
}

/**
 * Upright-photo pixel ([u], [v]) -> integer depth pixel (x, y), or null when the point falls in a
 * part of the camera image the depth image doesn't cover (ARCore's crop) or outside the photo.
 *
 * [photoWidth]/[photoHeight] are the saved (upright) photo's size — the same pixel space as
 * CameraIntrinsicsSnapshot's image dimensions on the AR path.
 */
fun photoPixelToDepthPixel(
    u: Double,
    v: Double,
    photoWidth: Int,
    photoHeight: Int,
    alignment: DepthAlignment,
    depthWidth: Int,
    depthHeight: Int
): Pair<Int, Int>? {
    if (u < 0.0 || v < 0.0 || u > photoWidth || v > photoHeight) return null
    val (x, y) = uprightPhotoToRawImagePixel(u, v, photoWidth, photoHeight, alignment.rotationDegrees)
    val s = alignment.cpuToTextureNormalized.applyX(x, y)
    val t = alignment.cpuToTextureNormalized.applyY(x, y)
    if (s !in 0.0..1.0 || t !in 0.0..1.0) return null
    // floor, and the top edge (s == 1.0) clamped into the last column rather than one past it.
    val depthX = floor(s * depthWidth).toInt().coerceAtMost(depthWidth - 1)
    val depthY = floor(t * depthHeight).toInt().coerceAtMost(depthHeight - 1)
    return depthX to depthY
}

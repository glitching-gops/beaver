package com.gops.spatialmapper.measure

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan

/**
 * Geometry for trees that are NOT at the centre of the photo — the extra members of a cluster.
 *
 * The centre tree's path is untouched by everything here: it is still sized and placed from the
 * single reading taken at the optical axis, where every angle below is zero and every function
 * reduces to the identity. These exist because a tree off to the side is (a) in a different
 * direction and (b) at a different distance along its own line of sight than along the axis.
 *
 * Pure and Android-free on purpose; see ClusterGeometryTest. The direction tests in there exist
 * because this project once shipped a heading that was off by exactly 180°.
 */

/**
 * Horizontal angle between the optical axis and the ray through [pixelX], in degrees. Positive
 * RIGHT of the image centre, negative left.
 *
 * Uses the image centre as the principal point (the app stores only the focal length), so this is
 * atan(dx / f) with dx = pixelX − width/2, in the upright photo's pixels — the same pixel space the
 * focal length is expressed in.
 */
fun horizontalOffAxisAngleDegrees(pixelX: Double, imageWidthPixels: Int, focalLengthPixels: Double): Double {
    require(focalLengthPixels > 0.0) { "focal length must be positive, got $focalLengthPixels" }
    val dx = pixelX - imageWidthPixels / 2.0
    return Math.toDegrees(atan(dx / focalLengthPixels))
}

/**
 * Compass bearing to a tree at [pixelX] in a photo taken facing [captureHeadingDegrees].
 *
 * DIRECTION: compass bearings grow CLOCKWISE seen from above (N=0, E=90). With the phone upright
 * and the camera facing the tree, the right-hand side of the photo is clockwise of where the camera
 * points, so a tree RIGHT of centre gets a LARGER bearing. Normalized to [0, 360).
 */
fun bearingToPixelDegrees(
    captureHeadingDegrees: Double,
    pixelX: Double,
    imageWidthPixels: Int,
    focalLengthPixels: Double
): Double {
    val bearing = captureHeadingDegrees +
        horizontalOffAxisAngleDegrees(pixelX, imageWidthPixels, focalLengthPixels)
    return ((bearing % 360.0) + 360.0) % 360.0
}

/*
 * WHY SIZING AND LOCATING USE DIFFERENT DISTANCES
 *
 * ARCore depth is the distance from the CAMERA PLANE (along the forward axis, "Z"), not the length
 * of the ray to the pixel. For a point off to the side at angle θ, the ray is longer: Z / cos θ.
 *
 *  - The pinhole size formula (diameter = pixelSpan × Z / f) is derived for an object at forward
 *    depth Z — its similar triangles are measured along the axis — so it takes Z as it comes.
 *    Feeding it the longer ray length would over-size every off-centre tree by 1 / cos θ.
 *
 *  - Placing the tree on the map is the opposite: SphericalUtil.computeOffset walks a distance
 *    ALONG the tree's own bearing, which is the ray, so it needs Z / cos θ. Feeding it Z would pull
 *    every off-centre tree towards the camera.
 *
 * Only the horizontal angle goes into the cosine: the vertical offset of a crown in the photo is
 * height above the ground, not extra distance across it, and the map is the ground.
 */

/** The distance the pinhole sizing formula expects: the axis depth itself. See the note above. */
fun sizingDepthMeters(axisDepthMeters: Double): Double = axisDepthMeters

/**
 * Distance along the tree's own line of sight, for map placement: axis depth / cos(θ), where θ is
 * the horizontal off-axis angle. See the note above for why this differs from [sizingDepthMeters].
 */
fun lineOfSightRangeMeters(axisDepthMeters: Double, horizontalOffAxisDegrees: Double): Double {
    val cosine = cos(Math.toRadians(horizontalOffAxisDegrees))
    require(cosine > 0.0) { "off-axis angle must be inside ±90°, got $horizontalOffAxisDegrees" }
    return axisDepthMeters / cosine
}

/** Same bounds as the centre fallback (DistanceEstimation.groundPlaneDistanceMeters). */
private const val MIN_DEPRESSION_DEGREES = 1.0
private const val MAX_DEPRESSION_DEGREES = 89.0

/**
 * Ground-plane distance to the point at image row [pixelY] — a tree's TRUNK BASE, i.e. the bottom
 * edge of its framed ellipse — for when there is no usable depth there.
 *
 * The same model as the centre tree's fallback (camera [cameraHeightMeters] above flat ground,
 * distance = height / tan(depression), depression = 90° − |pitch|), with one addition: a row BELOW
 * the image centre looks further down than the optical axis by atan(dy / f), so its depression is
 * larger and the ground it hits is NEARER. A row above centre looks less steeply down and hits
 * ground farther away — or none at all if that puts it at or above the horizon, which returns null.
 *
 * At pixelY == centre this is exactly groundPlaneDistanceMeters. Like that function, the result is
 * a forward distance and is used as the axis depth; and like it, it assumes the trunk stands on flat
 * ground level with the operator's feet. On a slope it degrades — inherent to the method.
 */
fun groundPlaneDistanceAtRowMeters(
    cameraHeightMeters: Double,
    pitchDegrees: Double?,
    pixelY: Double,
    imageHeightPixels: Int,
    focalLengthPixels: Double
): Double? {
    if (pitchDegrees == null || cameraHeightMeters <= 0.0 || focalLengthPixels <= 0.0) return null
    val dy = pixelY - imageHeightPixels / 2.0
    val depression = (90.0 - abs(pitchDegrees)) + Math.toDegrees(atan(dy / focalLengthPixels))
    if (depression <= MIN_DEPRESSION_DEGREES || depression >= MAX_DEPRESSION_DEGREES) return null
    val distance = cameraHeightMeters / tan(Math.toRadians(depression))
    return if (distance.isFinite() && distance > 0.0) distance else null
}

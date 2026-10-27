package com.gops.spatialmapper.measure

import com.gops.spatialmapper.capture.DepthAlignment
import com.gops.spatialmapper.capture.DepthMap
import com.gops.spatialmapper.capture.medianDepthMeters
import com.gops.spatialmapper.capture.photoPixelToDepthPixel
import com.gops.spatialmapper.telemetry.DistanceMethod
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The model behind framing a CLUSTER: one capture, several trees, one ellipse each.
 *
 * Tree numbering is the same everywhere the operator sees it — framing labels, the review screen,
 * session detail, the export's member_index: the centre tree is tree [CENTRE_TREE_NUMBER] and the
 * extra members are 2..N in the order they were added. A capture with no extra ellipses is a
 * cluster of one, which is exactly what every capture was before clusters existed.
 */

/** The centre tree — the original ellipse, measured from the shutter-time centre reading. */
const val CENTRE_TREE_NUMBER = 1

/**
 * Most trees one capture may hold, centre included.
 *
 * Ten, because past that the capture stops being the right tool rather than the cap being in the way:
 * ARCore depth is reliable to ~15–25 m and the framing photo is a phone screen wide, so a frame with
 * more than about ten separately resolvable crowns is either a stand better recorded from several
 * positions or a set of ellipses too small to frame honestly. It also bounds the framing overlay's
 * numbered labels and the review screen's per-tree pins to something that stays legible.
 */
const val MAX_CLUSTER_TREES = 10

/** A new member's starting diameter, as a fraction of the photo's shorter side — smaller than the
 * centre tree's [DEFAULT_ELLIPSE_DIAMETER_FRACTION], since neighbours are usually farther or smaller
 * and a big default would land on top of the trees already framed. */
const val NEW_MEMBER_DIAMETER_FRACTION = 1f / 5f

/** Candidate positions per axis when placing a new ellipse. */
private const val PLACEMENT_GRID_STEPS = 9

/**
 * A sensible spot for a new member ellipse in a photo drawn at [width] x [height] display pixels,
 * given the ellipses already framed.
 *
 * Scans a grid of candidate centres and picks the one farthest (relative to the ellipses' sizes)
 * from every existing ellipse, ties going to the candidate nearest the photo centre — that is where
 * the operator's attention already is. Candidates keep the right-hand and top handles inside the
 * photo, the same reachability rule the drag clamping enforces.
 */
fun placeNewMemberEllipse(existing: List<CanopyEllipse>, width: Float, height: Float): CanopyEllipse {
    val radius = max(1f, min(width, height) * NEW_MEMBER_DIAMETER_FRACTION / 2f)
    val minX = min(radius, width / 2f)
    val maxX = max(minX, width - radius)
    val minY = min(radius, height / 2f)
    val maxY = max(minY, height - radius)

    var best: CanopyEllipse? = null
    var bestScore = Float.NEGATIVE_INFINITY
    var bestCentreDistance = Float.POSITIVE_INFINITY
    for (i in 0 until PLACEMENT_GRID_STEPS) {
        for (j in 0 until PLACEMENT_GRID_STEPS) {
            val cx = minX + (maxX - minX) * i / (PLACEMENT_GRID_STEPS - 1)
            val cy = minY + (maxY - minY) * j / (PLACEMENT_GRID_STEPS - 1)
            val score = existing.minOfOrNull { other ->
                hypot(cx - other.centerX, cy - other.centerY) /
                    (radius + max(other.radiusX, other.radiusY))
            } ?: Float.POSITIVE_INFINITY
            val centreDistance = hypot(cx - width / 2f, cy - height / 2f)
            if (score > bestScore || (score == bestScore && centreDistance < bestCentreDistance)) {
                best = CanopyEllipse(cx, cy, radius, radius)
                bestScore = score
                bestCentreDistance = centreDistance
            }
        }
    }
    return best ?: CanopyEllipse(width / 2f, height / 2f, radius, radius)
}

/** Whether [x], [y] (display pixels) lies inside [ellipse]. */
fun CanopyEllipse.contains(x: Float, y: Float): Boolean {
    if (radiusX <= 0f || radiusY <= 0f) return false
    val nx = (x - centerX) / radiusX
    val ny = (y - centerY) / radiusY
    return nx * nx + ny * ny <= 1f
}

/**
 * An ellipse in CAPTURED-PHOTO pixels (the upright saved JPEG, the space the focal length is in).
 * This is what gets persisted, so a member's framing can be redrawn on its photo later at any size.
 */
data class PhotoEllipse(
    val centerX: Double,
    val centerY: Double,
    val radiusX: Double,
    val radiusY: Double
) {
    /** The bottom edge: where the trunk meets the ground, for the ground-plane fallback. */
    val bottomY: Double get() = centerY + radiusY
}

fun CanopyEllipse.toPhotoPixels(displayToImageScale: Float): PhotoEllipse = PhotoEllipse(
    centerX = centerX.toDouble() * displayToImageScale,
    centerY = centerY.toDouble() * displayToImageScale,
    radiusX = radiusX.toDouble() * displayToImageScale,
    radiusY = radiusY.toDouble() * displayToImageScale
)

/** A distance reading and the method that produced it — the two always travel together. */
data class TreeDistance(val meters: Double, val method: DistanceMethod) {
    /** True when the reading is an estimate rather than a measurement, and the UI should say so. */
    val isFallback: Boolean get() = method == DistanceMethod.GEOMETRY_FALLBACK
}

/** What a member's distance is resolved from. Everything is optional: a non-depth device has no map. */
data class MemberDistanceInputs(
    val photoWidthPixels: Int,
    val photoHeightPixels: Int,
    val focalLengthPixels: Double,
    val depthMap: DepthMap?,
    val depthAlignment: DepthAlignment?,
    val pitchDegrees: Double?,
    val cameraHeightMeters: Double
)

/**
 * A member tree's axis distance: the median of valid depth around its ellipse centre if the saved
 * depth image has any there, otherwise the ground-plane estimate at its trunk base (the ellipse's
 * bottom edge). Null when neither works — no depth there AND the trunk base is at/above the horizon
 * or pitch is unknown — and the operator has to move or delete that ellipse.
 */
fun resolveMemberDistance(ellipse: PhotoEllipse, inputs: MemberDistanceInputs): TreeDistance? {
    val map = inputs.depthMap
    val alignment = inputs.depthAlignment
    if (map != null && alignment != null) {
        val depthPixel = photoPixelToDepthPixel(
            u = ellipse.centerX,
            v = ellipse.centerY,
            photoWidth = inputs.photoWidthPixels,
            photoHeight = inputs.photoHeightPixels,
            alignment = alignment,
            depthWidth = map.width,
            depthHeight = map.height
        )
        val meters = depthPixel?.let { (x, y) -> map.medianDepthMeters(x, y) }
        if (meters != null) return TreeDistance(meters.toDouble(), DistanceMethod.ARCORE_DEPTH_IMAGE)
    }
    return groundPlaneDistanceAtRowMeters(
        cameraHeightMeters = inputs.cameraHeightMeters,
        pitchDegrees = inputs.pitchDegrees,
        pixelY = ellipse.bottomY,
        imageHeightPixels = inputs.photoHeightPixels,
        focalLengthPixels = inputs.focalLengthPixels
    )?.let { TreeDistance(it, DistanceMethod.GEOMETRY_FALLBACK) }
}

/**
 * Everything measured for one member tree on the framing screen, ready to be placed on the map.
 *
 * [canopy] is sized with the AXIS depth and [rangeMeters] is the line-of-sight distance used to
 * place it — see the note in ClusterGeometry.kt for why those are two different numbers.
 */
data class ClusterMemberMeasurement(
    val treeNumber: Int,
    val photoEllipse: PhotoEllipse,
    val distance: TreeDistance,
    val bearingDegrees: Double,
    val rangeMeters: Double,
    val canopy: CanopyMeasurement
)

/**
 * Measures member [treeNumber] from its [displayEllipse]. Null when it has no distance (see
 * [resolveMemberDistance]) or the ellipse is degenerate.
 */
fun measureClusterMember(
    treeNumber: Int,
    displayEllipse: CanopyEllipse,
    displayToImageScale: Float,
    captureHeadingDegrees: Double,
    inputs: MemberDistanceInputs
): ClusterMemberMeasurement? {
    val photoEllipse = displayEllipse.toPhotoPixels(displayToImageScale)
    val distance = resolveMemberDistance(photoEllipse, inputs) ?: return null
    val canopy = canopyMeasurementFromEllipse(
        ellipse = displayEllipse,
        displayToImageScale = displayToImageScale,
        distanceMeters = sizingDepthMeters(distance.meters).toFloat(),
        focalLengthPixels = inputs.focalLengthPixels.toFloat()
    ) ?: return null
    val offAxis = horizontalOffAxisAngleDegrees(
        photoEllipse.centerX, inputs.photoWidthPixels, inputs.focalLengthPixels
    )
    return ClusterMemberMeasurement(
        treeNumber = treeNumber,
        photoEllipse = photoEllipse,
        distance = distance,
        bearingDegrees = bearingToPixelDegrees(
            captureHeadingDegrees, photoEllipse.centerX, inputs.photoWidthPixels, inputs.focalLengthPixels
        ),
        rangeMeters = lineOfSightRangeMeters(distance.meters, offAxis),
        canopy = canopy
    )
}

package com.gops.spatialmapper.measure

import com.gops.spatialmapper.capture.CameraIntrinsicsSnapshot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The measurement math behind the manual canopy-framing screen.
 *
 * Deliberately free of Android and Compose types: this turns "the operator drew an ellipse this big
 * on screen" into "the crown is N meters across", so it is the part that most needs unit tests away
 * from the UI. See CanopyFramingTest.
 *
 * The chain is:
 *
 *     ellipse radii in DISPLAY pixels
 *       → × displayToImagePixelScale        (undo the on-screen downscaling of the photo)
 *       = spans in CAPTURED-IMAGE pixels    (the space focalLengthPixels is expressed in)
 *       → canopyDiameterMeters(...)         (pinhole, per axis)
 *       = two perpendicular crown diameters → averaged for the polygon generator
 */

/** Smallest crown diameter we'll accept without warning, in meters. See [isPlausibleCanopyDiameter]. */
const val MIN_PLAUSIBLE_CANOPY_DIAMETER_METERS = 0.3

/** Largest crown diameter we'll accept without warning, in meters. See [isPlausibleCanopyDiameter]. */
const val MAX_PLAUSIBLE_CANOPY_DIAMETER_METERS = 50.0

/**
 * An ellipse the operator has framed over the captured photo, in DISPLAY pixels — i.e. pixels of the
 * photo as it is currently drawn on screen, with the origin at the photo's top-left corner (not the
 * screen's). Converting to captured-image pixels is [displayToImagePixelScale]'s job.
 *
 * Two independent radii, because the screen carries two independent perpendicular handles.
 */
data class CanopyEllipse(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float
)

/**
 * A confirmed canopy measurement: the single diameter that drives the map polygon, plus the two raw
 * per-axis diameters it came from.
 *
 * Both raw values are kept (and persisted) rather than only the average, so that a later revision can
 * change the combining convention — or analyse crown asymmetry — without re-collecting field data.
 */
data class CanopyMeasurement(
    /** The value that feeds the polygon generator: the mean of the two perpendicular diameters. */
    val diameterMeters: Double,
    /** Crown extent along the image's horizontal axis, in meters. */
    val horizontalDiameterMeters: Double,
    /** Crown extent along the image's vertical axis, in meters. */
    val verticalDiameterMeters: Double
) {
    val radiusMeters: Double get() = diameterMeters / 2.0

    /** False when the size is outside the sanity bounds — a warning, never a block. */
    val isPlausible: Boolean get() = isPlausibleCanopyDiameter(diameterMeters)
}

/**
 * Pixels of the captured image per pixel of the photo as displayed on screen.
 *
 * The framing ellipse is drawn in screen coordinates over a photo that has been decoded downsampled
 * and then fitted into the available space, so its radii are typically a small fraction of the true
 * capture resolution. [canopyDiameterMeters] needs spans in the SAME pixel space the focal length is
 * expressed in — the captured image — so every span is multiplied by this factor first. Skipping it
 * would under-report every canopy by whatever the display happened to shrink the photo by.
 *
 * Both sides are compared along the LONGEST image side, which makes the ratio independent of how the
 * photo was rotated upright and of which axis is which. Aspect ratio is preserved end to end (rotation
 * and fit-scaling both preserve it), so a single scalar is correct for both axes; phone sensors have
 * square pixels, so it is also correct to reuse one focal length on both axes.
 *
 * @param captureLongestSidePixels longest side of the captured image, from the camera intrinsics.
 * @param displayedLongestSidePixels longest side of the photo as actually drawn on screen.
 */
fun displayToImagePixelScale(
    captureLongestSidePixels: Int,
    displayedLongestSidePixels: Float
): Float? {
    if (captureLongestSidePixels <= 0) return null
    if (displayedLongestSidePixels <= 0f || !displayedLongestSidePixels.isFinite()) return null
    return captureLongestSidePixels.toFloat() / displayedLongestSidePixels
}

/**
 * Dimensions of the captured image once rotated upright, as (width, height).
 *
 * Every current capture path reports a snapshot whose dimensions already describe the file as saved
 * on disk — physically rotated upright at write time, see
 * [com.gops.spatialmapper.capture.saveArFrameAsJpeg] — so `rotationDegrees` is 0 in practice and this
 * is an identity mapping. It stays as a general-purpose function (not inlined at the one call site)
 * so a future capture path that reports a genuine pending rotation is still handled correctly rather
 * than by convention.
 */
fun CameraIntrinsicsSnapshot.uprightImageSize(): Pair<Int, Int> {
    val normalized = ((rotationDegrees % 360) + 360) % 360
    return if (normalized == 90 || normalized == 270) {
        imageHeightPixels to imageWidthPixels
    } else {
        imageWidthPixels to imageHeightPixels
    }
}

/**
 * Converts a framed [ellipse] into a canopy measurement.
 *
 * Each axis is converted independently through the pinhole formula, then averaged.
 *
 * WHY AVERAGE rather than take the larger: averaging two perpendicular crown diameters is the
 * standard forestry convention for crown width, and it is far less sensitive to one sloppy handle
 * drag than a max would be (a max propagates every overshoot, an average halves it).
 *
 * CAVEAT worth knowing when reading this data: in the field that convention means two perpendicular
 * diameters measured in the GROUND plane. Here the two axes are the width and the height of the
 * crown's silhouette in a photo, so the vertical figure is closer to crown depth than to a second
 * ground-plane diameter. For a roughly spherical crown the two are nearly the same; for a tall narrow
 * conifer or a wide flat-topped tree they are not, and the average will be pulled toward the vertical
 * extent. Both raw values are stored precisely so this can be revisited without new fieldwork.
 *
 * Returns null when the ellipse is degenerate or the camera model / distance is unusable, rather than
 * inventing a number.
 */
fun canopyMeasurementFromEllipse(
    ellipse: CanopyEllipse,
    displayToImageScale: Float,
    distanceMeters: Float?,
    focalLengthPixels: Float?
): CanopyMeasurement? {
    if (displayToImageScale <= 0f || !displayToImageScale.isFinite()) return null

    val horizontalSpanPixels = (2f * ellipse.radiusX * displayToImageScale).roundToInt()
    val verticalSpanPixels = (2f * ellipse.radiusY * displayToImageScale).roundToInt()

    val horizontal = canopyDiameterMeters(horizontalSpanPixels, distanceMeters, focalLengthPixels)
        ?: return null
    val vertical = canopyDiameterMeters(verticalSpanPixels, distanceMeters, focalLengthPixels)
        ?: return null

    return CanopyMeasurement(
        diameterMeters = (horizontal + vertical) / 2.0,
        horizontalDiameterMeters = horizontal,
        verticalDiameterMeters = vertical
    )
}

/**
 * Whether a measured crown diameter is within the range we'd expect in the field.
 *
 * These bounds are a SANITY CHECK, NOT A GATE. Unlike the confidence threshold they replace, nothing
 * here blocks a save: the operator can see the ellipse sitting on the actual photo, so if they framed
 * a genuinely tiny sapling or a genuinely enormous banyan, they are better placed to judge it than a
 * constant is. Failing this check only surfaces an inline warning.
 *
 * Bounds chosen against real crown sizes:
 *  - [MIN_PLAUSIBLE_CANOPY_DIAMETER_METERS] = 0.3 m — below a typical planted sapling's spread; a
 *    result this small usually means the ellipse was left near its minimum size, or the distance
 *    reading was far too short.
 *  - [MAX_PLAUSIBLE_CANOPY_DIAMETER_METERS] = 50 m — comfortably above the widest crowns of common
 *    large trees (a mature banyan or rain tree tops out around 30-40 m of spread), so anything beyond
 *    it points at a bad distance reading rather than a real plant.
 */
fun isPlausibleCanopyDiameter(diameterMeters: Double): Boolean =
    diameterMeters.isFinite() &&
        diameterMeters >= MIN_PLAUSIBLE_CANOPY_DIAMETER_METERS &&
        diameterMeters <= MAX_PLAUSIBLE_CANOPY_DIAMETER_METERS

/**
 * Starting ellipse for a freshly captured photo: centered, with both diameters at
 * [DEFAULT_ELLIPSE_DIAMETER_FRACTION] of the photo's SHORTER side.
 *
 * A visible, grabbable starting shape matters — a zero-size ellipse would leave the operator hunting
 * for handles on top of their own photo. A circle is the honest default: nothing is yet known about
 * the crown's proportions, so neither axis should be favoured.
 */
fun defaultCanopyEllipse(displayedWidth: Float, displayedHeight: Float): CanopyEllipse {
    val radius = max(1f, minOf(displayedWidth, displayedHeight) * DEFAULT_ELLIPSE_DIAMETER_FRACTION / 2f)
    return CanopyEllipse(
        centerX = displayedWidth / 2f,
        centerY = displayedHeight / 2f,
        radiusX = radius,
        radiusY = radius
    )
}

/** Default ellipse diameter as a fraction of the photo's shorter side — roughly a third of the frame. */
const val DEFAULT_ELLIPSE_DIAMETER_FRACTION = 1f / 3f

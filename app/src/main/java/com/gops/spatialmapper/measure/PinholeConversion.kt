package com.gops.spatialmapper.measure

/**
 * Pinhole conversion from an apparent size in pixels to a real-world size in meters:
 *
 *     diameterMeters = pixelSpan * distanceMeters / focalLengthPixels
 *
 * This is the similar-triangles relation of an ideal pinhole camera: an object of size `D` at range
 * `Z` projects to `d = f · D / Z` pixels, where `f` is the focal length expressed in pixels of the
 * SAME image the span was measured in. Both inputs therefore have to refer to the same image: the
 * caller is responsible for scaling [pixelSpan] into the captured image's pixel space before calling
 * this (the framing ellipse is drawn at whatever size the photo is displayed on screen — see
 * [displayToImagePixelScale]).
 *
 * The math here is unchanged from the automatic-segmentation revision; only the parameter name moved
 * from `maskPixelWidth` to [pixelSpan], because the span now comes from the operator's framing
 * ellipse rather than from a segmentation mask. It is deliberately free of Android types so it can be
 * unit-tested in isolation from the UI — see CanopyFramingTest.
 *
 * ASSUMPTIONS, and where they break:
 *  - The measured extent lies in a plane perpendicular to the optical axis at [distanceMeters]. A
 *    crown is roughly spherical, so its silhouette width is a good proxy for its diameter; a canopy
 *    that is much deeper than it is wide, or one measured at a steep angle, will read low.
 *  - [distanceMeters] refers to the point the span is centered on. The distance reading is taken at
 *    the frame center, so a plant framed well off-center is being measured against the wrong range.
 *  - Lens distortion is ignored. Near the frame center it is negligible.
 *
 * Returns null when any input is missing or non-physical (non-positive span, distance, or focal
 * length), rather than producing a nonsense measurement.
 */
fun canopyDiameterMeters(
    pixelSpan: Int,
    distanceMeters: Float?,
    focalLengthPixels: Float?
): Double? {
    if (distanceMeters == null || focalLengthPixels == null) return null
    if (pixelSpan <= 0 || distanceMeters <= 0f || focalLengthPixels <= 0f) return null
    if (!distanceMeters.isFinite() || !focalLengthPixels.isFinite()) return null

    val diameter = pixelSpan.toDouble() * distanceMeters.toDouble() / focalLengthPixels.toDouble()
    return if (diameter.isFinite() && diameter > 0.0) diameter else null
}

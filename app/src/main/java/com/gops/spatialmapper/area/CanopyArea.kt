package com.gops.spatialmapper.area

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import java.util.Locale

/**
 * A polygon needs three distinct vertices before "area" means anything. Rows saved with fewer (or
 * with a footprint that degraded to a couple of parseable vertices — see [com.gops.spatialmapper.data.Converters])
 * have no area, which is different from having an area of zero.
 */
private const val MIN_VERTICES_FOR_AREA = 3

/** Forestry's working unit. Exposed so callers can convert without re-deriving the constant. */
const val SQUARE_METERS_PER_HECTARE = 10_000.0

/**
 * Area of a canopy footprint in square meters, or null when [vertices] can't describe a polygon.
 *
 * Delegates to [SphericalUtil.computeArea], which integrates the spherical excess over the closed
 * path on an Earth-radius sphere. That matters at the scale this app works at for the same reason
 * [com.gops.spatialmapper.map.generateCanopyPolygon] places vertices with computeOffset rather than
 * by adding degrees: a planar shoelace on raw lat/lon would treat a degree of longitude as a degree
 * of latitude and under-report by roughly cos(latitude) — ~6% at 20°N, ~35% at 55°N. Silent, and
 * exactly the kind of error a stand-level tally would carry all the way to a report.
 *
 * The path does NOT need to be closed: computeArea closes it itself, so passing the stored 24-vertex
 * outline is correct as-is. The result is unsigned, so vertex winding is irrelevant here.
 */
fun polygonAreaSquareMeters(vertices: List<LatLng>): Double? {
    if (vertices.size < MIN_VERTICES_FOR_AREA) return null
    return SphericalUtil.computeArea(vertices)
}

/**
 * Formats an area for the operator, switching to hectares once square meters stop being readable.
 *
 * Forestry works in hectares, but a single crown is a two-digit m² number — "0.0034 ha" is useless in
 * the field. So: m² below one hectare, hectares at or above it, with the m² echoed alongside so a
 * stand total can still be cross-checked against the per-tree numbers it came from.
 *
 * Null renders as an explicit "not computed" rather than a bare dash: for a row that predates the
 * backfill it is a genuinely absent value, not a measurement of nothing.
 */
fun formatArea(areaSquareMeters: Double?): String {
    val area = areaSquareMeters ?: return "— (not computed)"
    val squareMeters = when {
        area >= 100.0 -> "%.0f m²".format(Locale.getDefault(), area)
        area >= 10.0 -> "%.1f m²".format(Locale.getDefault(), area)
        else -> "%.2f m²".format(Locale.getDefault(), area)
    }
    if (area < SQUARE_METERS_PER_HECTARE) return squareMeters
    return "%.2f ha (%s)".format(Locale.getDefault(), area / SQUARE_METERS_PER_HECTARE, squareMeters)
}

package com.gops.spatialmapper.map

import com.google.android.gms.maps.model.LatLng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How far the cluster outline stands off the crowns it encloses, in metres. Enough that the outline
 * reads as a separate line around the group rather than as a second stroke on the outermost crown.
 */
const val CLUSTER_OUTLINE_PADDING_METERS = 1.5

/** Points used to round each corner of the padded hull. */
private const val PADDING_DIRECTIONS = 16

private const val EARTH_RADIUS_METERS = 6_371_009.0

/**
 * A rounded, padded convex hull around every vertex of every crown in a cluster: the outline the
 * maps draw to say "these trees were recorded together".
 *
 * Computed in a local east/north metre plane around the vertices' centroid (an equirectangular
 * projection — exact enough over the tens of metres a cluster spans), so the padding is metres on
 * the ground at any latitude rather than degrees. Padding is a Minkowski sum with a
 * [PADDING_DIRECTIONS]-gon: each vertex is pushed out in every direction and the hull of all of that
 * is taken, which rounds the corners instead of producing spikes.
 *
 * Returns an open path (the maps close polygons themselves), or an empty list when there is nothing
 * to outline. A cluster of one tree gets no outline from the callers — see [needsClusterOutline].
 */
fun paddedClusterOutline(
    crowns: List<List<LatLng>>,
    paddingMeters: Double = CLUSTER_OUTLINE_PADDING_METERS
): List<LatLng> {
    val vertices = crowns.flatten()
    if (vertices.isEmpty()) return emptyList()

    val originLat = vertices.sumOf { it.latitude } / vertices.size
    val originLng = vertices.sumOf { it.longitude } / vertices.size
    val metersPerDegLat = EARTH_RADIUS_METERS * PI / 180.0
    val metersPerDegLng = metersPerDegLat * cos(Math.toRadians(originLat))

    val padded = ArrayList<Pair<Double, Double>>(vertices.size * PADDING_DIRECTIONS)
    vertices.forEach { vertex ->
        val east = (vertex.longitude - originLng) * metersPerDegLng
        val north = (vertex.latitude - originLat) * metersPerDegLat
        for (k in 0 until PADDING_DIRECTIONS) {
            val angle = 2.0 * PI * k / PADDING_DIRECTIONS
            padded += (east + paddingMeters * cos(angle)) to (north + paddingMeters * sin(angle))
        }
    }

    return convexHull(padded).map { (east, north) ->
        LatLng(originLat + north / metersPerDegLat, originLng + east / metersPerDegLng)
    }
}

/** Only an actual cluster gets an outline: around a single tree it would just be a second ring. */
fun needsClusterOutline(treeCount: Int): Boolean = treeCount > 1

/**
 * Andrew's monotone chain. Counter-clockwise, no repeated closing point, collinear points dropped.
 * Internal so the hull itself is testable apart from the projection around it.
 */
internal fun convexHull(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    val sorted = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    if (sorted.size < 3) return sorted

    fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

    val lower = ArrayList<Pair<Double, Double>>()
    for (p in sorted) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0.0) {
            lower.removeAt(lower.size - 1)
        }
        lower += p
    }
    val upper = ArrayList<Pair<Double, Double>>()
    for (p in sorted.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0.0) {
            upper.removeAt(upper.size - 1)
        }
        upper += p
    }
    return lower.dropLast(1) + upper.dropLast(1)
}

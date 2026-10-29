package com.gops.spatialmapper.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.Polygon

/**
 * The cluster outline as drawn on every map: a dark halo under white round dots, unfilled.
 * See [CLUSTER_OUTLINE_COLOR] for why that style. One composable so the three maps can't drift.
 *
 * The dotted ring carries the tap, when there is one ([onClick]) — tapping the outline of a capture
 * opens that capture, same as tapping any of its crowns. Interior taps are taken by the crowns,
 * which sit above the outline.
 *
 * [visible] = false keeps the native polygons but neither draws them nor lets them take a tap — the
 * raw-map toggle flips this rather than removing and re-adding the shapes.
 */
@Composable
@GoogleMapComposable
fun ClusterOutlinePolygons(outline: List<LatLng>, onClick: (() -> Unit)? = null, visible: Boolean = true) {
    if (outline.size < 3) return
    Polygon(
        points = outline,
        fillColor = Color.Transparent,
        strokeColor = CLUSTER_OUTLINE_HALO_COLOR,
        strokeWidth = CLUSTER_OUTLINE_HALO_WIDTH,
        geodesic = false,
        visible = visible,
        zIndex = CLUSTER_OUTLINE_HALO_Z_INDEX
    )
    Polygon(
        points = outline,
        fillColor = Color.Transparent,
        strokeColor = CLUSTER_OUTLINE_COLOR,
        strokePattern = clusterOutlinePattern(),
        strokeWidth = CLUSTER_OUTLINE_WIDTH,
        geodesic = false,
        zIndex = CLUSTER_OUTLINE_Z_INDEX,
        visible = visible,
        // A hidden ring must not take taps either, so a raw-map tap can never open a capture.
        clickable = visible && onClick != null,
        onClick = { if (visible) onClick?.invoke() }
    )
}

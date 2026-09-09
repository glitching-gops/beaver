package com.gops.spatialmapper.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * How a RECORDED TREE is drawn on a map, in one place — present ones (canopies) and absent ones
 * (removed trees) together.
 *
 * The file grew past "canopy" deliberately rather than sprouting a sibling: a removed-tree marker is
 * defined entirely by contrast with the canopy palette (see [REMOVED_TREE_COLOR]), so splitting them
 * across two files would put the two halves of one decision where neither shows the other. Live,
 * ephemeral Scout state stays in ScoutStyle.kt — that IS a different concern, with a different
 * lifetime, and it only has to avoid these colours rather than be derived from them.
 *
 * The app records flora exclusively, so there is exactly ONE canopy look — this used to be chosen by
 * an asset-category chip row (Building/Flora/Water/Other) that no longer exists, and there is no
 * per-species variation either because species identification was never built. Do not add speculative
 * per-species colors here.
 *
 * These constants were previously copy-pasted privately into MapReviewScreen and HistoryScreen. The
 * global map made that a third copy, so they were lifted here: three maps showing the same objects in
 * three subtly different greens would read as three different things.
 */

/** Semi-transparent so satellite imagery reads through the crown. */
val CANOPY_FILL_COLOR = Color(0x554CAF50)

/** Opaque, so the outline edge stays crisp against foliage-colored imagery. */
val CANOPY_STROKE_COLOR = Color(0xFF2E7D32)

/** Outline weight for an ordinary canopy on the global map. */
const val CANOPY_STROKE_WIDTH = 4f

/**
 * Highlight treatment for the one tree the operator navigated to via "View on map".
 *
 * Amber rather than a brighter green: against satellite imagery of vegetation, a green-on-green
 * highlight is exactly the comparison the eye is worst at. A different HUE (plus a much heavier
 * stroke and a pin) is unambiguous even on a small phone screen in daylight.
 */
val CANOPY_HIGHLIGHT_FILL_COLOR = Color(0x66FFC107)
val CANOPY_HIGHLIGHT_STROKE_COLOR = Color(0xFFFF6F00)
const val CANOPY_HIGHLIGHT_STROKE_WIDTH = 10f

// -------------------------------------------------------------------------------------------
// Removed trees — present in the imagery, gone on the ground.
// -------------------------------------------------------------------------------------------

/**
 * Red, as the visual inverse of the canopy green.
 *
 * Green-means-standing / red-means-gone is the one colour convention that needs no legend, and red
 * is also the only strong hue left: green is a canopy, amber is the "View on map" highlight, and
 * blue/cyan is Scout's live position. Chosen as a saturated red rather than a dark one so it holds
 * up against the dark foliage these markers sit among.
 */
val REMOVED_TREE_COLOR = Color(0xFFD32F2F)

/** White ring around the marker, for the same reason Scout's position dot has one: daylight. */
val REMOVED_TREE_RING_COLOR = Color.White

/** Above the canopy polygons (1-3) so an absent tree is never hidden by a neighbour's crown. */
const val REMOVED_TREE_Z_INDEX = 4f

/**
 * The removed-tree marker: a red disc with a white ring and a white cross-out bar.
 *
 * A bitmap rather than [BitmapDescriptorFactory.defaultMarker] with HUE_RED, because the default is
 * a teardrop pin — the shape already used for the focused-tree and projected-target markers. The bar
 * carries the meaning without needing colour at all, which matters for a red/green pairing seen by
 * roughly 1 in 12 men with some form of colour-vision deficiency: to them the disc and the canopy
 * may read similarly, but a struck-through disc still reads as "not there".
 *
 * Requires the Maps SDK to be initialised, so build it behind an `onMapLoaded` flag — same rule as
 * [scoutPositionIcon] and MapReviewScreen's drag handles.
 */
fun removedTreeIcon(): BitmapDescriptor {
    val sizePx = 56
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(70, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 2f, shadowPaint)

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = REMOVED_TREE_RING_COLOR.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 6f, ringPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = REMOVED_TREE_COLOR.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 11f, fillPaint)

    // The cross-out bar: the shape-based half of the signal.
    val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = REMOVED_TREE_RING_COLOR.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    val inset = sizePx * 0.30f
    canvas.drawLine(inset, inset, sizePx - inset, sizePx - inset, barPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

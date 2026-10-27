package com.gops.spatialmapper.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.PatternItem
import com.gops.spatialmapper.data.TreeClass

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

// -------------------------------------------------------------------------------------------
// Tree classification colors — one per growth form.
// -------------------------------------------------------------------------------------------

/**
 * Fill color for a canopy polygon of a given [TreeClass]. Semi-transparent so satellite
 * imagery reads through the crown. For [TreeClass.UNCLASSIFIED], the alpha is very low
 * (0x33) to signal "pending", versus 0x66 (40%) for classified trees.
 *
 * The palette is deliberately high-chroma with only one warm hue besides pink, so classes
 * stay distinguishable on satellite imagery. Each class has a visual identity that's hard
 * to confuse with others or with the canopy highlight (amber), removed-tree marker (red),
 * or scout colors (blue/cyan/grey-blue).
 */
fun canopyFillColor(treeClass: TreeClass): Color = when (treeClass) {
    TreeClass.PALM -> Color(0x66FF2D95)                // semi-transparent hot pink
    TreeClass.BROADLEAF_EVERGREEN -> Color(0x662962FF) // semi-transparent royal blue
    TreeClass.BROADLEAF_DECIDUOUS -> Color(0x66FF6D00)  // semi-transparent orange
    TreeClass.BROADLEAF_UNCONFIRMED -> Color(0x6600E5FF) // semi-transparent cyan
    TreeClass.BAMBOO -> Color(0x6676FF03)                // semi-transparent neon lime
    TreeClass.OTHER -> Color(0x66AA00FF)                 // semi-transparent violet
    TreeClass.UNCLASSIFIED -> Color(0x33888888)          // low-alpha grey (very transparent)
}

/**
 * Stroke color for a canopy polygon of a given [TreeClass]. Opaque and solid for classified
 * trees, so the outline stays crisp over bright satellite imagery. For [TreeClass.UNCLASSIFIED],
 * the stroke is dashed (not solid) to signal "pending" and "not yet concrete".
 *
 * The stroke colors are slightly darker/more saturated than the fill to maintain edge definition.
 */
fun canopyStrokeColor(treeClass: TreeClass): Color = when (treeClass) {
    TreeClass.PALM -> Color(0xFFFF2D95)                // hot pink, fully opaque
    TreeClass.BROADLEAF_EVERGREEN -> Color(0xFF2962FF) // royal blue, fully opaque
    TreeClass.BROADLEAF_DECIDUOUS -> Color(0xFFFF6D00) // orange, fully opaque
    TreeClass.BROADLEAF_UNCONFIRMED -> Color(0xFF00E5FF) // cyan, fully opaque
    TreeClass.BAMBOO -> Color(0xFF76FF03)               // neon lime, fully opaque
    TreeClass.OTHER -> Color(0xFFAA00FF)                // violet, fully opaque
    TreeClass.UNCLASSIFIED -> Color(0xFF666666)         // grey, fully opaque (dashed)
}

/**
 * Whether the stroke for a canopy polygon of a given [TreeClass] should be dashed.
 * Only [TreeClass.UNCLASSIFIED] is dashed, to signal "pending" and distinguish it from
 * "concrete" (classified) trees. Classified trees use solid strokes.
 */
fun canopyStrokeDashed(treeClass: TreeClass): Boolean =
    treeClass == TreeClass.UNCLASSIFIED

/**
 * The stroke pattern that goes with [canopyStrokeDashed]: dashed for an UNCLASSIFIED crown, solid
 * (null) for a classified one. One place, so all three maps dash the same crowns the same way.
 */
fun canopyStrokePattern(treeClass: TreeClass): List<PatternItem>? =
    if (canopyStrokeDashed(treeClass)) {
        listOf(Dash(UNCLASSIFIED_DASH_LENGTH), Gap(UNCLASSIFIED_DASH_GAP))
    } else {
        null
    }

private const val UNCLASSIFIED_DASH_LENGTH = 18f
private const val UNCLASSIFIED_DASH_GAP = 10f

// -------------------------------------------------------------------------------------------
// Cluster outline — "these trees were recorded in one capture".
// -------------------------------------------------------------------------------------------

/**
 * WHITE, ROUND DOTS over a dark halo, no fill.
 *
 * Chosen by elimination against everything else a map can show at the same time:
 *  - not grey and not dashed — that is an unclassified crown ([canopyStrokePattern]);
 *  - not a dashed line in grey-blue — that is a saved survey track (SAVED_TRACK_COLOR);
 *  - not red — removed trees; not amber — the "View on map" highlight;
 *  - not cyan or blue — Scout's live breadcrumb and position (and two class colours).
 * White is the one colour no other map element is drawn in, and dots are the one stroke pattern
 * nothing else uses, so the outline differs from each of those in colour AND shape — it survives a
 * colour-vision deficiency, and it survives bright imagery thanks to the halo underneath.
 *
 * Unfilled, so it never tints the crowns it encloses or the imagery between them.
 */
val CLUSTER_OUTLINE_COLOR = Color.White
val CLUSTER_OUTLINE_HALO_COLOR = Color(0x8C000000)
const val CLUSTER_OUTLINE_WIDTH = 5f
const val CLUSTER_OUTLINE_HALO_WIDTH = 10f
private const val CLUSTER_OUTLINE_DOT_GAP = 14f

/** Under the crowns (1-3) so a tap on a tree hits the tree, above survey/scout lines (0.4-0.5). */
const val CLUSTER_OUTLINE_Z_INDEX = 0.8f
const val CLUSTER_OUTLINE_HALO_Z_INDEX = 0.79f

fun clusterOutlinePattern(): List<PatternItem> = listOf(Dot(), Gap(CLUSTER_OUTLINE_DOT_GAP))

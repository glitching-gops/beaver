package com.gops.spatialmapper.map

import androidx.compose.ui.graphics.Color

/**
 * How a canopy is drawn on a map, in one place.
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

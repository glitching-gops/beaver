package com.gops.spatialmapper.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/**
 * How the LIVE position and walked path are drawn, in one place.
 *
 * Deliberately a sibling of [CanopyStyle.kt] rather than an addition to it: that file documents
 * itself as "how a canopy is drawn" and warns against speculative additions, and a canopy is a
 * recorded object while this is the operator's own live position — different lifetime, different
 * meaning, different reason to change. What the two DO share is one palette problem, so read them
 * together before touching either.
 *
 * COLOUR REASONING. The map already spends green on canopies (CANOPY_FILL/STROKE_COLOR) and amber on
 * the "View on map" highlight (CANOPY_HIGHLIGHT_*), over satellite imagery that is itself mostly
 * green and brown. That leaves the blue/cyan end of the spectrum as the only genuinely unambiguous
 * choice — which is also what every mapping app on earth uses for "you are here", so it needs no
 * learning. The marker is drawn with a heavy white ring for the same reason a life ring is white: it
 * has to stay findable against dark foliage AND bright bare soil in direct sunlight.
 */

/** Live position: the universal "you are here" blue, at full opacity so it reads at a glance. */
val SCOUT_POSITION_COLOR = Color(0xFF1E88E5)

/** Ring around the position dot — white, for contrast against both dark canopy and pale ground. */
val SCOUT_POSITION_RING_COLOR = Color.White

/**
 * The walked breadcrumb. Cyan rather than the marker's blue so the path stays visually subordinate
 * to the live position — the operator's eye should land on where they ARE, then follow where they
 * have been, not the other way round.
 */
val SCOUT_TRAIL_COLOR = Color(0xFF00E5FF)

/** Heavy enough to follow at speed on a phone in daylight, thin enough not to bury small canopies. */
const val SCOUT_TRAIL_WIDTH = 8f

/**
 * A SAVED survey track overlaid from the History list — muted grey-blue, not the live cyan.
 *
 * The distinction is the whole point of having a second colour. A past track and the live breadcrumb
 * can be on screen at once (open a saved survey while Scout is running), and confusing "where I
 * walked last Tuesday" with "where I am walking now" is a navigation error in the field, not just an
 * aesthetic one. So the saved track differs in hue, in saturation AND in line style — desaturated and
 * dashed against bright cyan and solid — which keeps it readable in direct sun and to a viewer with
 * colour-vision deficiency, neither of whom can rely on hue alone.
 */
val SAVED_TRACK_COLOR = Color(0xFF5C7A99)

/** Thinner than the live trail, so the past sits visually behind the present. */
const val SAVED_TRACK_WIDTH = 6f

/** Dash and gap lengths, in pixels, for the saved track's pattern. */
const val SAVED_TRACK_DASH = 24f
const val SAVED_TRACK_GAP = 16f

/**
 * Below the live trail and below the canopies: reference material, never something that can obscure
 * the survey data or the operator's own position.
 */
const val SAVED_TRACK_Z_INDEX = 0.4f

/**
 * Draw order. The live marker sits above every canopy (which use zIndex 1-3 on the global map) so a
 * dense stand can never hide the operator's own position; the trail sits below the canopies so it
 * cannot obscure the outlines the survey is actually about.
 */
const val SCOUT_TRAIL_Z_INDEX = 0.5f
const val SCOUT_POSITION_Z_INDEX = 10f

/**
 * The "you are here" dot: a filled blue disc inside a thick white ring.
 *
 * Built as a bitmap rather than using [BitmapDescriptorFactory.defaultMarker] with a hue, because the
 * default marker is a teardrop PIN whose point marks the position — the same shape already used for
 * the focused-tree marker and the projected-target marker. A live position is a place you are AT, not
 * a place you are pointing at, and a symmetrical dot says that without competing with the pins.
 *
 * BitmapDescriptorFactory requires the Maps SDK to be initialised, which is only guaranteed after the
 * map reports loaded — callers must build this behind an `onMapLoaded` flag, exactly as
 * MapReviewScreen does for its drag handles.
 */
fun scoutPositionIcon(): BitmapDescriptor {
    val sizePx = 56
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f

    // Soft outer shadow so the dot survives a light-coloured background (bare earth, a track).
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 2f, shadowPaint)

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SCOUT_POSITION_RING_COLOR.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 6f, ringPaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SCOUT_POSITION_COLOR.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 14f, fillPaint)

    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

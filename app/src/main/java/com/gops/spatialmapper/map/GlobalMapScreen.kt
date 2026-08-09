package com.gops.spatialmapper.map

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import com.gops.spatialmapper.data.SessionEntity
import com.gops.spatialmapper.data.SessionRepository

private const val TAG = "SpatialMapperGlobalMap"

/** Zoom used when the map opens focused on ONE tree; matches the individual map review screen. */
private const val FOCUSED_ZOOM = 19.5f

/** Fallback zoom if the auto-fit bounds move is rejected (see the runCatching below). */
private const val FALLBACK_ZOOM = 17f

/** Breathing room around the auto-fit bounding box so edge canopies aren't flush with the bezel. */
private val BOUNDS_PADDING = 56.dp

/**
 * One saved session, ready to draw. Built once per session-list change rather than per recomposition
 * — see the `remember(sessions)` in [GlobalMapScreen].
 */
private data class CanopyOverlay(
    val sessionId: Long,
    val label: String,
    val vertices: List<LatLng>,
    /** Bounding box of [vertices]; supplies both the focus center and the global auto-fit. */
    val bounds: LatLngBounds
)

/**
 * Every recorded tree on one satellite view.
 *
 * Each session is drawn as its ACTUAL canopy outline, not a pin — the outline is the data, and a map
 * of pins would throw away the crown sizes the whole capture pipeline exists to measure. Tapping a
 * canopy goes straight to that session's detail screen.
 *
 * Two camera behaviours:
 *  - [focusedSessionId] == null (Map tab opened normally): auto-fit to every tree on load.
 *  - [focusedSessionId] != null (arrived via "View on map"): center and zoom on that one tree, and
 *    highlight it so it's obvious which of the visible canopies was meant.
 *
 * @param onOpenSession navigate to a session's detail screen.
 * @param onClearFocus  drop the single-tree focus and return to the all-trees view.
 */
@Composable
fun GlobalMapScreen(
    focusedSessionId: Long?,
    onOpenSession: (Long) -> Unit,
    onClearFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    // Null until the first emission, so "still loading" and "genuinely no trees" stay distinguishable
    // — otherwise the empty state would flash on every open before the query lands.
    val sessions by repository.observeSessions().collectAsState(initial = null)

    // PERFORMANCE: resolving outlines and computing per-canopy bounds happens once per session-list
    // change, NOT on every recomposition (camera moves alone recompose this screen constantly). The
    // Room Flow re-emits an equal list on unrelated writes, and List equality is structural here, so
    // remember() genuinely skips the work rather than just deferring it.
    val canopies = remember(sessions) { sessions.orEmpty().mapNotNull { it.toCanopyOverlay() } }
    val allBounds = remember(canopies) { canopies.combinedBounds() }
    val focused = remember(canopies, focusedSessionId) {
        focusedSessionId?.let { id -> canopies.firstOrNull { it.sessionId == id } }
    }

    var mapLoaded by remember { mutableStateOf(false) }
    val cameraPositionState = rememberCameraPositionState()
    val density = LocalDensity.current

    // Camera positioning waits for onMapLoaded: newLatLngBounds needs the map's measured size and
    // throws IllegalStateException if it's still zero, which is exactly the state during first layout.
    LaunchedEffect(mapLoaded, canopies, focusedSessionId) {
        if (!mapLoaded || canopies.isEmpty()) return@LaunchedEffect

        val target = focused
        if (target != null) {
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(target.bounds.center, FOCUSED_ZOOM)
            )
            return@LaunchedEffect
        }

        val bounds = allBounds ?: return@LaunchedEffect
        val paddingPx = with(density) { BOUNDS_PADDING.roundToPx() }
        runCatching {
            cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx))
        }.onFailure { error ->
            // Defence in depth: if the bounds move is rejected anyway, a centred zoom still shows
            // something useful instead of leaving the operator staring at the null island.
            Log.w(TAG, "Auto-fit to bounds failed; falling back to a centred zoom", error)
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(bounds.center, FALLBACK_ZOOM)
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.SATELLITE),
            uiSettings = MapUiSettings(mapToolbarEnabled = false),
            onMapLoaded = { mapLoaded = true }
        ) {
            canopies.forEach { canopy ->
                val isFocused = canopy.sessionId == focusedSessionId
                Polygon(
                    points = canopy.vertices,
                    fillColor = if (isFocused) CANOPY_HIGHLIGHT_FILL_COLOR else CANOPY_FILL_COLOR,
                    strokeColor = if (isFocused) {
                        CANOPY_HIGHLIGHT_STROKE_COLOR
                    } else {
                        CANOPY_STROKE_COLOR
                    },
                    strokeWidth = if (isFocused) {
                        CANOPY_HIGHLIGHT_STROKE_WIDTH
                    } else {
                        CANOPY_STROKE_WIDTH
                    },
                    // Polygons are NOT clickable by default — without this the taps below never fire.
                    clickable = true,
                    onClick = { onOpenSession(canopy.sessionId) },
                    // Keep the highlighted crown on top so an overlapping neighbour can't hide it.
                    zIndex = if (isFocused) 2f else 1f
                )
            }

            // A pin on the focused tree: the heavier amber outline reads well when the canopy fills
            // the screen, but this keeps it findable if the operator pans away and zooms out.
            focused?.let { target ->
                Marker(
                    state = remember(target.sessionId) { MarkerState(position = target.bounds.center) },
                    title = target.label.ifBlank { "(no label)" },
                    snippet = "Tap the canopy to open this session",
                    zIndex = 3f
                )
            }
        }

        when {
            sessions == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            canopies.isEmpty() -> EmptyMapState(
                hasSessions = !sessions.isNullOrEmpty(),
                modifier = Modifier.align(Alignment.Center)
            )
            else -> MapHeader(
                focusedLabel = focused?.label,
                treeCount = canopies.size,
                onClearFocus = onClearFocus,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

/**
 * Shown when there is nothing to draw.
 *
 * [hasSessions] separates the two ways that happens: no saved sessions at all, versus saved sessions
 * that carry no usable coordinates. The second would otherwise look like data loss.
 */
@Composable
private fun EmptyMapState(hasSessions: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(24.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (hasSessions) "Nothing to map yet" else "No trees recorded yet",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = if (hasSessions) {
                    "Saved sessions exist, but none of them have a canopy outline or a map position."
                } else {
                    "Capture a plant on the Capture tab and save it — it'll appear here."
                },
                fontSize = 13.sp,
                color = Color.DarkGray,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Floating header: either the tree count, or which single tree is being focused plus a way out of
 * that focus. Without the escape hatch, arriving via "View on map" would strand the operator zoomed
 * onto one crown with no obvious route back to the whole survey.
 */
@Composable
private fun MapHeader(
    focusedLabel: String?,
    treeCount: Int,
    onClearFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (focusedLabel == null) {
                Text(
                    text = if (treeCount == 1) "1 tree" else "$treeCount trees",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = "Showing ${focusedLabel.ifBlank { "(no label)" }}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClearFocus) { Text("Show all", fontSize = 13.sp) }
            }
        }
    }
}

/**
 * Turns a saved session into something drawable, or null if it has no usable geometry at all.
 *
 * SOURCE OF TRUTH is [SessionEntity.polygonVertices] — the outline the operator actually confirmed on
 * the review screen, including any center drag and radius nudge they made there. Note this is NOT the
 * same as regenerating the crown from the stored center/radius: the row keeps the RAW projected
 * target and the framed diameter (both pre-nudge), so regenerating would quietly redraw a different
 * shape than the one that was confirmed and saved.
 *
 * Regeneration is only the fallback, for rows whose outline is missing or degenerate, and it goes
 * through the very same [generateCanopyPolygon] the review screen uses — with the seed recovered from
 * the timestamp via [canopySeedFor], so a given session always regenerates the same silhouette.
 */
private fun SessionEntity.toCanopyOverlay(): CanopyOverlay? {
    val vertices = canopyOutline() ?: return null
    val builder = LatLngBounds.builder()
    vertices.forEach(builder::include)
    return CanopyOverlay(
        sessionId = id,
        label = label,
        vertices = vertices,
        bounds = builder.build()
    )
}

private fun SessionEntity.canopyOutline(): List<LatLng>? {
    if (polygonVertices.size >= 3) return polygonVertices

    val latitude = projectedLatitude ?: return null
    val longitude = projectedLongitude ?: return null
    val diameter = canopyDiameterMeters ?: return null
    val radius = (diameter / 2.0).coerceIn(MIN_CANOPY_RADIUS_METERS, MAX_CANOPY_RADIUS_METERS)
    return generateCanopyPolygon(
        center = LatLng(latitude, longitude),
        baseRadiusMeters = radius,
        seed = canopySeedFor(timestampMillis)
    )
}

/** Bounding box containing every canopy, or null when there's nothing to fit. */
private fun List<CanopyOverlay>.combinedBounds(): LatLngBounds? {
    if (isEmpty()) return null
    val builder = LatLngBounds.builder()
    forEach { canopy ->
        builder.include(canopy.bounds.northeast)
        builder.include(canopy.bounds.southwest)
    }
    return builder.build()
}

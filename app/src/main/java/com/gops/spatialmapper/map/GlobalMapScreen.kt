package com.gops.spatialmapper.map

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.gops.spatialmapper.data.ClusterMemberEntity
import com.gops.spatialmapper.data.RemovedTreeEntity
import com.gops.spatialmapper.data.RemovedTreeRepository
import com.gops.spatialmapper.data.SessionEntity
import com.gops.spatialmapper.data.SessionRepository
import com.gops.spatialmapper.data.SurveyTrackRepository
import com.gops.spatialmapper.data.TreeClass
import com.gops.spatialmapper.data.parseTreeClass
import com.gops.spatialmapper.survey.formatSurveyDuration
import com.gops.spatialmapper.removed.buildRemovedTree
import com.gops.spatialmapper.removed.resolveOfficerFix
import com.gops.spatialmapper.scout.ScoutSession
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.google.android.gms.maps.model.BitmapDescriptor
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "SpatialMapperGlobalMap"

/** Zoom used when the map opens focused on ONE tree; matches the individual map review screen. */
private const val FOCUSED_ZOOM = 19.5f

/** Zoom for "View on map" on a cluster: a step out, so its neighbours are on screen too. */
private const val FOCUSED_CLUSTER_ZOOM = 18.5f

/** Fallback zoom if the auto-fit bounds move is rejected (see the runCatching below). */
private const val FALLBACK_ZOOM = 17f

/** Breathing room around the auto-fit bounding box so edge canopies aren't flush with the bezel. */
private val BOUNDS_PADDING = 56.dp

/**
 * Zoom the camera snaps to when Scout mode first locks on, and when Recenter is tapped.
 *
 * Deliberately looser than [FOCUSED_ZOOM]: scouting is about "what is around me as I walk", so the
 * useful frame is tens of meters of surrounding stand, not one crown filling the screen.
 */
private const val SCOUT_FOLLOW_ZOOM = 18.5f

/**
 * Camera animation length while following, in ms. Comfortably shorter than the 3 s fix interval so
 * each step settles before the next one starts — a longer animation would leave the camera
 * permanently mid-flight and feel like drift rather than following.
 */
private const val SCOUT_FOLLOW_ANIMATION_MILLIS = 700

/**
 * One saved TREE, ready to draw — the centre tree of a session, or one member of its cluster. Built
 * once per data change rather than per recomposition — see the `remember(sessions, members)` in
 * [GlobalMapScreen].
 */
private data class CanopyOverlay(
    val sessionId: Long,
    /** 1 for the session row's own tree, 2..N for cluster members. */
    val treeNumber: Int,
    val label: String,
    val vertices: List<LatLng>,
    /** Bounding box of [vertices]; supplies both the focus center and the global auto-fit. */
    val bounds: LatLngBounds,
    /** The tree's growth-form classification, for coloring on the map. */
    val treeClass: TreeClass
)

/** The outline around one capture's trees; only for captures of more than one tree. */
private data class ClusterOverlay(val sessionId: Long, val outline: List<LatLng>)

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
    overlaySurveyId: Long?,
    onOpenSession: (Long) -> Unit,
    onClearFocus: () -> Unit,
    onClearSurveyOverlay: () -> Unit,
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
    val members by repository.observeAllMembers().collectAsState(initial = emptyList())
    val canopies = remember(sessions, members) {
        val membersBySession = members.groupBy { it.sessionId }
        sessions.orEmpty().flatMap { session ->
            listOfNotNull(session.toCanopyOverlay()) +
                membersBySession[session.id].orEmpty().mapNotNull { it.toCanopyOverlay(session.label, session.timestampMillis) }
        }
    }
    val clusters = remember(canopies) {
        canopies.groupBy { it.sessionId }
            .filterValues { needsClusterOutline(it.size) }
            .map { (sessionId, trees) -> ClusterOverlay(sessionId, paddedClusterOutline(trees.map { it.vertices })) }
    }
    val allBounds = remember(canopies) { canopies.combinedBounds() }
    // Every tree of the focused capture: "View on map" on a cluster means the cluster.
    val focusedTrees = remember(canopies, focusedSessionId) {
        focusedSessionId?.let { id -> canopies.filter { it.sessionId == id } }.orEmpty()
    }
    val focused = focusedTrees.firstOrNull()
    val focusedBounds = remember(focusedTrees) { focusedTrees.combinedBounds() }

    var mapLoaded by remember { mutableStateOf(false) }
    val cameraPositionState = rememberCameraPositionState()
    val density = LocalDensity.current

    // --- Scout mode ---
    // Read from the process-wide session rather than owned here: the foreground service keeps
    // tracking while this screen is not composed at all (other tab, screen locked), so the trail has
    // to outlive the composable. See the rationale on ScoutSession.
    val scout by ScoutSession.state.collectAsState()
    val toggleScout = rememberScoutToggle()
    val toggleSurvey = rememberSurveyToggle()

    // Auto-follow, and whether the camera has already made its initial jump to the operator.
    // Both are keyed on scout.active so a fresh activation always re-follows and re-zooms, rather
    // than inheriting "the operator panned away" from a session they ended ten minutes ago.
    var followEnabled by remember(scout.active) { mutableStateOf(true) }
    var hasCenteredOnScout by remember(scout.active) { mutableStateOf(false) }

    // BitmapDescriptorFactory needs the Maps SDK initialised, which onMapLoaded guarantees — same
    // discipline as MapReviewScreen's drag handles.
    val scoutIcon: BitmapDescriptor? = remember(mapLoaded) {
        if (mapLoaded) scoutPositionIcon() else null
    }

    // --- Saved survey overlay ---
    // Observed rather than loaded once, so deleting the track that is currently drawn clears it off
    // the map instead of leaving a line pointing at a row that no longer exists.
    val surveyRepository = remember { SurveyTrackRepository.get(context) }
    val overlaidTrack by remember(overlaySurveyId) {
        overlaySurveyId?.let { surveyRepository.observeTrack(it) } ?: flowOf(null)
    }.collectAsState(initial = null)
    val overlaidPath = remember(overlaidTrack) {
        overlaidTrack?.points.orEmpty().map { it.position }
    }

    // --- Removed trees ---
    val removedTreeRepository = remember { RemovedTreeRepository.get(context) }
    val removedTrees by removedTreeRepository.observeRemovedTrees().collectAsState(initial = emptyList())
    val removedIcon: BitmapDescriptor? = remember(mapLoaded) {
        if (mapLoaded) removedTreeIcon() else null
    }
    val scope = rememberCoroutineScope()

    // Targeting mode: the reticle is showing and the operator is panning the map under it.
    var markingMode by remember { mutableStateOf(false) }
    var savingMark by remember { mutableStateOf(false) }
    // The tapped red marker whose info card is open, and the delete confirmation on top of it.
    var selectedRemovedTree by remember { mutableStateOf<RemovedTreeEntity?>(null) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    // Marks vs raw map. Plain remember, NOT rememberSaveable or a preference: every visit starts with
    // marks shown, so an operator can never open the map and wonder where their trees went.
    var marksShown by remember { mutableStateOf(true) }
    val layers = globalMapLayers(marksShown = marksShown, markingMode = markingMode)

    // Entering targeting mode DETACHES the camera from Scout.
    //
    // Without this the two features fight each other: Scout's follow effect re-centres on the
    // officer every 3 seconds, so an operator panning towards a tree would be dragged back before
    // they could confirm — and the reticle would end up saving the officer's own position instead of
    // the tree's. Panning would disable follow on its own (it is a GESTURE), but only AFTER the first
    // pan, which is exactly the moment the fight would be visible. Turning it off on entry is the
    // difference between "Scout paused while you aim" and "the map is broken".
    LaunchedEffect(markingMode) {
        if (markingMode) followEnabled = false
    }

    // DON'T FIGHT THE OPERATOR: any pan/pinch/rotate detaches the camera from the live position and
    // surfaces the Recenter button. Only GESTURE counts — our own follow animations report
    // DEVELOPER_ANIMATION, so the camera cannot switch itself off.
    LaunchedEffect(cameraPositionState.isMoving) {
        if (cameraPositionState.isMoving &&
            cameraPositionState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE
        ) {
            followEnabled = false
        }
    }

    // Follow the live position. The first lock-on also sets the zoom; after that only the centre
    // moves, so a zoom level the operator chose (by panning, which turns follow off, then hitting
    // Recenter) isn't overwritten on every fix.
    LaunchedEffect(scout.position, followEnabled, scout.active) {
        if (!scout.active || !followEnabled) return@LaunchedEffect
        val target = scout.position ?: return@LaunchedEffect
        val update = if (hasCenteredOnScout) {
            CameraUpdateFactory.newLatLng(target)
        } else {
            CameraUpdateFactory.newLatLngZoom(target, SCOUT_FOLLOW_ZOOM)
        }
        hasCenteredOnScout = true
        try {
            cameraPositionState.animate(update, SCOUT_FOLLOW_ANIMATION_MILLIS)
        } catch (cancellation: CancellationException) {
            // A newer fix superseded this animation; let the coroutine machinery handle it.
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Scout camera follow failed", error)
        }
    }

    // Camera positioning waits for onMapLoaded: newLatLngBounds needs the map's measured size and
    // throws IllegalStateException if it's still zero, which is exactly the state during first layout.
    LaunchedEffect(mapLoaded, canopies, focusedSessionId, scout.active) {
        if (!mapLoaded || canopies.isEmpty()) return@LaunchedEffect
        // An opened survey owns the camera: the operator asked to see THAT track, and the
        // all-trees fit would drop them somewhere else entirely. Handled in the dedicated effect
        // below rather than here, so it survives a session list re-emission.
        if (overlaySurveyId != null) return@LaunchedEffect
        // While scouting, the camera belongs to the operator's position (or to wherever they panned).
        // Without this, saving a session mid-walk would re-emit the Room Flow, change `canopies`, and
        // yank the camera back to the all-trees fit. Turning Scout off re-runs this and restores the
        // survey overview, which is the natural "I'm done walking" behaviour.
        if (scout.active) return@LaunchedEffect

        val target = focusedBounds
        if (target != null) {
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(
                    target.center,
                    if (focusedTrees.size > 1) FOCUSED_CLUSTER_ZOOM else FOCUSED_ZOOM
                )
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

    // Fit to the opened track once it has loaded. Keyed on the id and the point count rather than
    // the list, so it fires once per opened survey instead of on every unrelated recomposition — and
    // so it does not fight an operator who has panned away to look at something.
    LaunchedEffect(mapLoaded, overlaySurveyId, overlaidPath.size) {
        if (!mapLoaded || overlaySurveyId == null || overlaidPath.isEmpty()) return@LaunchedEffect
        val builder = LatLngBounds.builder()
        overlaidPath.forEach(builder::include)
        val paddingPx = with(density) { BOUNDS_PADDING.roundToPx() }
        runCatching {
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx)
            )
        }.onFailure {
            // A single-point track has zero-area bounds, which newLatLngBounds rejects. Centre on it.
            Log.w(TAG, "Could not fit the survey overlay; centring instead", it)
            cameraPositionState.move(
                CameraUpdateFactory.newLatLngZoom(overlaidPath.first(), FOCUSED_ZOOM)
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
            // RAW MAP: every overlay below stays in composition and is switched with `visible` (plus
            // clickable/onClick guards), rather than being removed. The native shapes survive a hide,
            // so showing them again is one cheap property change per object instead of re-adding
            // hundreds of polygons — and nothing is re-queried. The two exceptions are single markers
            // that can open an info window (focus pin, Scout dot): those leave composition, which is
            // the only way to guarantee their info window goes with them.

            // Cluster outlines first and lowest (zIndex just under the crowns), so a tap inside a crown
            // reaches the crown; the dotted ring itself opens the capture too.
            clusters.forEach { cluster ->
                ClusterOutlinePolygons(
                    outline = cluster.outline,
                    onClick = if (markingMode) null else ({ onOpenSession(cluster.sessionId) }),
                    visible = layers.clusterOutlines
                )
            }

            canopies.forEach { canopy ->
                val isFocused = canopy.sessionId == focusedSessionId
                val baseClass = canopy.treeClass
                Polygon(
                    points = canopy.vertices,
                    fillColor = if (isFocused) CANOPY_HIGHLIGHT_FILL_COLOR else canopyFillColor(baseClass),
                    strokeColor = if (isFocused) {
                        CANOPY_HIGHLIGHT_STROKE_COLOR
                    } else {
                        canopyStrokeColor(baseClass)
                    },
                    strokePattern = if (isFocused) null else canopyStrokePattern(baseClass),
                    strokeWidth = if (isFocused) {
                        CANOPY_HIGHLIGHT_STROKE_WIDTH
                    } else {
                        CANOPY_STROKE_WIDTH
                    },
                    // Polygons are NOT clickable by default — without this the taps below never fire.
                    // Muted while aiming: a stray tap during targeting would otherwise navigate away
                    // to a session detail and throw away the aim the operator was mid-way through.
                    // Hidden crowns are neither drawn nor tappable: a tap on the raw map where a canopy
                    // used to be must not open a session.
                    clickable = !markingMode && layers.canopies,
                    onClick = { if (layers.canopies) onOpenSession(canopy.sessionId) },
                    visible = layers.canopies,
                    // Keep the highlighted crown on top so an overlapping neighbour can't hide it.
                    zIndex = if (isFocused) 2f else 1f,
                    geodesic = false
                )
            }

            // A pin on the focused tree: the heavier amber outline reads well when the canopy fills
            // the screen, but this keeps it findable if the operator pans away and zooms out.
            if (layers.canopies) focused?.let { target ->
                val pinAt = focusedBounds?.center ?: target.bounds.center
                Marker(
                    state = remember(target.sessionId, pinAt) { MarkerState(position = pinAt) },
                    title = target.label.ifBlank { "(no label)" },
                    snippet = "Tap the canopy to open this session",
                    zIndex = 3f
                )
            }

            // --- A saved survey track, opened from the History list ---
            // Dashed and desaturated so it can never be mistaken for the live Scout breadcrumb, which
            // may well be on screen at the same time. Drawn first and lowest so it sits behind every
            // piece of survey data rather than over it.
            if (overlaidPath.size >= 2) {
                Polyline(
                    points = overlaidPath,
                    color = SAVED_TRACK_COLOR,
                    width = SAVED_TRACK_WIDTH,
                    pattern = listOf(Dash(SAVED_TRACK_DASH), Gap(SAVED_TRACK_GAP)),
                    visible = layers.surveyTrack,
                    zIndex = SAVED_TRACK_Z_INDEX
                )
            }

            // --- Removed trees: recorded absences, drawn red against the green canopies ---
            // Above the polygons (zIndex 1-3) so a mark inside a neighbouring crown stays findable,
            // below Scout's live position, which must never be hidden by data.
            removedTrees.forEach { removedTree ->
                Marker(
                    state = rememberUpdatedMarkerState(
                        LatLng(removedTree.latitude, removedTree.longitude)
                    ),
                    icon = removedIcon,
                    anchor = Offset(0.5f, 0.5f),
                    title = "Removed tree",
                    zIndex = REMOVED_TREE_Z_INDEX,
                    visible = layers.removedTrees,
                    onClick = {
                        // true = consume the tap. Without it the Maps SDK also opens its own info
                        // window and recentres the camera, which during aiming would move the
                        // reticle off target — and on the raw map would move the camera at all.
                        if (!markingMode && layers.removedTrees) selectedRemovedTree = removedTree
                        true
                    }
                )
            }

            // --- Scout overlay, layered OVER the canopies rather than replacing them ---
            // The canopy polygons stay exactly as they are in the non-Scout view: the whole point of
            // scouting on this map instead of a second one is seeing recorded trees relative to where
            // you are standing. The trail sits under them and the live marker well above them, so
            // neither the survey data nor the operator's own position can be hidden by the other.
            //
            // Hiding these is drawing only: `scout` is still collected and the service keeps
            // recording, so a survey walked on the raw map saves exactly as it would otherwise.
            if (scout.active) {
                if (scout.trail.size >= 2) {
                    Polyline(
                        points = scout.trail,
                        color = SCOUT_TRAIL_COLOR,
                        width = SCOUT_TRAIL_WIDTH,
                        visible = layers.scoutPosition,
                        zIndex = SCOUT_TRAIL_Z_INDEX
                    )
                }
                if (layers.scoutPosition) scout.position?.let { position ->
                    Marker(
                        // rememberUpdatedMarkerState, NOT remember { MarkerState(...) }: the same
                        // state object is retained and its position updated as fixes arrive, so the
                        // marker slides rather than being torn down and rebuilt every 3 seconds.
                        // (This is the exact opposite of MapReviewScreen's drag handles, which use a
                        // plain remember precisely so recomposition can't undo an operator's drag —
                        // here nothing drags it and the position IS the incoming data.)
                        state = rememberUpdatedMarkerState(position),
                        icon = scoutIcon,
                        anchor = Offset(0.5f, 0.5f),
                        title = "You are here",
                        snippet = scout.accuracyMeters?.let { "±${it.toInt()} m" },
                        zIndex = SCOUT_POSITION_Z_INDEX
                    )
                }
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
                // TREES, not sessions: a cluster of four adds four, here and in the legend.
                treeCount = canopies.size,
                classCounts = remember(canopies) { canopies.groupingBy { it.treeClass }.eachCount() },
                showLegend = layers.classLegend,
                onClearFocus = onClearFocus,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // The survey banner sits ABOVE the tree-count header rather than replacing it, because the
        // two answer different questions ("what am I looking at" vs "what is overlaid on it") and an
        // operator viewing a track still wants to know how many trees are on screen. It carries the
        // only way back to the normal map, mirroring the focused-tree "Show all" affordance.
        overlaidTrack?.let { track ->
            SurveyOverlayHeader(
                label = track.label,
                pointCount = track.pointCount,
                duration = formatSurveyDuration(track.startedAtMillis, track.endedAtMillis),
                drawable = overlaidPath.size >= 2,
                onClear = onClearSurveyOverlay,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
            )
        }

        // --- Removed-tree targeting ---
        // The reticle sits at the centre of this Box, which is the same point the map camera reports
        // as `position.target` — so what it covers is exactly what Confirm saves.
        if (markingMode) {
            CenterReticle(modifier = Modifier.align(Alignment.Center))
        }

        // The bottom slot is shared and only ever holds one thing. Targeting outranks the info card,
        // which outranks the Scout panel: each is a narrower, more recently-entered mode than the one
        // below it, and stacking them would bury the control the operator just asked for.
        val selected = selectedRemovedTree
        when {
            markingMode -> MarkTargetingBar(
                saving = savingMark,
                onCancel = { markingMode = false },
                onConfirm = {
                    // Snapshot the reticle position NOW, before the suspend below: the operator can
                    // keep panning while the GPS request is in flight, and the point that gets saved
                    // must be the one that was under the crosshair when they tapped Confirm.
                    val treePosition = cameraPositionState.position.target
                    savingMark = true
                    scope.launch {
                        // Best-effort and time-boxed: returns null rather than waiting when there is
                        // no fix, which is the supported "marked from a desk" case.
                        val officerFix = resolveOfficerFix(context)
                        val entity = buildRemovedTree(
                            treePosition = treePosition,
                            officerFix = officerFix,
                            markedAtMillis = System.currentTimeMillis()
                        )
                        runCatching { removedTreeRepository.insert(entity) }
                            .onSuccess { id ->
                                Log.d(TAG, "Marked removed tree id=" + id + " proximity=" + entity.proximityMeters)
                            }
                            .onFailure { Log.e(TAG, "Could not save the removed-tree mark", it) }
                        savingMark = false
                        markingMode = false
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            selected != null -> RemovedTreeInfoCard(
                removedTree = selected,
                onDelete = { confirmDeleteId = selected.id },
                onDismiss = { selectedRemovedTree = null },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // OUTSIDE the when above, deliberately: Scout has to be reachable with zero saved trees.
            // An officer walking a new block before recording anything is the normal first use of it,
            // and hiding the toggle behind the empty state would make that impossible.
            else -> ScoutPanel(
                scout = scout,
                onToggle = toggleScout,
                onSurveyToggle = toggleSurvey,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // "Mark removed" enters the mode; the mode owns its own Confirm/Cancel, so this hides while
        // targeting rather than sitting there offering to start something already started.
        //
        // Entering targeting forces marks back on and leaves them on: the operator is aiming at a gap
        // between recorded trees and has to see those trees. (globalMapLayers also overrides while
        // targeting, so no frame is ever drawn with the reticle over a raw map.)
        if (!markingMode && selected == null) {
            MarkRemovedButton(
                onClick = {
                    marksShown = true
                    markingMode = true
                },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        // Marks/raw-map toggle: on the right-hand control column, ABOVE "Mark removed" (Recenter goes
        // below it). That keeps it clear of the header and survey banner at the top, the Scout panel
        // and info card at the bottom, and the Google logo bottom-left. `offset` rather than padding
        // so it moves without changing the column's layout: its centre sits 64 dp above the screen
        // centre, leaving a ~20 dp gap over the Mark removed pill.
        if (layers.toggleButton) {
            MarksToggleButton(
                marksShown = marksShown,
                onToggle = { marksShown = !marksShown },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(y = (-64).dp)
                    .padding(12.dp)
            )
        }

        // Only while scouting AND detached — its presence is the entire signal that the camera has
        // stopped following, so there is no disabled/greyed state to reason about. Hidden during
        // targeting, where re-attaching the camera to the officer is precisely the wrong thing.
        if (scout.active && !followEnabled && !markingMode) {
            RecenterButton(
                onClick = { followEnabled = true },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = 72.dp)
            )
        }
    }

    confirmDeleteId?.let { id ->
        DeleteRemovedTreeDialog(
            onDismiss = { confirmDeleteId = null },
            onConfirm = {
                confirmDeleteId = null
                selectedRemovedTree = null
                scope.launch {
                    runCatching { removedTreeRepository.delete(id) }
                        .onFailure { Log.e(TAG, "Could not delete removed-tree mark " + id, it) }
                }
            }
        )
    }
}

/**
 * Banner shown while a saved survey track is overlaid, and the way back out of it.
 *
 * [drawable] is false for a track with fewer than two points, which cannot be drawn as a line. Saying
 * so is the point: an operator who opened a survey and sees no track needs to know the record is
 * short, not wonder whether the map is broken.
 */
@Composable
private fun SurveyOverlayHeader(
    label: String,
    pointCount: Int,
    duration: String,
    drawable: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f))
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(SAVED_TRACK_COLOR)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = if (drawable) {
                        "$pointCount points · $duration"
                    } else {
                        "$pointCount points · too short to draw"
                    },
                    fontSize = 11.sp,
                    color = Color.DarkGray
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear) { Text("Clear", fontSize = 13.sp) }
        }
    }
}

/** Enters targeting mode. Carries the removed-tree red so it reads as part of that feature. */
@Composable
private fun MarkRemovedButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .padding(12.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(REMOVED_TREE_COLOR)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Mark removed",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black
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
 * Floating header: either the tree count with a per-class legend, or which capture is being focused
 * plus a way out of that focus. Without the escape hatch, arriving via "View on map" would strand
 * the operator zoomed onto one crown with no obvious route back to the whole survey.
 *
 * Every count is of TREES — the session row's own tree plus each cluster member — because that is
 * what the colours on the map are.
 */
@Composable
private fun MapHeader(
    focusedLabel: String?,
    treeCount: Int,
    classCounts: Map<TreeClass, Int>,
    showLegend: Boolean,
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
                Column {
                    Text(
                        text = if (treeCount == 1) "1 tree" else "$treeCount trees",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    // Off on the raw map: it explains colours that are no longer drawn.
                    if (showLegend) ClassLegend(classCounts)
                }
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
 * One dot + count per class that is actually on the map, in the enum's order, so the legend only
 * ever explains colours the operator can see.
 */
@Composable
private fun ClassLegend(classCounts: Map<TreeClass, Int>) {
    val shown = TreeClass.entries.filter { (classCounts[it] ?: 0) > 0 }
    if (shown.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { treeClass ->
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(canopyStrokeColor(treeClass))
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "${legendName(treeClass)} ${classCounts[treeClass]}",
                fontSize = 11.sp,
                color = Color.DarkGray
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

private fun legendName(treeClass: TreeClass): String = when (treeClass) {
    TreeClass.PALM -> "Palm"
    TreeClass.BROADLEAF_EVERGREEN -> "Evergreen"
    TreeClass.BROADLEAF_DECIDUOUS -> "Deciduous"
    TreeClass.BROADLEAF_UNCONFIRMED -> "Broadleaf"
    TreeClass.BAMBOO -> "Bamboo"
    TreeClass.OTHER -> "Other"
    TreeClass.UNCLASSIFIED -> "Unclassified"
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
        treeNumber = 1,
        label = label,
        vertices = vertices,
        bounds = builder.build(),
        treeClass = parseTreeClass(treeClass)
    )
}

/**
 * A cluster member, drawable. Same source-of-truth rule as the session row: the confirmed outline,
 * regenerated from the stored projection and diameter with the member's own seed only if the stored
 * outline is unusable.
 */
private fun ClusterMemberEntity.toCanopyOverlay(sessionLabel: String, sessionTimestampMillis: Long): CanopyOverlay? {
    val vertices = polygonVertices.takeIf { it.size >= 3 } ?: generateCanopyPolygon(
        center = LatLng(projectedLatitude, projectedLongitude),
        baseRadiusMeters = (canopyDiameterMeters / 2.0).coerceIn(MIN_CANOPY_RADIUS_METERS, MAX_CANOPY_RADIUS_METERS),
        seed = clusterTreeSeedFor(sessionTimestampMillis, memberIndex)
    )
    val builder = LatLngBounds.builder()
    vertices.forEach(builder::include)
    return CanopyOverlay(
        sessionId = sessionId,
        treeNumber = memberIndex,
        label = sessionLabel,
        vertices = vertices,
        bounds = builder.build(),
        treeClass = parseTreeClass(treeClass)
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

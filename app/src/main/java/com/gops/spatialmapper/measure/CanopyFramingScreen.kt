package com.gops.spatialmapper.measure

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gops.spatialmapper.capture.CameraIntrinsicsSnapshot
import com.gops.spatialmapper.capture.DepthMap
import com.gops.spatialmapper.capture.SavedDepthImage
import com.gops.spatialmapper.capture.loadDepthMap
import com.gops.spatialmapper.capture.rotateBitmapClockwise
import com.gops.spatialmapper.map.formatCanopyDiameter
import com.gops.spatialmapper.telemetry.CaptureTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val TAG = "SpatialMapperFraming"

/** Long-side cap for the displayed bitmap. Well above any phone screen; keeps the decode cheap. */
private const val DISPLAY_IMAGE_MAX_SIDE = 1600

/** Visual radius of a drag handle. */
private val HANDLE_RADIUS = 11.dp

/** How close a touch must land to a handle to grab it — deliberately larger than the handle itself. */
private val HANDLE_GRAB_RADIUS = 30.dp

/** Floor on either ellipse radius, so the shape can never be dragged into nothing. */
private val MIN_ELLIPSE_RADIUS = 12.dp

private val CANOPY_STROKE = Color(0xFF66BB6A)
private val CANOPY_FILL = Color(0x1A4CAF50)
private val HANDLE_HORIZONTAL = Color(0xFF66BB6A)
private val HANDLE_VERTICAL = Color(0xFFFFD54F)
/** Ellipses that aren't being edited: present, but quieter than the one under the operator's thumb. */
private val INACTIVE_STROKE = Color(0xCCFFFFFF)
private val INACTIVE_FILL = Color(0x14FFFFFF)
/** A tree whose distance is an estimate, or that has none: the same amber as the size warning. */
private val WARNING = Color(0xFFFFB74D)
private val ACTIVE_ROW = Color(0xFF2E3B2F)

/** Which part of the ellipse a drag currently owns. */
private enum class Grab { NONE, BODY, HORIZONTAL, VERTICAL }

/** One tree's live readout on this screen. */
private data class TreeReadout(
    val treeNumber: Int,
    val measurement: CanopyMeasurement?,
    val distance: TreeDistance?
)

/**
 * Post-capture canopy framing: the operator drags an ellipse over the still photo they just took to
 * measure the crown — and, for a cluster, one more ellipse per neighbouring tree.
 *
 * This replaces the automatic MobileSAM segmentation step. The tradeoff is deliberate: segmentation
 * could silently pick the wrong mask (a branch, a leaf, the tree behind) and the operator had no way
 * to see or correct it. Here what is measured is exactly what is drawn on screen, so a mistake is
 * visible before it is saved.
 *
 * TREE 1 IS THE ORIGINAL ELLIPSE and it is measured exactly as before, from [distanceMeters] (the
 * reading at the frame centre). It can't be deleted. The "+" button adds trees 2..[MAX_CLUSTER_TREES],
 * each measured at its own position: the median of the saved depth image around its centre, or the
 * ground-plane estimate at its bottom edge when there's no depth there (see [resolveMemberDistance]).
 *
 * One ellipse is ACTIVE at a time and only it carries handles, so ten sets of handles never compete
 * for the same thumb. Tapping an ellipse activates it; dragging inside one activates and moves it.
 *
 * COORDINATE SPACES — the one thing in here that is easy to get silently wrong:
 *  - Ellipses are manipulated and stored in DISPLAY pixels, with the origin at the top-left of the
 *    photo as drawn (not the screen).
 *  - The pinhole conversion and the depth lookup need CAPTURED-IMAGE pixels, because that is the
 *    space [CameraIntrinsicsSnapshot.focalLengthPixels] and the depth alignment are expressed in.
 *  - [displayToImagePixelScale] bridges the two. The photo is fitted into the available space by
 *    explicit arithmetic here rather than by [ContentScale.Fit], so the drawn rectangle's size is a
 *    value we hold rather than one we'd have to infer from the layout.
 *
 * @param photoPath   the JPEG the shutter just wrote.
 * @param intrinsics  the camera model captured alongside it; its rotation brings the photo upright.
 * @param distanceMeters range to the frame center at shutter time (ARCore depth or the fallback).
 * @param capture     the shutter telemetry: heading and pitch for the members' bearing and fallback.
 * @param depth       the depth image saved with the photo, or null (CameraX path / no depth).
 * @param onCancel    abandon this capture entirely; the caller discards the photo.
 * @param onConfirm   proceed to map review with the centre tree's measurement and the members'.
 */
@Composable
fun CanopyFramingScreen(
    photoPath: String,
    intrinsics: CameraIntrinsicsSnapshot,
    distanceMeters: Float,
    capture: CaptureTelemetry,
    depth: SavedDepthImage?,
    cameraHeightMeters: Double,
    onCancel: () -> Unit,
    onConfirm: (CanopyMeasurement, List<ClusterMemberMeasurement>) -> Unit,
    modifier: Modifier = Modifier
) {
    var photo by remember(photoPath) { mutableStateOf<ImageBitmap?>(null) }
    var loadFailed by remember(photoPath) { mutableStateOf(false) }

    LaunchedEffect(photoPath, intrinsics.rotationDegrees) {
        val decoded = withContext(Dispatchers.IO) {
            loadUprightBitmap(photoPath, intrinsics.rotationDegrees)
        }
        if (decoded == null) loadFailed = true else photo = decoded.asImageBitmap()
    }

    // The depth image is only needed for members; until it loads (a few ms), they fall back.
    var depthMap by remember(depth?.path) { mutableStateOf<DepthMap?>(null) }
    LaunchedEffect(depth?.path) {
        depthMap = withContext(Dispatchers.IO) { loadDepthMap(depth?.path) }
    }

    // Ellipses in display pixels — index 0 is tree 1, the centre tree — plus the drawn photo size
    // those pixels are relative to. Kept here (not inside the photo box) so a layout change can
    // rescale them instead of resetting the operator's work — see the rescale below.
    var ellipses by remember(photoPath) { mutableStateOf<List<CanopyEllipse>>(emptyList()) }
    var activeIndex by remember(photoPath) { mutableIntStateOf(0) }
    var drawnSize by remember(photoPath) { mutableStateOf(Size.Zero) }

    val scale = displayToImagePixelScale(
        intrinsics.longestSidePixels,
        max(drawnSize.width, drawnSize.height)
    )
    val (photoWidth, photoHeight) = intrinsics.uprightImageSize()
    val memberInputs = MemberDistanceInputs(
        photoWidthPixels = photoWidth,
        photoHeightPixels = photoHeight,
        focalLengthPixels = intrinsics.focalLengthPixels.toDouble(),
        depthMap = depthMap,
        depthAlignment = depth?.alignment,
        pitchDegrees = capture.pitchDegrees?.toDouble(),
        cameraHeightMeters = cameraHeightMeters
    )
    val heading = capture.headingDegrees?.toDouble()

    val centreMeasurement = ellipses.firstOrNull()?.let { centre ->
        scale?.let {
            canopyMeasurementFromEllipse(
                ellipse = centre,
                displayToImageScale = it,
                distanceMeters = distanceMeters,
                focalLengthPixels = intrinsics.focalLengthPixels
            )
        }
    }
    val members: List<ClusterMemberMeasurement?> = ellipses.drop(1).mapIndexed { i, ellipse ->
        if (scale == null || heading == null) {
            null
        } else {
            measureClusterMember(
                treeNumber = i + 2,
                displayEllipse = ellipse,
                displayToImageScale = scale,
                captureHeadingDegrees = heading,
                inputs = memberInputs
            )
        }
    }
    val readouts = buildList {
        if (ellipses.isNotEmpty()) {
            add(
                TreeReadout(
                    treeNumber = CENTRE_TREE_NUMBER,
                    measurement = centreMeasurement,
                    distance = capture.distanceMethod?.let { TreeDistance(distanceMeters.toDouble(), it) }
                )
            )
        }
        members.forEachIndexed { i, member ->
            add(TreeReadout(i + 2, member?.canopy, member?.distance))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            val current = photo
            when {
                loadFailed -> Text(
                    text = "Couldn't open the captured photo.",
                    color = Color.White,
                    fontSize = 15.sp
                )
                current == null -> CircularProgressIndicator(color = Color.White)
                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Fit the photo into the available space, preserving aspect. Computed rather than
                    // delegated so the drawn size is known exactly — the pixel scale depends on it.
                    val fitScale = min(
                        constraints.maxWidth.toFloat() / current.width.toFloat(),
                        constraints.maxHeight.toFloat() / current.height.toFloat()
                    )
                    val drawnWidth = current.width * fitScale
                    val drawnHeight = current.height * fitScale

                    LaunchedEffect(drawnWidth, drawnHeight) {
                        val previousSize = drawnSize
                        ellipses = if (
                            ellipses.isEmpty() ||
                            previousSize.width <= 0f ||
                            previousSize.height <= 0f
                        ) {
                            listOf(defaultCanopyEllipse(drawnWidth, drawnHeight))
                        } else {
                            // The photo area changed size (e.g. device rotation). Rescale rather than
                            // reset, so the operator doesn't lose a careful framing to a layout pass.
                            val scaleX = drawnWidth / previousSize.width
                            val scaleY = drawnHeight / previousSize.height
                            ellipses.map { previous ->
                                CanopyEllipse(
                                    centerX = previous.centerX * scaleX,
                                    centerY = previous.centerY * scaleY,
                                    radiusX = previous.radiusX * scaleX,
                                    radiusY = previous.radiusY * scaleY
                                )
                            }
                        }
                        drawnSize = Size(drawnWidth, drawnHeight)
                    }

                    with(LocalDensity.current) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(drawnWidth.toDp(), drawnHeight.toDp())
                        ) {
                            Image(
                                bitmap = current,
                                contentDescription = "Captured plant photo",
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (ellipses.isNotEmpty()) {
                                ClusterOverlay(
                                    ellipses = ellipses,
                                    activeIndex = activeIndex.coerceIn(0, ellipses.lastIndex),
                                    flagged = readouts.map { it.distance == null || it.distance.isFallback },
                                    onActivate = { activeIndex = it },
                                    onEllipseChange = { index, shape ->
                                        ellipses = ellipses.toMutableList().also { it[index] = shape }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        FramingPanel(
            readouts = readouts,
            activeIndex = activeIndex,
            canAdd = ellipses.isNotEmpty() && ellipses.size < MAX_CLUSTER_TREES,
            onActivate = { activeIndex = it },
            onAdd = {
                ellipses = ellipses + placeNewMemberEllipse(ellipses, drawnSize.width, drawnSize.height)
                activeIndex = ellipses.lastIndex
            },
            onDeleteActive = {
                // Never the centre tree. Nothing is saved yet, so no confirmation: the later
                // trees simply move up a number, keeping labels 1..N contiguous.
                if (activeIndex > 0 && activeIndex < ellipses.size) {
                    ellipses = ellipses.toMutableList().also { it.removeAt(activeIndex) }
                    activeIndex = (activeIndex - 1).coerceAtLeast(0)
                }
            },
            onCancel = onCancel,
            onConfirm = {
                val measuredMembers = members.filterNotNull()
                if (centreMeasurement != null && measuredMembers.size == members.size) {
                    onConfirm(centreMeasurement, measuredMembers)
                }
            }
        )
    }
}

/**
 * Every ellipse on the photo: outlines and number labels for all of them, the two perpendicular
 * diameter handles for the [activeIndex] one only, and all the touch handling.
 *
 * Hit-testing is done here rather than with per-handle `pointerInput` modifiers so that a touch is
 * resolved against the CURRENT geometry in one place, with a defined winner: the active ellipse's
 * handles, then the active ellipse's body, then the smallest other ellipse under the finger (so a
 * small tree framed on top of a big crown can still be picked). Drags apply the frame-to-frame DELTA
 * rather than jumping to the touch position, so grabbing slightly off-centre doesn't snap anything.
 */
@Composable
private fun ClusterOverlay(
    ellipses: List<CanopyEllipse>,
    activeIndex: Int,
    flagged: List<Boolean>,
    onActivate: (Int) -> Unit,
    onEllipseChange: (Int, CanopyEllipse) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { HANDLE_RADIUS.toPx() }
    val grabRadiusPx = with(density) { HANDLE_GRAB_RADIUS.toPx() }
    val minRadiusPx = with(density) { MIN_ELLIPSE_RADIUS.toPx() }
    val textMeasurer = rememberTextMeasurer()

    // The gesture detectors are installed once (pointerInput(Unit)); these keep them reading the
    // latest geometry and callbacks instead of the ones captured on first composition.
    val currentEllipses by rememberUpdatedState(ellipses)
    val currentActive by rememberUpdatedState(activeIndex)
    val currentOnActivate by rememberUpdatedState(onActivate)
    val currentOnChange by rememberUpdatedState(onEllipseChange)
    var grab by remember { mutableStateOf(Grab.NONE) }
    var grabIndex by remember { mutableIntStateOf(0) }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { point ->
                    ellipseAt(currentEllipses, currentActive, point)?.let(currentOnActivate)
                }
            }
            .pointerInput(Unit) {
                val bounds = Size(size.width.toFloat(), size.height.toFloat())
                detectDragGestures(
                    onDragStart = { start ->
                        val active = currentEllipses.getOrNull(currentActive)
                        val onActive = active?.let { grabTarget(it, start, grabRadiusPx) } ?: Grab.NONE
                        if (onActive != Grab.NONE) {
                            grab = onActive
                            grabIndex = currentActive
                        } else {
                            // Dragging another ellipse's body makes it the active one, so the handles
                            // follow whatever the operator is working on.
                            val other = ellipseAt(currentEllipses, currentActive, start)
                            if (other != null) {
                                currentOnActivate(other)
                                grab = Grab.BODY
                                grabIndex = other
                            } else {
                                grab = Grab.NONE
                            }
                        }
                    },
                    onDragEnd = { grab = Grab.NONE },
                    onDragCancel = { grab = Grab.NONE }
                ) { change, delta ->
                    val target = currentEllipses.getOrNull(grabIndex)
                    if (grab != Grab.NONE && target != null) {
                        change.consume()
                        currentOnChange(grabIndex, applyDrag(target, grab, delta, bounds, minRadiusPx))
                    }
                }
            }
    ) {
        // Inactive first, so the active ellipse is always drawn on top of any overlap.
        ellipses.forEachIndexed { index, ellipse ->
            if (index != activeIndex) drawInactiveEllipse(ellipse, flagged.getOrElse(index) { false })
        }
        ellipses.getOrNull(activeIndex)?.let { drawActiveEllipse(it, handleRadiusPx) }
        ellipses.forEachIndexed { index, ellipse ->
            drawTreeLabel(
                textMeasurer = textMeasurer,
                ellipse = ellipse,
                number = index + 1,
                active = index == activeIndex,
                flagged = flagged.getOrElse(index) { false }
            )
        }
    }
}

/**
 * Which ellipse a touch at [point] means: the active one if it's under the finger, else the
 * smallest one that is. Null when the touch is on bare photo.
 */
private fun ellipseAt(ellipses: List<CanopyEllipse>, activeIndex: Int, point: Offset): Int? {
    ellipses.getOrNull(activeIndex)?.let { if (it.contains(point.x, point.y)) return activeIndex }
    return ellipses.indices
        .filter { ellipses[it].contains(point.x, point.y) }
        .minByOrNull { ellipses[it].radiusX * ellipses[it].radiusY }
}

private fun DrawScope.drawActiveEllipse(ellipse: CanopyEllipse, handleRadiusPx: Float) {
    val center = Offset(ellipse.centerX, ellipse.centerY)
    val topLeft = Offset(ellipse.centerX - ellipse.radiusX, ellipse.centerY - ellipse.radiusY)
    val ovalSize = Size(ellipse.radiusX * 2f, ellipse.radiusY * 2f)

    drawOval(color = CANOPY_FILL, topLeft = topLeft, size = ovalSize)
    // Dark under-stroke first: a single bright outline disappears against pale sky or bark.
    drawOval(
        color = Color.Black.copy(alpha = 0.5f),
        topLeft = topLeft,
        size = ovalSize,
        style = Stroke(width = 6f)
    )
    drawOval(color = CANOPY_STROKE, topLeft = topLeft, size = ovalSize, style = Stroke(width = 3f))

    // The two diameters being measured, drawn so the operator can see exactly what each handle
    // controls rather than inferring it from the outline.
    drawLine(
        color = CANOPY_STROKE.copy(alpha = 0.7f),
        start = Offset(center.x - ellipse.radiusX, center.y),
        end = Offset(center.x + ellipse.radiusX, center.y),
        strokeWidth = 2f
    )
    drawLine(
        color = HANDLE_VERTICAL.copy(alpha = 0.7f),
        start = Offset(center.x, center.y - ellipse.radiusY),
        end = Offset(center.x, center.y + ellipse.radiusY),
        strokeWidth = 2f
    )

    drawHandle(Offset(center.x + ellipse.radiusX, center.y), handleRadiusPx, HANDLE_HORIZONTAL)
    drawHandle(Offset(center.x, center.y - ellipse.radiusY), handleRadiusPx, HANDLE_VERTICAL)
}

/** No handles; dashed in amber when that tree's distance is an estimate or missing. */
private fun DrawScope.drawInactiveEllipse(ellipse: CanopyEllipse, flagged: Boolean) {
    val topLeft = Offset(ellipse.centerX - ellipse.radiusX, ellipse.centerY - ellipse.radiusY)
    val ovalSize = Size(ellipse.radiusX * 2f, ellipse.radiusY * 2f)
    drawOval(color = INACTIVE_FILL, topLeft = topLeft, size = ovalSize)
    drawOval(
        color = Color.Black.copy(alpha = 0.45f),
        topLeft = topLeft,
        size = ovalSize,
        style = Stroke(width = 5f)
    )
    drawOval(
        color = if (flagged) WARNING else INACTIVE_STROKE,
        topLeft = topLeft,
        size = ovalSize,
        style = Stroke(
            width = 2.5f,
            pathEffect = if (flagged) PathEffect.dashPathEffect(floatArrayOf(14f, 9f)) else null
        )
    )
}

/** The tree's number in a small badge at the ellipse's top-left, "⚠" added when flagged. */
private fun DrawScope.drawTreeLabel(
    textMeasurer: TextMeasurer,
    ellipse: CanopyEllipse,
    number: Int,
    active: Boolean,
    flagged: Boolean
) {
    val layout = textMeasurer.measure(
        text = if (flagged) "$number ⚠" else "$number",
        style = TextStyle(
            color = if (active) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    )
    val padX = 7f
    val padY = 3f
    // Upper-left of the bounding box, nudged inside the photo so a framing near the edge keeps its
    // label readable.
    val left = (ellipse.centerX - ellipse.radiusX * 0.75f - layout.size.width / 2f)
        .coerceIn(0f, max(0f, size.width - layout.size.width - 2 * padX))
    val top = (ellipse.centerY - ellipse.radiusY * 0.75f - layout.size.height / 2f)
        .coerceIn(0f, max(0f, size.height - layout.size.height - 2 * padY))
    drawRoundRect(
        color = when {
            active -> CANOPY_STROKE
            flagged -> WARNING.copy(alpha = 0.85f)
            else -> Color.Black.copy(alpha = 0.6f)
        },
        topLeft = Offset(left, top),
        size = Size(layout.size.width + 2 * padX, layout.size.height + 2 * padY),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
    )
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

private fun DrawScope.drawHandle(
    center: Offset,
    radius: Float,
    color: Color
) {
    drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = radius + 2f, center = center)
    drawCircle(color = color, radius = radius, center = center)
    drawCircle(color = Color.White, radius = radius, center = center, style = Stroke(width = 3f))
}

/**
 * Resolves which part of the ellipse a touch at [point] should drag.
 *
 * Handles win over the body, and the nearer handle wins over the further one — otherwise the two
 * would fight whenever the ellipse is small enough for their grab areas to overlap.
 */
private fun grabTarget(ellipse: CanopyEllipse, point: Offset, grabRadius: Float): Grab {
    val horizontal = Offset(ellipse.centerX + ellipse.radiusX, ellipse.centerY)
    val vertical = Offset(ellipse.centerX, ellipse.centerY - ellipse.radiusY)
    val toHorizontal = (point - horizontal).getDistance()
    val toVertical = (point - vertical).getDistance()

    if (toHorizontal <= grabRadius && toHorizontal <= toVertical) return Grab.HORIZONTAL
    if (toVertical <= grabRadius) return Grab.VERTICAL

    // Touches outside the ellipse do nothing on purpose: an accidental brush of the photo shouldn't
    // move a framing the operator has already settled.
    return if (ellipse.contains(point.x, point.y)) Grab.BODY else Grab.NONE
}

/**
 * Applies one drag step.
 *
 * The clamping exists to keep both handles REACHABLE. Touch events and drawing are both confined to
 * the photo's box, so a handle pushed past the right or top edge would vanish and could never be
 * grabbed back — the operator would have to cancel and recapture. So the horizontal handle is kept
 * inside the right edge and the vertical handle inside the top edge, and moving the ellipse respects
 * the same limits.
 *
 * The left and bottom edges are deliberately NOT clamped: no handle lives there, so the ellipse is
 * free to extend past them, which is what lets a crown that is slightly cut off at the frame edge
 * still be framed by extrapolating. The radius floor keeps the shape grabbable at all times.
 */
private fun applyDrag(
    ellipse: CanopyEllipse,
    grab: Grab,
    delta: Offset,
    bounds: Size,
    minRadius: Float
): CanopyEllipse = when (grab) {
    // Handle sits to the RIGHT of center, so dragging right grows the horizontal diameter.
    Grab.HORIZONTAL -> ellipse.copy(
        radiusX = (ellipse.radiusX + delta.x)
            .coerceIn(minRadius, max(minRadius, bounds.width - ellipse.centerX))
    )
    // Handle sits ABOVE center, and screen y grows downward, so dragging up grows the vertical one.
    Grab.VERTICAL -> ellipse.copy(
        radiusY = (ellipse.radiusY - delta.y)
            .coerceIn(minRadius, max(minRadius, ellipse.centerY))
    )
    Grab.BODY -> ellipse.copy(
        centerX = (ellipse.centerX + delta.x)
            .coerceIn(0f, max(0f, bounds.width - ellipse.radiusX)),
        centerY = (ellipse.centerY + delta.y)
            .coerceIn(min(ellipse.radiusY, bounds.height), bounds.height)
    )
    Grab.NONE -> ellipse
}

/**
 * Per-tree live readouts, the warnings, add/delete, and the two exits.
 *
 * The size warning is advisory, per tree. Unlike the segmentation confidence gate this screen
 * replaced, there is no forced retry: the operator is looking at the ellipses on top of the actual
 * plants, so they can judge an unusual-looking size better than a constant can. What a warning
 * changes is the confirm button's wording, so proceeding past it is a deliberate act.
 *
 * A member with NO distance at all is different: it can't be placed on the map, so confirm waits
 * until it is moved (its bottom edge onto the trunk base) or deleted.
 */
@Composable
private fun FramingPanel(
    readouts: List<TreeReadout>,
    activeIndex: Int,
    canAdd: Boolean,
    onActivate: (Int) -> Unit,
    onAdd: () -> Unit,
    onDeleteActive: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val allMeasured = readouts.isNotEmpty() && readouts.all { it.measurement != null }
    val anyImplausible = readouts.any { it.measurement?.isPlausible == false }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1B1B1B))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = if (readouts.size > 1) "Frame the canopies" else "Frame the canopy",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Drag the green handle for width and the amber handle for height. " +
                "Tap a tree to edit it; + adds a neighbouring tree.",
            color = Color(0xFFBDBDBD),
            fontSize = 12.sp
        )

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp)
                .verticalScroll(rememberScrollState())
        ) {
            readouts.forEachIndexed { index, readout ->
                TreeReadoutRow(
                    readout = readout,
                    active = index == activeIndex,
                    onClick = { onActivate(index) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (canAdd) "+ Add tree" else "Max $MAX_CLUSTER_TREES trees",
                    color = if (canAdd) Color.White else Color.Gray
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onDeleteActive,
                enabled = activeIndex > 0,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (activeIndex > 0) "Delete tree ${activeIndex + 1}" else "Delete tree",
                    color = if (activeIndex > 0) Color(0xFFEF9A9A) else Color.Gray
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel", color = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onConfirm,
                enabled = allMeasured,
                modifier = Modifier.weight(1.6f)
            ) {
                Text(
                    text = when {
                        !allMeasured && readouts.size > 1 -> "A tree has no distance"
                        anyImplausible -> if (readouts.size > 1) "Use these sizes anyway" else "Use this size anyway"
                        readouts.size > 1 -> "Confirm ${readouts.size} trees"
                        else -> "Confirm size"
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * One line per tree: number, diameter (width × height), and distance with its method. Anything the
 * operator should look at twice — an estimated distance, a missing one, an implausible size — is in
 * amber, worded rather than only coloured.
 */
@Composable
private fun TreeReadoutRow(readout: TreeReadout, active: Boolean, onClick: () -> Unit) {
    val measurement = readout.measurement
    val distance = readout.distance
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) ACTIVE_ROW else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        val title = buildString {
            append("${readout.treeNumber}")
            if (readout.treeNumber == CENTRE_TREE_NUMBER) append(" (centre)")
            append(" · ")
            append(
                measurement?.let { "${formatCanopyDiameter(it.diameterMeters)} across" } ?: "not measured"
            )
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = if (active) 15.sp else 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
        val detail = buildString {
            if (measurement != null) {
                append("width ${formatCanopyDiameter(measurement.horizontalDiameterMeters)}")
                append(" × height ${formatCanopyDiameter(measurement.verticalDiameterMeters)} · ")
            }
            append(
                when {
                    distance == null -> "no distance — put its bottom edge on the trunk base"
                    distance.isFallback -> "at %.1f m ⚠ estimated (ground plane, no depth)".format(distance.meters)
                    else -> "at %.1f m (depth)".format(distance.meters)
                }
            )
        }
        Text(
            text = detail,
            color = if (distance == null || distance.isFallback) WARNING else Color(0xFFBDBDBD),
            fontSize = 12.sp
        )
        if (measurement != null && !measurement.isPlausible) {
            Text(
                text = "⚠ This size looks unusual — check the framing.",
                color = WARNING,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Decodes [path] downsampled to at most [DISPLAY_IMAGE_MAX_SIDE] px on its long side and rotates it
 * upright via [rotateBitmapClockwise].
 *
 * In practice every capture path now writes its JPEG bytes already upright (see
 * [com.gops.spatialmapper.capture.saveArFrameAsJpeg]'s doc), so [rotationDegrees] is 0 here and this
 * is a no-op — the parameter and the rotation call stay as defense in depth for any future capture
 * path that doesn't uphold that invariant, rather than silently trusting it.
 *
 * Downsampling costs no accuracy: the ellipse is measured in display pixels and converted back to
 * captured-image pixels through the camera intrinsics, which are independent of this bitmap's size.
 */
private fun loadUprightBitmap(path: String, rotationDegrees: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val longestSide = max(bounds.outWidth, bounds.outHeight)
        while (longestSide / (sampleSize * 2) >= DISPLAY_IMAGE_MAX_SIDE) sampleSize *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        rotateBitmapClockwise(decoded, rotationDegrees)
    } catch (e: Exception) {
        Log.w(TAG, "Decoding $path failed", e)
        null
    }
}

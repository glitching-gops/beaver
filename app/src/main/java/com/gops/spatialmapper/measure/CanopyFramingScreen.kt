package com.gops.spatialmapper.measure

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gops.spatialmapper.capture.CameraIntrinsicsSnapshot
import com.gops.spatialmapper.map.formatCanopyDiameter
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

/** Which part of the ellipse a drag currently owns. */
private enum class Grab { NONE, BODY, HORIZONTAL, VERTICAL }

/**
 * Post-capture canopy framing: the operator drags an ellipse over the still photo they just took to
 * measure the crown.
 *
 * This replaces the automatic MobileSAM segmentation step. The tradeoff is deliberate: segmentation
 * could silently pick the wrong mask (a branch, a leaf, the tree behind) and the operator had no way
 * to see or correct it. Here what is measured is exactly what is drawn on screen, so a mistake is
 * visible before it is saved.
 *
 * COORDINATE SPACES — the one thing in here that is easy to get silently wrong:
 *  - The ellipse is manipulated and stored in DISPLAY pixels, with the origin at the top-left of the
 *    photo as drawn (not the screen).
 *  - The pinhole conversion needs spans in CAPTURED-IMAGE pixels, because that is the space
 *    [CameraIntrinsicsSnapshot.focalLengthPixels] is expressed in.
 *  - [displayToImagePixelScale] bridges the two. The photo is fitted into the available space by
 *    explicit arithmetic here rather than by [ContentScale.Fit], so the drawn rectangle's size is a
 *    value we hold rather than one we'd have to infer from the layout.
 *
 * @param photoPath   the JPEG the shutter just wrote.
 * @param intrinsics  the camera model captured alongside it; its rotation brings the photo upright.
 * @param distanceMeters range to the frame center at shutter time (ARCore depth or the fallback).
 * @param onCancel    abandon this capture entirely; the caller discards the photo.
 * @param onConfirm   proceed to map review with the measured canopy.
 */
@Composable
fun CanopyFramingScreen(
    photoPath: String,
    intrinsics: CameraIntrinsicsSnapshot,
    distanceMeters: Float,
    onCancel: () -> Unit,
    onConfirm: (CanopyMeasurement) -> Unit,
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

    // Ellipse in display pixels, plus the drawn photo size those pixels are relative to. Kept here
    // (not inside the photo box) so a layout change can rescale it instead of resetting the operator's
    // work — see the rescale in the LaunchedEffect below.
    var ellipse by remember(photoPath) { mutableStateOf<CanopyEllipse?>(null) }
    var drawnSize by remember(photoPath) { mutableStateOf(Size.Zero) }

    val measurement = ellipse?.let { current ->
        val longestDrawnSide = max(drawnSize.width, drawnSize.height)
        displayToImagePixelScale(intrinsics.longestSidePixels, longestDrawnSide)?.let { scale ->
            canopyMeasurementFromEllipse(
                ellipse = current,
                displayToImageScale = scale,
                distanceMeters = distanceMeters,
                focalLengthPixels = intrinsics.focalLengthPixels
            )
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
                        val previous = ellipse
                        val previousSize = drawnSize
                        ellipse = if (
                            previous == null ||
                            previousSize.width <= 0f ||
                            previousSize.height <= 0f
                        ) {
                            defaultCanopyEllipse(drawnWidth, drawnHeight)
                        } else {
                            // The photo area changed size (e.g. device rotation). Rescale rather than
                            // reset, so the operator doesn't lose a careful framing to a layout pass.
                            val scaleX = drawnWidth / previousSize.width
                            val scaleY = drawnHeight / previousSize.height
                            CanopyEllipse(
                                centerX = previous.centerX * scaleX,
                                centerY = previous.centerY * scaleY,
                                radiusX = previous.radiusX * scaleX,
                                radiusY = previous.radiusY * scaleY
                            )
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
                            ellipse?.let { shape ->
                                EllipseOverlay(
                                    ellipse = shape,
                                    onEllipseChange = { ellipse = it },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        FramingPanel(
            measurement = measurement,
            distanceMeters = distanceMeters,
            onCancel = onCancel,
            onConfirm = { measurement?.let(onConfirm) }
        )
    }
}

/**
 * The ellipse itself: outline, the two perpendicular diameter handles, and all the drag handling.
 *
 * Hit-testing is done here rather than with per-handle `pointerInput` modifiers so that a touch is
 * resolved against the CURRENT geometry in one place, and so a grab near two overlapping targets has
 * a defined winner (nearest handle, then the body). Drags apply the frame-to-frame DELTA rather than
 * jumping to the touch position, so grabbing a handle slightly off-center doesn't snap the ellipse.
 */
@Composable
private fun EllipseOverlay(
    ellipse: CanopyEllipse,
    onEllipseChange: (CanopyEllipse) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { HANDLE_RADIUS.toPx() }
    val grabRadiusPx = with(density) { HANDLE_GRAB_RADIUS.toPx() }
    val minRadiusPx = with(density) { MIN_ELLIPSE_RADIUS.toPx() }

    // The gesture detector is installed once (pointerInput(Unit)); these keep it reading the latest
    // ellipse and callback instead of the ones captured on first composition.
    val currentEllipse by rememberUpdatedState(ellipse)
    val currentOnChange by rememberUpdatedState(onEllipseChange)
    var grab by remember { mutableStateOf(Grab.NONE) }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            val bounds = Size(size.width.toFloat(), size.height.toFloat())
            detectDragGestures(
                onDragStart = { start ->
                    grab = grabTarget(currentEllipse, start, grabRadiusPx)
                },
                onDragEnd = { grab = Grab.NONE },
                onDragCancel = { grab = Grab.NONE }
            ) { change, delta ->
                if (grab != Grab.NONE) {
                    change.consume()
                    currentOnChange(applyDrag(currentEllipse, grab, delta, bounds, minRadiusPx))
                }
            }
        }
    ) {
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
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHandle(
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

    if (ellipse.radiusX <= 0f || ellipse.radiusY <= 0f) return Grab.NONE
    val normalizedX = (point.x - ellipse.centerX) / ellipse.radiusX
    val normalizedY = (point.y - ellipse.centerY) / ellipse.radiusY
    // Touches outside the ellipse do nothing on purpose: an accidental brush of the photo shouldn't
    // move a framing the operator has already settled.
    return if (normalizedX * normalizedX + normalizedY * normalizedY <= 1f) Grab.BODY else Grab.NONE
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
 * Live readout, the plausibility warning, and the two exits.
 *
 * The warning is advisory. Unlike the segmentation confidence gate this screen replaces, there is no
 * forced retry: the operator is looking at the ellipse on top of the actual plant, so they can judge
 * an unusual-looking size better than a constant can. What the warning changes is the confirm button's
 * wording, so proceeding past it is a deliberate act rather than an unnoticed one.
 */
@Composable
private fun FramingPanel(
    measurement: CanopyMeasurement?,
    distanceMeters: Float,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1B1B1B))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = "Frame the canopy",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Drag the green handle for width and the amber handle for height. " +
                "Drag inside the ellipse to move it.",
            color = Color(0xFFBDBDBD),
            fontSize = 12.sp
        )

        Spacer(Modifier.height(12.dp))

        if (measurement == null) {
            Text(text = "Measuring…", color = Color(0xFFBDBDBD), fontSize = 13.sp)
        } else {
            Text(
                text = "Canopy ${formatCanopyDiameter(measurement.diameterMeters)} across",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "width ${formatCanopyDiameter(measurement.horizontalDiameterMeters)}" +
                    " × height ${formatCanopyDiameter(measurement.verticalDiameterMeters)}" +
                    " · averaged · at %.1f m".format(distanceMeters),
                color = Color(0xFFBDBDBD),
                fontSize = 12.sp
            )
            if (!measurement.isPlausible) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "⚠ This size looks unusual — check your framing.",
                    color = Color(0xFFFFB74D),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

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
                enabled = measurement != null,
                modifier = Modifier.weight(1.6f)
            ) {
                Text(
                    text = if (measurement != null && !measurement.isPlausible) {
                        "Use this size anyway"
                    } else {
                        "Confirm size"
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Decodes [path] downsampled to at most [DISPLAY_IMAGE_MAX_SIDE] px on its long side and rotates it
 * upright.
 *
 * [rotationDegrees] is applied clockwise: the ARCore path saves the raw sensor image (typically 90°
 * off) while CameraX already writes its JPEG upright. Getting this wrong would show the operator a
 * sideways photo and swap which axis each handle measures.
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

        val normalized = ((rotationDegrees % 360) + 360) % 360
        if (normalized == 0) return decoded

        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    } catch (e: Exception) {
        Log.w(TAG, "Decoding $path failed", e)
        null
    }
}

package com.gops.spatialmapper.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import com.gops.spatialmapper.area.formatArea
import com.gops.spatialmapper.area.polygonAreaSquareMeters
import com.gops.spatialmapper.data.SessionEntity
import com.gops.spatialmapper.data.SessionRepository
import com.gops.spatialmapper.measure.CanopyMeasurement
import com.gops.spatialmapper.telemetry.CaptureTelemetry
import kotlinx.coroutines.launch

private const val MAP_REVIEW_TAG = "SpatialMapperMapReview"

/** Bearing at which the radius-drag handle sits relative to the canopy center (due east). */
private const val RADIUS_HANDLE_BEARING = 90.0

/** Draws a small filled circle "handle" bitmap for a draggable map control. */
private fun vertexHandleIcon(color: Color): BitmapDescriptor {
    val sizePx = 44
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = sizePx / 2f
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    canvas.drawCircle(radius, radius, radius - 5f, fillPaint)
    canvas.drawCircle(radius, radius, radius - 5f, borderPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/**
 * Review a captured plant on satellite imagery, then SAVE it as a [SessionEntity].
 *
 * The canopy outline arrives pre-generated at the size the operator framed on the captured photo:
 * [measurement] carries the crown diameter the framing ellipse + pinhole conversion produced, and its
 * half — the radius — seeds [generateCanopyPolygon]. The operator's job here is confirm-and-nudge,
 * not construction:
 *  - drag the center pin to sit the crown on the real plant,
 *  - drag the radius handle if the size looks wrong against the satellite imagery.
 *
 * Nudging the radius keeps the same shape seed, so the crown scales rather than reshuffling, and the
 * framed measurement remains the starting point rather than something the operator has to build from
 * scratch. The raw measurement is persisted separately from the final outline, so the audit log shows
 * both what was measured on the photo and what the human confirmed against the map.
 *
 * @param capture     the full at-shutter telemetry snapshot; its projected lat/lon MUST be non-null.
 * @param photoPath   absolute path to the JPEG this capture saved (stored on the session row).
 * @param measurement the canopy measurement confirmed on the framing screen.
 * @param onBack      discard and return to the camera.
 * @param onSaved     invoked after a successful DB insert.
 */
@Composable
fun MapReviewScreen(
    capture: CaptureTelemetry,
    photoPath: String,
    measurement: CanopyMeasurement,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val target = LatLng(
        requireNotNull(capture.projectedLatitude) { "MapReviewScreen requires a projected target" },
        requireNotNull(capture.projectedLongitude) { "MapReviewScreen requires a projected target" }
    )
    val origin = if (capture.latitude != null && capture.longitude != null) {
        LatLng(capture.latitude, capture.longitude)
    } else {
        null
    }

    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    val scope = rememberCoroutineScope()

    val initialRadius = measurement.radiusMeters
        .coerceIn(MIN_CANOPY_RADIUS_METERS, MAX_CANOPY_RADIUS_METERS)
    // One seed per capture, so the crown's silhouette is stable across recompositions and resizes.
    val shapeSeed = remember(capture.timestampMillis) { canopySeedFor(capture.timestampMillis) }

    // Draggable canopy center and radius handle. remember (not rememberUpdatedMarkerState) so a drag
    // isn't reset by recomposition — MarkerState.position is snapshot-backed, so reading it here
    // makes the polygon below track the drag live, frame by frame.
    val centerState = remember { MarkerState(position = target) }
    val radiusHandleState = remember {
        MarkerState(position = SphericalUtil.computeOffset(target, initialRadius, RADIUS_HANDLE_BEARING))
    }
    val originState = remember(origin) { origin?.let { MarkerState(position = it) } }

    // When the CENTER is dragged, carry the radius handle along by the same offset so the crown
    // translates instead of resizing. Radius is then simply "distance from center to handle".
    var lastCenter by remember { mutableStateOf(target) }
    LaunchedEffect(centerState.position) {
        val current = centerState.position
        if (current != lastCenter) {
            val heldRadius = SphericalUtil.computeDistanceBetween(lastCenter, radiusHandleState.position)
            val heldBearing = SphericalUtil.computeHeading(lastCenter, radiusHandleState.position)
            radiusHandleState.position =
                SphericalUtil.computeOffset(current, heldRadius, heldBearing)
            lastCenter = current
        }
    }

    val radiusMeters = SphericalUtil
        .computeDistanceBetween(centerState.position, radiusHandleState.position)
        .coerceIn(MIN_CANOPY_RADIUS_METERS, MAX_CANOPY_RADIUS_METERS)
    val canopy = generateCanopyPolygon(
        center = centerState.position,
        baseRadiusMeters = radiusMeters,
        seed = shapeSeed
    )

    var label by remember { mutableStateOf("") }

    // BitmapDescriptorFactory requires the Maps SDK to be initialized, which is guaranteed once the
    // map has loaded — so custom icons are created only after onMapLoaded.
    var mapLoaded by remember { mutableStateOf(false) }
    val handleIcon: BitmapDescriptor? = remember(mapLoaded) {
        if (mapLoaded) vertexHandleIcon(CANOPY_STROKE_COLOR) else null
    }
    val originIcon: BitmapDescriptor? = remember(mapLoaded) {
        if (mapLoaded) BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE) else null
    }

    val cameraPositionState = rememberCameraPositionState {
        // Zoom ~19–20 = very close in; good for confirming/nudging a small canopy.
        position = CameraPosition.fromLatLngZoom(target, 19.5f)
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.SATELLITE),
            onMapLoaded = { mapLoaded = true }
        ) {
            Polygon(
                points = canopy,
                fillColor = CANOPY_FILL_COLOR,
                strokeColor = CANOPY_STROKE_COLOR,
                strokeWidth = 5f,
                zIndex = 1f
            )

            // Origin (photo location): fixed reference, not draggable, azure to distinguish it.
            originState?.let { state ->
                Marker(
                    state = state,
                    icon = originIcon,
                    draggable = false,
                    title = "Origin (photo taken here)",
                    zIndex = 2f
                )
            }

            // Canopy center: draggable so the operator can sit the crown on the real plant.
            Marker(
                state = centerState,
                draggable = true,
                title = "Plant (drag to nudge)",
                zIndex = 3f
            )

            // Radius handle: drag outward/inward to refine the auto-measured crown size.
            Marker(
                state = radiusHandleState,
                icon = handleIcon,
                anchor = Offset(0.5f, 0.5f),
                draggable = true,
                title = "Canopy size (drag to resize)",
                zIndex = 4f
            )
        }

        // Top bar: back to camera.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("‹ Back to camera")
            }
        }

        // Bottom editing panel: measured size readout, label, save.
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Canopy ${formatCanopyDiameter(radiusMeters * 2.0)} across · " +
                        formatArea(polygonAreaSquareMeters(canopy)),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val measuredText = formatCanopyDiameter(measurement.diameterMeters)
                val widthText = formatCanopyDiameter(measurement.horizontalDiameterMeters)
                val heightText = formatCanopyDiameter(measurement.verticalDiameterMeters)
                Text(
                    text = "Framed on the photo at $measuredText ($widthText × $heightText). " +
                        "Drag the green handle to refine.",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (e.g. \"Neem by the gate\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val entity = capture.toSessionEntity(
                            label = label.trim(),
                            canopy = canopy,
                            measurement = measurement,
                            photoPath = photoPath
                        )
                        logAssetState(entity)
                        // Persist off the main thread (Room runs suspend inserts on its own executor),
                        // then confirm + hand control back to the caller for navigation.
                        scope.launch {
                            val id = repository.insert(entity)
                            Log.d(MAP_REVIEW_TAG, "Saved session id=$id")
                            Toast.makeText(context, "Plant saved", Toast.LENGTH_SHORT).show()
                            onSaved()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }
    }
}

/**
 * Combines the raw at-shutter [CaptureTelemetry] (the audit log) with the framed [measurement] and
 * the human-confirmed review state ([label], [canopy]) into a persistable [SessionEntity].
 *
 * projectedLatitude/Longitude and [CanopyMeasurement.diameterMeters] keep the values they had when
 * the framing screen was confirmed — any later nudge of the on-map radius handle lives only in
 * [SessionEntity.polygonVertices]. Storing both means the audit log shows what was measured AND what
 * was confirmed, even when they differ.
 */
private fun CaptureTelemetry.toSessionEntity(
    label: String,
    canopy: List<LatLng>,
    measurement: CanopyMeasurement,
    photoPath: String
): SessionEntity = SessionEntity(
    timestampMillis = timestampMillis,
    photoPath = photoPath,
    latitude = latitude,
    longitude = longitude,
    gpsAccuracyMeters = gpsAccuracyMeters,
    headingDegrees = headingDegrees,
    headingIsTrueNorth = headingIsTrueNorth,
    pitchDegrees = pitchDegrees,
    compassReliability = compassReliability.name,
    distanceMeters = distanceMeters,
    distanceMethod = distanceMethod?.name,
    projectedLatitude = projectedLatitude,
    projectedLongitude = projectedLongitude,
    focalLengthPixels = intrinsics?.focalLengthPixels,
    imageWidthPixels = intrinsics?.imageWidthPixels,
    imageHeightPixels = intrinsics?.imageHeightPixels,
    canopyDiameterMeters = measurement.diameterMeters,
    canopyDiameterHorizontalMeters = measurement.horizontalDiameterMeters,
    canopyDiameterVerticalMeters = measurement.verticalDiameterMeters,
    label = label,
    polygonVertices = canopy,
    // Derived from the CONFIRMED outline, not from the framed diameter: the operator may have
    // dragged the radius handle after framing, and the polygon is the thing they signed off on.
    areaSquareMeters = polygonAreaSquareMeters(canopy)
)

/** Debug breadcrumb (filter Logcat by tag [MAP_REVIEW_TAG]) mirroring what just got persisted. */
private fun logAssetState(entity: SessionEntity) {
    val outlineText = entity.polygonVertices.joinToString(prefix = "[", postfix = "]") {
        "(%.6f, %.6f)".format(it.latitude, it.longitude)
    }
    Log.d(
        MAP_REVIEW_TAG,
        "Persisting plant: label='${entity.label.ifBlank { "(none)" }}' " +
            "canopy=${entity.canopyDiameterMeters?.let { "%.2f m".format(it) } ?: "—"} " +
            "area=${entity.areaSquareMeters?.let { "%.2f m2".format(it) } ?: "—"} " +
            "photo=${entity.photoPath} outline=$outlineText"
    )
}

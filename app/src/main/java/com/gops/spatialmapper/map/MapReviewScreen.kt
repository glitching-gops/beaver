package com.gops.spatialmapper.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import com.gops.spatialmapper.capture.SavedDepthImage
import com.gops.spatialmapper.data.ClassSource
import com.gops.spatialmapper.data.ClusterMemberEntity
import com.gops.spatialmapper.data.SessionEntity
import com.gops.spatialmapper.data.SessionRepository
import com.gops.spatialmapper.data.TreeClass
import com.gops.spatialmapper.measure.CENTRE_TREE_NUMBER
import com.gops.spatialmapper.measure.CanopyMeasurement
import com.gops.spatialmapper.measure.ClusterMemberMeasurement
import com.gops.spatialmapper.projection.projectTargetLocation
import com.gops.spatialmapper.telemetry.CaptureTelemetry
import com.gops.spatialmapper.ui.TreeClassSelector
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
 * One tree on the review map: its draggable pin, its radius handle, and its class.
 *
 * MarkerState rather than plain LatLng state because the Maps SDK writes drags straight into it;
 * created once per capture (see the remember below) so recomposition can never undo a drag.
 */
@Stable
private class ReviewTree(
    val treeNumber: Int,
    val seed: Long,
    /** Where the measurement put it, before any nudge — persisted as the raw projected target. */
    val projected: LatLng,
    initialRadiusMeters: Double
) {
    val pin = MarkerState(position = projected)
    val radiusHandle = MarkerState(
        position = SphericalUtil.computeOffset(projected, initialRadiusMeters, RADIUS_HANDLE_BEARING)
    )

    /** Where [pin] was last seen, so a drag can be turned into a translation of the handle. */
    var lastPin: LatLng = projected

    var treeClass by mutableStateOf<TreeClass?>(null)

    val radiusMeters: Double
        get() = SphericalUtil.computeDistanceBetween(pin.position, radiusHandle.position)
            .coerceIn(MIN_CANOPY_RADIUS_METERS, MAX_CANOPY_RADIUS_METERS)

    val polygon: List<LatLng>
        get() = generateCanopyPolygon(center = pin.position, baseRadiusMeters = radiusMeters, seed = seed)
}

/**
 * Review a captured plant — or a captured CLUSTER of plants — on satellite imagery, then SAVE it.
 *
 * The canopy outlines arrive pre-generated at the sizes the operator framed on the captured photo,
 * each from its own [generateCanopyPolygon] seed. The operator's job here is confirm-and-nudge, not
 * construction:
 *  - drag tree 1's pin to sit the crowns on the real plants. In a cluster this moves the WHOLE
 *    group, every member keeping its offset from tree 1 — the trees were measured relative to one
 *    another from one spot, and that relationship is better than anyone's thumb;
 *  - turn on "Fine adjust" to move or resize trees individually, when one of them is visibly off;
 *  - drag a radius handle if a size looks wrong against the imagery.
 *
 * Nudging a radius keeps that tree's seed, so its crown scales rather than reshuffling. The raw
 * measurements are persisted separately from the final outlines, so the audit log shows both what
 * was measured on the photo and what the human confirmed against the map.
 *
 * Save writes tree 1 to the session row exactly as a single-tree capture always has, and the others
 * to cluster_members, in one transaction.
 *
 * @param capture     the full at-shutter telemetry snapshot; its projected lat/lon MUST be non-null.
 * @param photoPath   absolute path to the JPEG this capture saved (stored on the session row).
 * @param measurement tree 1's canopy measurement from the framing screen.
 * @param members     trees 2..N, measured on the framing screen; empty for a single tree.
 * @param depth       the depth image saved with the photo, recorded on the session row.
 * @param onBack      return to the framing screen.
 * @param onSaved     invoked after a successful DB insert.
 */
@Composable
fun MapReviewScreen(
    capture: CaptureTelemetry,
    photoPath: String,
    measurement: CanopyMeasurement,
    members: List<ClusterMemberMeasurement>,
    depth: SavedDepthImage?,
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

    // Tree 1 keeps the exact seed and starting radius a single-tree capture always had. Members are
    // placed from the origin along their own bearing and line-of-sight range — see ClusterGeometry.
    val trees = remember(capture.timestampMillis, members) {
        val centre = ReviewTree(
            treeNumber = CENTRE_TREE_NUMBER,
            seed = clusterTreeSeedFor(capture.timestampMillis, CENTRE_TREE_NUMBER),
            projected = target,
            initialRadiusMeters = measurement.radiusMeters
                .coerceIn(MIN_CANOPY_RADIUS_METERS, MAX_CANOPY_RADIUS_METERS)
        )
        listOf(centre) + members.map { member ->
            ReviewTree(
                treeNumber = member.treeNumber,
                seed = clusterTreeSeedFor(capture.timestampMillis, member.treeNumber),
                projected = origin?.let {
                    projectTargetLocation(it, member.bearingDegrees, member.rangeMeters)
                } ?: target,
                initialRadiusMeters = member.canopy.radiusMeters
                    .coerceIn(MIN_CANOPY_RADIUS_METERS, MAX_CANOPY_RADIUS_METERS)
            )
        }
    }
    val centre = trees.first()
    val isCluster = trees.size > 1

    var fineAdjust by remember { mutableStateOf(false) }
    // Which tree the class chips edit in fine-adjust mode (an index into [trees]).
    var classTarget by remember { mutableIntStateOf(0) }

    // Each tree: when its PIN moves, carry its radius handle along by the same offset so the crown
    // translates instead of resizing. Radius is then simply "distance from pin to handle".
    trees.forEach { tree ->
        key(tree.treeNumber) {
            LaunchedEffect(tree.pin.position) {
                val current = tree.pin.position
                if (current != tree.lastPin) {
                    val heldRadius = SphericalUtil.computeDistanceBetween(tree.lastPin, tree.radiusHandle.position)
                    val heldBearing = SphericalUtil.computeHeading(tree.lastPin, tree.radiusHandle.position)
                    tree.radiusHandle.position = SphericalUtil.computeOffset(current, heldRadius, heldBearing)
                    tree.lastPin = current
                }
            }
        }
    }

    // GROUP MOVE: unless fine-adjusting, moving tree 1 moves every member with it, each keeping its
    // distance and bearing FROM TREE 1. (Their own effects above then carry their handles.)
    var groupAnchor by remember { mutableStateOf(target) }
    LaunchedEffect(centre.pin.position) {
        val current = centre.pin.position
        if (current != groupAnchor) {
            if (!fineAdjust) {
                trees.drop(1).forEach { member ->
                    val offset = SphericalUtil.computeDistanceBetween(groupAnchor, member.pin.position)
                    val heading = SphericalUtil.computeHeading(groupAnchor, member.pin.position)
                    member.pin.position = SphericalUtil.computeOffset(current, offset, heading)
                }
            }
            groupAnchor = current
        }
    }

    val polygons = trees.map { it.polygon }
    val outline = if (needsClusterOutline(trees.size)) paddedClusterOutline(polygons) else emptyList()

    var label by remember { mutableStateOf("") }

    // BitmapDescriptorFactory requires the Maps SDK to be initialized, which is guaranteed once the
    // map has loaded — so custom icons are created only after onMapLoaded.
    var mapLoaded by remember { mutableStateOf(false) }
    val handleIcon: BitmapDescriptor? = remember(mapLoaded) {
        if (mapLoaded) vertexHandleIcon(CANOPY_STROKE_COLOR) else null
    }
    val memberPinIcon: BitmapDescriptor? = remember(mapLoaded) {
        if (mapLoaded) BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET) else null
    }
    val originIcon: BitmapDescriptor? = remember(mapLoaded) {
        if (mapLoaded) BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE) else null
    }

    val cameraPositionState = rememberCameraPositionState {
        // Zoom ~19–20 = very close in; good for confirming/nudging a small canopy.
        position = CameraPosition.fromLatLngZoom(target, if (isCluster) 19f else 19.5f)
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.SATELLITE),
            onMapLoaded = { mapLoaded = true }
        ) {
            if (outline.size >= 3) ClusterOutlinePolygons(outline)

            trees.forEachIndexed { index, tree ->
                val displayClass = tree.treeClass ?: TreeClass.UNCLASSIFIED
                Polygon(
                    points = polygons[index],
                    fillColor = canopyFillColor(displayClass),
                    strokeColor = canopyStrokeColor(displayClass),
                    strokePattern = canopyStrokePattern(displayClass),
                    strokeWidth = 5f,
                    geodesic = false,
                    zIndex = 1f
                )
            }

            // Origin (photo location): fixed reference, not draggable, azure to distinguish it.
            originState(origin)?.let { state ->
                Marker(
                    state = state,
                    icon = originIcon,
                    draggable = false,
                    title = "Origin (photo taken here)",
                    zIndex = 2f
                )
            }

            // Tree 1: draggable so the operator can sit the crown(s) on the real plants.
            Marker(
                state = centre.pin,
                draggable = true,
                title = when {
                    !isCluster -> "Plant (drag to nudge)"
                    fineAdjust -> "Tree 1 (drag to move this tree)"
                    else -> "Tree 1 (drag to move the whole cluster)"
                },
                zIndex = 3f,
                onClick = {
                    classTarget = 0
                    false
                }
            )
            Marker(
                state = centre.radiusHandle,
                icon = handleIcon,
                anchor = Offset(0.5f, 0.5f),
                draggable = true,
                title = "Canopy size (drag to resize)",
                zIndex = 4f
            )

            // Members' own controls exist only while fine-adjusting: by default the cluster moves
            // as one, and ten extra pins would only invite accidental drags.
            if (fineAdjust) {
                trees.drop(1).forEachIndexed { i, tree ->
                    key(tree.treeNumber) {
                        Marker(
                            state = tree.pin,
                            icon = memberPinIcon,
                            draggable = true,
                            title = "Tree ${tree.treeNumber} (drag to move)",
                            zIndex = 3f,
                            onClick = {
                                classTarget = i + 1
                                false
                            }
                        )
                        Marker(
                            state = tree.radiusHandle,
                            icon = handleIcon,
                            anchor = Offset(0.5f, 0.5f),
                            draggable = true,
                            title = "Tree ${tree.treeNumber} size (drag to resize)",
                            zIndex = 4f
                        )
                    }
                }
            }
        }

        // Top bar: back to framing.
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

        // Bottom editing panel: measured size readout, label, class, save.
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isCluster) {
                    val totalArea = polygons.mapNotNull { polygonAreaSquareMeters(it) }.sum()
                    Text(
                        text = "Cluster of ${trees.size} trees · ${formatArea(totalArea)} total",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (fineAdjust) {
                            "Drag any pin or green handle to adjust that tree alone."
                        } else {
                            "Drag tree 1's pin to move the whole cluster."
                        },
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Fine adjust",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = fineAdjust,
                            onCheckedChange = {
                                fineAdjust = it
                                classTarget = 0
                            }
                        )
                    }
                } else {
                    Text(
                        text = "Canopy ${formatCanopyDiameter(centre.radiusMeters * 2.0)} across · " +
                            formatArea(polygonAreaSquareMeters(polygons.first())),
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
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (e.g. \"Neem by the gate\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(6.dp))
                ClassPicker(
                    trees = trees,
                    perTree = isCluster && fineAdjust,
                    classTarget = classTarget.coerceIn(0, trees.lastIndex),
                    onTargetChange = { classTarget = it }
                )

                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        val entity = capture.toSessionEntity(
                            label = label.trim(),
                            canopy = polygons.first(),
                            measurement = measurement,
                            photoPath = photoPath,
                            treeClass = centre.treeClass,
                            classSource = if (centre.treeClass != null) ClassSource.MANUAL else ClassSource.UNSET,
                            depth = depth
                        )
                        val memberEntities = members.zip(trees.drop(1)).mapIndexed { i, (member, tree) ->
                            member.toEntity(tree, polygons[i + 1])
                        }
                        logAssetState(entity, memberEntities)
                        // Persist off the main thread (Room runs suspend writes on its own executor),
                        // then confirm + hand control back to the caller for navigation.
                        scope.launch {
                            val id = repository.insertCapture(entity, memberEntities)
                            Log.d(MAP_REVIEW_TAG, "Saved session id=$id with ${memberEntities.size} member(s)")
                            Toast.makeText(
                                context,
                                if (isCluster) "Cluster of ${trees.size} trees saved" else "Plant saved",
                                Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isCluster) "Save ${trees.size} trees" else "Save")
                }
            }
        }
    }
}

@Composable
private fun originState(origin: LatLng?): MarkerState? =
    remember(origin) { origin?.let { MarkerState(position = it) } }

/**
 * The class chips. By default one choice applies to EVERY tree in the capture — a cluster is
 * usually one kind of tree, and one tap should say so. In fine-adjust mode a row of tree numbers
 * picks which single tree the chips edit.
 *
 * No chip is pre-selected, ever: every tree starts unclassified, and the chips only show a
 * selection the operator made. With mixed classes the shared row shows none selected.
 */
@Composable
private fun ClassPicker(
    trees: List<ReviewTree>,
    perTree: Boolean,
    classTarget: Int,
    onTargetChange: (Int) -> Unit
) {
    Text(
        text = when {
            trees.size == 1 -> "Tree class (optional):"
            perTree -> "Tree class for tree ${trees[classTarget].treeNumber} (optional):"
            else -> "Tree class for all ${trees.size} trees (optional):"
        },
        fontSize = 12.sp,
        color = Color.DarkGray,
        modifier = Modifier.padding(start = 16.dp)
    )
    if (perTree) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            trees.forEachIndexed { index, tree ->
                FilterChip(
                    selected = index == classTarget,
                    onClick = { onTargetChange(index) },
                    label = { Text("${tree.treeNumber}") }
                )
            }
        }
        TreeClassSelector(
            selectedClass = trees[classTarget].treeClass,
            onClassSelected = { trees[classTarget].treeClass = it }
        )
    } else {
        val shared = trees.map { it.treeClass }.distinct().singleOrNull()
        TreeClassSelector(
            selectedClass = shared,
            onClassSelected = { selected -> trees.forEach { it.treeClass = selected } }
        )
    }
}

/**
 * Combines the raw at-shutter [CaptureTelemetry] (the audit log) with the framed [measurement] and
 * the human-confirmed review state ([label], [canopy], [treeClass]) into a persistable [SessionEntity]
 * — tree 1 of the capture, exactly as a single-tree capture has always been saved.
 *
 * projectedLatitude/Longitude and [CanopyMeasurement.diameterMeters] keep the values they had when
 * the framing screen was confirmed — any later nudge of the on-map radius handle lives only in
 * [SessionEntity.polygonVertices]. Storing both means the audit log shows what was measured AND what
 * was confirmed, even when they differ.
 *
 * If a [treeClass] is provided, [classConfirmedAtMillis] is set to now so the operator's confirmation
 * is timestamped. If no class is selected ([treeClass] is null), the entity defaults to UNCLASSIFIED
 * with source UNSET.
 */
private fun CaptureTelemetry.toSessionEntity(
    label: String,
    canopy: List<LatLng>,
    measurement: CanopyMeasurement,
    photoPath: String,
    treeClass: TreeClass? = null,
    classSource: ClassSource = ClassSource.UNSET,
    depth: SavedDepthImage? = null
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
    areaSquareMeters = polygonAreaSquareMeters(canopy),
    // Tree classification
    treeClass = (treeClass ?: TreeClass.UNCLASSIFIED).name,
    classSource = classSource.name,
    classConfirmedAtMillis = if (treeClass != null) System.currentTimeMillis() else null,
    // The depth image saved with the photo (AR path only).
    depthImagePath = depth?.path,
    depthImageWidth = depth?.width,
    depthImageHeight = depth?.height,
    depthRotationDegrees = depth?.alignment?.rotationDegrees,
    depthTransform = depth?.alignment?.cpuToTextureNormalized?.serialize()
)

/**
 * A member's row: the framing-screen measurement (audit log) plus what the operator confirmed on the
 * map. sessionId is filled in by the repository from the new session row.
 */
private fun ClusterMemberMeasurement.toEntity(tree: ReviewTree, canopy: List<LatLng>): ClusterMemberEntity =
    ClusterMemberEntity(
        sessionId = 0,
        memberIndex = treeNumber,
        ellipseCenterXPixels = photoEllipse.centerX,
        ellipseCenterYPixels = photoEllipse.centerY,
        ellipseRadiusXPixels = photoEllipse.radiusX,
        ellipseRadiusYPixels = photoEllipse.radiusY,
        distanceMeters = distance.meters,
        distanceMethod = distance.method.name,
        bearingDegrees = bearingDegrees,
        canopyDiameterHorizontalMeters = this.canopy.horizontalDiameterMeters,
        canopyDiameterVerticalMeters = this.canopy.verticalDiameterMeters,
        canopyDiameterMeters = this.canopy.diameterMeters,
        projectedLatitude = tree.projected.latitude,
        projectedLongitude = tree.projected.longitude,
        polygonVertices = canopy,
        areaSquareMeters = polygonAreaSquareMeters(canopy),
        treeClass = (tree.treeClass ?: TreeClass.UNCLASSIFIED).name,
        classSource = (if (tree.treeClass != null) ClassSource.MANUAL else ClassSource.UNSET).name,
        classConfirmedAtMillis = if (tree.treeClass != null) System.currentTimeMillis() else null
    )

/** Debug breadcrumb (filter Logcat by tag [MAP_REVIEW_TAG]) mirroring what just got persisted. */
private fun logAssetState(entity: SessionEntity, members: List<ClusterMemberEntity>) {
    val outlineText = entity.polygonVertices.joinToString(prefix = "[", postfix = "]") {
        "(%.6f, %.6f)".format(it.latitude, it.longitude)
    }
    Log.d(
        MAP_REVIEW_TAG,
        "Persisting plant: label='${entity.label.ifBlank { "(none)" }}' " +
            "class=${entity.treeClass} source=${entity.classSource} " +
            "canopy=${entity.canopyDiameterMeters?.let { "%.2f m".format(it) } ?: "—"} " +
            "area=${entity.areaSquareMeters?.let { "%.2f m2".format(it) } ?: "—"} " +
            "photo=${entity.photoPath} depth=${entity.depthImagePath ?: "—"} outline=$outlineText"
    )
    members.forEach { member ->
        Log.d(
            MAP_REVIEW_TAG,
            "  member ${member.memberIndex}: class=${member.treeClass} " +
                "canopy=%.2f m distance=%.2f m (%s) bearing=%.1f° area=%s".format(
                    member.canopyDiameterMeters,
                    member.distanceMeters,
                    member.distanceMethod,
                    member.bearingDegrees,
                    member.areaSquareMeters?.let { "%.2f m2".format(it) } ?: "—"
                )
        )
    }
}

package com.gops.spatialmapper.history

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState
import com.gops.spatialmapper.area.formatArea
import com.gops.spatialmapper.data.ClassSource
import com.gops.spatialmapper.data.ClusterMemberEntity
import com.gops.spatialmapper.data.SessionEntity
import com.gops.spatialmapper.data.SessionRepository
import com.gops.spatialmapper.data.TreeClass
import com.gops.spatialmapper.data.parseTreeClass
import com.gops.spatialmapper.export.ExportAllAction
import com.gops.spatialmapper.identify.decodeCandidates
import com.gops.spatialmapper.identify.sessionReferencePoint
import com.gops.spatialmapper.map.CANOPY_FILL_COLOR
import com.gops.spatialmapper.map.CANOPY_STROKE_COLOR
import com.gops.spatialmapper.map.ClusterOutlinePolygons
import com.gops.spatialmapper.map.canopyFillColor
import com.gops.spatialmapper.map.canopyStrokeColor
import com.gops.spatialmapper.map.canopyStrokePattern
import com.gops.spatialmapper.map.needsClusterOutline
import com.gops.spatialmapper.map.paddedClusterOutline
import com.gops.spatialmapper.telemetry.DistanceMethod
import com.gops.spatialmapper.map.formatCanopyDiameter
import com.gops.spatialmapper.ui.TreeClassSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val timestampFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

/**
 * History: a chronological (newest-first) browse-and-delete UI over saved plant records.
 *
 * The list/detail selection is HOISTED rather than held locally: the global map needs to be able to
 * open a session's detail directly when its canopy is tapped, which is impossible if History owns the
 * selection privately. This is now a controlled component — the caller decides what's showing.
 *
 * @param selectedSessionId null shows the list, non-null shows that session's detail.
 * @param onSelectSession   open a session (non-null) or return to the list (null).
 * @param onViewOnMap       show this session on the global Map tab, centred and highlighted.
 * @param onIdentify        open the modal species-identification flow for this session. The origin is
 *                          passed along rather than re-read there, because it is what the proximity
 *                          check compares against and detail already has the row in hand.
 * @param onIdentifyMember  the same flow for ONE member of a cluster (its row id and tree number).
 */
/**
 * The two kinds of record the History tab browses.
 *
 * A sub-tab here rather than a fourth bottom-bar destination, deliberately. Both are "things I have
 * already recorded, listed newest first, tap to look at one, swipe-free delete" — the same activity
 * over two record types, not two activities. Promoting surveys to the bottom bar would make the bar
 * four-wide and imply the two are as far apart as Capture and Map, which they are not.
 */
private enum class HistorySection(val label: String) {
    TREES("Trees"),
    SURVEYS("Surveys")
}

@Composable
fun HistoryScreen(
    selectedSessionId: Long?,
    onSelectSession: (Long?) -> Unit,
    onViewOnMap: (Long) -> Unit,
    onViewSurveyOnMap: (Long) -> Unit,
    onIdentify: (sessionId: Long, label: String, origin: LatLng?) -> Unit,
    onIdentifyMember: (sessionId: Long, label: String, origin: LatLng?, memberId: Long, memberIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // rememberSaveable so switching tabs and coming back does not silently drop the operator from
    // Surveys back to Trees. Saved by name, matching how AppTab is persisted in MainActivity.
    var section by rememberSaveable(stateSaver = HistorySectionSaver) {
        mutableStateOf(HistorySection.TREES)
    }

    if (selectedSessionId == null) {
        SessionList(
            section = section,
            onSelectSection = { section = it },
            onOpen = { onSelectSession(it) },
            onOpenSurvey = onViewSurveyOnMap,
            modifier = modifier
        )
    } else {
        SessionDetail(
            sessionId = selectedSessionId,
            onBack = { onSelectSession(null) },
            onDeleted = { onSelectSession(null) },
            onViewOnMap = { onViewOnMap(selectedSessionId) },
            onIdentify = onIdentify,
            onIdentifyMember = onIdentifyMember,
            modifier = modifier
        )
    }
}

private val HistorySectionSaver: Saver<HistorySection, String> = Saver(
    save = { it.name },
    restore = { name -> runCatching { HistorySection.valueOf(name) }.getOrDefault(HistorySection.TREES) }
)

@Composable
private fun SessionList(
    section: HistorySection,
    onSelectSection: (HistorySection) -> Unit,
    onOpen: (Long) -> Unit,
    onOpenSurvey: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    // Flow → Compose state: emits a fresh list on every insert/delete, so this screen updates live.
    val sessions by repository.observeSessions().collectAsState(initial = emptyList())
    // Member counts per session, so a cluster's row can say how many trees it holds.
    val allMembers by repository.observeAllMembers().collectAsState(initial = emptyList())
    val memberCounts = remember(allMembers) { allMembers.groupingBy { it.sessionId }.eachCount() }

    Column(modifier = modifier.fillMaxSize()) {
        // No back arrow: this is a tab root now, and the bottom bar is how you leave it.
        //
        // Export lives here rather than on a session's detail because it is a bulk action over
        // everything the list is showing — the same set, from the same screen. It is handed the
        // already-collected list so the button and the rows beneath it can never disagree about
        // what "all" means, and so it stays out of the database entirely.
        ScreenTopBar(
            title = "History",
            onBack = null,
            actions = { ExportAllAction(sessions = sessions) }
        )

        SectionSelector(selected = section, onSelect = onSelectSection)

        when (section) {
            HistorySection.SURVEYS -> SurveyList(onOpenOnMap = onOpenSurvey)

            HistorySection.TREES -> if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No saved sessions yet.\nCapture, review on the map, and Save.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            treeCount = 1 + (memberCounts[session.id] ?: 0),
                            onClick = { onOpen(session.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Two-segment switch between Trees and Surveys.
 *
 * Hand-rolled rather than Material3's SegmentedButton, which is still an experimental API: adding an
 * opt-in annotation to this project for a two-item toggle would buy nothing the six lines below do
 * not, and the app already hand-rolls its accordion and its cards in the same vocabulary.
 */
@Composable
private fun SectionSelector(selected: HistorySection, onSelect: (HistorySection) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HistorySection.entries.forEach { entry ->
            val active = entry == selected
            Surface(
                onClick = { onSelect(entry) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = entry.label,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, treeCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LocalPhoto(
                path = session.photoPath,
                reqSizePx = 256,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.label.ifBlank { "(no label)" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(canopyStrokeColor(parseTreeClass(session.treeClass)))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (treeCount > 1) {
                            "Cluster of $treeCount trees"
                        } else {
                            "Canopy ${canopyText(session.canopyDiameterMeters)}"
                        },
                        fontSize = 13.sp,
                        fontWeight = if (treeCount > 1) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = timestampFormat.format(Date(session.timestampMillis)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Area: " + formatArea(session.areaSquareMeters),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SessionDetail(
    sessionId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onViewOnMap: () -> Unit,
    onIdentify: (sessionId: Long, label: String, origin: LatLng?) -> Unit,
    onIdentifyMember: (sessionId: Long, label: String, origin: LatLng?, memberId: Long, memberIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    val scope = rememberCoroutineScope()
    // The extra trees of this capture; empty for a single-tree session.
    val members by repository.observeMembers(sessionId).collectAsState(initial = emptyList())

    // OBSERVED, not loaded once. This used to be a one-shot read, which was fine while a saved row
    // never changed. Confirming a species mutates it from a modal stacked on top of this screen, so a
    // snapshot would still show "Plant not identified yet" after the operator had just identified it.
    // Null while loading / if the row was just deleted.
    val session by repository.observeSession(sessionId).collectAsState(initial = null)

    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenTopBar(title = "Session detail", onBack = onBack)

        val current = session
        if (current == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            LocalPhoto(
                path = current.photoPath,
                reqSizePx = 1024,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(Modifier.height(16.dp))

            // --- Species ---
            // Sits directly under the photo, above everything measured, because for an unidentified
            // plant this is the one outstanding action on the record and it should be the first thing
            // the eye lands on after the image.
            val referencePoint = sessionReferencePoint(
                latitude = current.latitude,
                longitude = current.longitude,
                projectedLatitude = current.projectedLatitude,
                projectedLongitude = current.projectedLongitude
            )
            SectionHeader(if (members.isEmpty()) "Species" else "Species (tree 1 and the cluster)")
            if (members.isNotEmpty()) {
                Text(
                    text = "Identifying here sets tree 1 and suggests the same species for every " +
                        "other tree that has none of its own. Override any tree below.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            SpeciesSection(
                session = current,
                onIdentify = { onIdentify(current.id, current.label, referencePoint) }
            )

            Spacer(Modifier.height(12.dp))

            IdentificationHistorySection(sessionId = current.id)

            Spacer(Modifier.height(16.dp))

            // --- Every tree in this capture (clusters only) ---
            if (members.isNotEmpty()) {
                SectionHeader("Trees in this capture (${members.size + 1})")
                ClusterTreeList(
                    session = current,
                    members = members,
                    onIdentifyMember = { member ->
                        onIdentifyMember(current.id, current.label, referencePoint, member.id, member.memberIndex)
                    },
                    onMemberClass = { member, treeClass ->
                        repository.updateMemberTreeClass(member.id, treeClass)
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

            // --- Tree classification (manual growth form) ---
            SectionHeader(if (members.isEmpty()) "Tree class (growth form)" else "Tree 1 class (growth form)")
            TreeClassSection(
                session = current,
                onUpdate = { treeClass ->
                    repository.updateTreeClass(current.id, treeClass, ClassSource.MANUAL)
                }
            )

            Spacer(Modifier.height(16.dp))

            // --- Confirmed plant ---
            SectionHeader("Confirmed plant")
            Row(verticalAlignment = Alignment.CenterVertically) {
                val treeClass = try {
                    TreeClass.valueOf(current.treeClass)
                } catch (e: Exception) {
                    TreeClass.UNCLASSIFIED
                }
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(canopyStrokeColor(treeClass))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Canopy ${canopyText(current.canopyDiameterMeters)} across",
                    fontWeight = FontWeight.SemiBold
                )
            }
            DetailRow("Label", current.label.ifBlank { "(none)" })
            DetailRow("Area", formatArea(current.areaSquareMeters))
            DetailRow("Saved", timestampFormat.format(Date(current.timestampMillis)))

            Spacer(Modifier.height(16.dp))

            // --- Confirmed canopy outline on satellite imagery (read-only) ---
            SectionHeader("Confirmed canopy outline")
            FootprintMap(session = current, members = members)
            Spacer(Modifier.height(8.dp))
            if (current.polygonVertices.isEmpty()) {
                Text("No canopy vertices stored.", fontSize = 13.sp)
            } else {
                current.polygonVertices.forEachIndexed { index, v ->
                    Text(
                        text = "  ${index + 1}. %.6f, %.6f".format(v.latitude, v.longitude),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- Raw telemetry audit log ---
            SectionHeader("Raw telemetry (audit log)")
            DetailRow("Origin", latLonText(current.latitude, current.longitude))
            DetailRow("GPS accuracy", current.gpsAccuracyMeters?.let { "±%.1f m".format(it) } ?: "—")
            DetailRow(
                "Heading",
                current.headingDegrees?.let {
                    "%.1f° (%s)".format(it, if (current.headingIsTrueNorth) "true north" else "magnetic")
                } ?: "—"
            )
            DetailRow("Pitch", current.pitchDegrees?.let { "%.1f°".format(it) } ?: "—")
            DetailRow("Compass reliability", current.compassReliability)
            DetailRow("Distance", current.distanceMeters?.let { "%.2f m".format(it) } ?: "—")
            DetailRow("Distance method", current.distanceMethod ?: "—")
            DetailRow("Projected target (raw)", latLonText(current.projectedLatitude, current.projectedLongitude))
            DetailRow("Focal length", current.focalLengthPixels?.let { "%.1f px".format(it) } ?: "—")
            DetailRow(
                "Capture image size",
                if (current.imageWidthPixels != null && current.imageHeightPixels != null) {
                    "${current.imageWidthPixels} × ${current.imageHeightPixels} px"
                } else {
                    "—"
                }
            )
            // Both framed axes, not just the average that drove the polygon — the average alone
            // hides crown asymmetry and makes the number impossible to sanity-check afterwards.
            DetailRow("Framed canopy (mean)", canopyText(current.canopyDiameterMeters))
            DetailRow("Framed width", canopyText(current.canopyDiameterHorizontalMeters))
            DetailRow("Framed height", canopyText(current.canopyDiameterVerticalMeters))
            DetailRow("Photo path", current.photoPath)

            Spacer(Modifier.height(24.dp))

            // Jumps to the Map tab centred and highlighted on THIS tree, rather than the all-trees
            // auto-fit the tab shows when opened normally.
            Button(
                onClick = onViewOnMap,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View on map")
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Text("Delete session")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDeleteDialog) {
        val current = session
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this session?") },
            text = {
                Text(
                    if (members.isEmpty()) {
                        "This permanently removes the saved session and its photo. This can't be undone."
                    } else {
                        "This permanently removes all ${members.size + 1} trees of this capture and " +
                            "its photo. This can't be undone."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    if (current != null) {
                        scope.launch {
                            repository.delete(current)
                            onDeleted()
                        }
                    }
                }) {
                    Text("Delete", color = Color(0xFFC62828))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Either the "not identified yet" call to action or the confirmed species.
 *
 * The empty state is a full-width tappable card rather than a plain button because "no species" is the
 * default state of every record the moment it is saved, and a bare row of dashes among the other
 * detail rows would read as a missing value rather than as something to do.
 */
@Composable
private fun SpeciesSection(session: SessionEntity, onIdentify: () -> Unit) {
    val scientificName = session.speciesScientificName

    if (scientificName.isNullOrBlank()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onIdentify),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Plant not identified yet",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Take a close-up of a leaf, flower, fruit, or bark to identify it.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Identify plant  ›",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        return
    }

    Text(text = scientificName, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)
    session.speciesCommonName?.takeIf { it.isNotBlank() }?.let { common ->
        Text(text = common, fontSize = 14.sp)
    }
    DetailRow("Genus", session.speciesGenus?.takeIf { it.isNotBlank() } ?: "—")
    DetailRow(
        "Confidence",
        session.speciesConfidence?.let { "%d%%".format((it * 100).roundToInt().coerceIn(0, 100)) }
            ?: "—"
    )
    DetailRow("Source", session.speciesSource ?: "—")

    Spacer(Modifier.height(8.dp))
    // Re-identify, not "edit": there is no way to hand-type a species here, and the flow it opens is
    // the same one as the first time. A new attempt is appended; the previous one is untouched.
    OutlinedButton(onClick = onIdentify, modifier = Modifier.fillMaxWidth()) {
        Text("Identify again")
    }
}

/**
 * Manual tree classification (growth form) editor.
 *
 * Shows the currently selected class and provides a chip row to change it. Wires up the update
 * to the repository when the operator confirms a selection.
 */
@Composable
private fun TreeClassSection(session: SessionEntity, onUpdate: suspend (TreeClass?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editingClass by remember(session.id) {
        mutableStateOf(try {
            TreeClass.valueOf(session.treeClass)
        } catch (e: Exception) {
            TreeClass.UNCLASSIFIED
        })
    }

    Text(
        text = "Current class: ${editingClass.name.replace("_", " ")}",
        fontSize = 13.sp,
        fontStyle = FontStyle.Italic
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Change tree class (optional):",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    TreeClassSelector(
        selectedClass = if (editingClass == TreeClass.UNCLASSIFIED) null else editingClass,
        onClassSelected = { selected ->
            editingClass = selected ?: TreeClass.UNCLASSIFIED
            scope.launch {
                onUpdate(selected)
                Toast.makeText(context, "Tree class updated", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

/**
 * Collapsed-by-default log of every identification submitted for this session.
 *
 * Hidden entirely when there are none, so a record that was never identified doesn't carry an empty
 * accordion. Shows the confirmed name where there is one and the top suggestion where there isn't,
 * which is what makes a rejected attempt legible: "it offered X, the operator didn't take it".
 */
@Composable
private fun IdentificationHistorySection(sessionId: Long) {
    val context = LocalContext.current
    val repository = remember { SessionRepository.get(context) }
    val attempts by repository.observeIdentificationAttempts(sessionId)
        .collectAsState(initial = emptyList())

    if (attempts.isEmpty()) return

    var expanded by remember(sessionId) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (expanded) "▾  Identification history" else "▸  Identification history",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (attempts.size == 1) "1 attempt" else "${attempts.size} attempts",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (!expanded) return

    attempts.forEach { attempt ->
        val candidates = remember(attempt.id, attempt.candidatesJson) {
            decodeCandidates(attempt.candidatesJson)
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(modifier = Modifier.padding(10.dp)) {
                LocalPhoto(
                    path = attempt.photoPath,
                    reqSizePx = 192,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = timestampFormat.format(Date(attempt.timestampMillis)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                    val confirmed = attempt.confirmedScientificName
                    if (!confirmed.isNullOrBlank()) {
                        Text(
                            text = "Confirmed: $confirmed",
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = "Not confirmed",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    candidates.take(3).forEach { candidate ->
                        Text(
                            text = "  ${candidate.scientificName} · ${candidate.confidencePercent}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (candidates.size > 3) {
                        Text(
                            text = "  +${candidates.size - 3} more",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small read-only satellite map showing the saved canopy (every tree of a cluster, with its outline)
 * + the raw projected target marker.
 */
@Composable
private fun FootprintMap(session: SessionEntity, members: List<ClusterMemberEntity>) {
    val vertices = session.polygonVertices
    val center: LatLng? = when {
        vertices.isNotEmpty() -> LatLng(
            vertices.map { it.latitude }.average(),
            vertices.map { it.longitude }.average()
        )
        session.projectedLatitude != null && session.projectedLongitude != null ->
            LatLng(session.projectedLatitude, session.projectedLongitude)
        else -> null
    }

    if (center == null) {
        Text("No coordinates to map.", fontSize = 13.sp)
        return
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(center, if (members.isEmpty()) 19.5f else 18.5f)
    }

    GoogleMap(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp)),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(mapType = MapType.SATELLITE),
        // Read-only: this is an audit view, so gestures are disabled to avoid accidental panning.
        uiSettings = MapUiSettings(
            scrollGesturesEnabled = false,
            zoomGesturesEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false,
            mapToolbarEnabled = false
        )
    ) {
        val crowns = (listOf(vertices to parseTreeClass(session.treeClass)) +
            members.map { it.polygonVertices to parseTreeClass(it.treeClass) })
            .filter { it.first.size >= 3 }
        if (needsClusterOutline(crowns.size)) {
            ClusterOutlinePolygons(paddedClusterOutline(crowns.map { it.first }))
        }
        crowns.forEach { (crown, treeClass) ->
            Polygon(
                points = crown,
                fillColor = canopyFillColor(treeClass),
                strokeColor = canopyStrokeColor(treeClass),
                strokePattern = canopyStrokePattern(treeClass),
                strokeWidth = 4f,
                geodesic = false,
                zIndex = 1f
            )
        }
        if (session.projectedLatitude != null && session.projectedLongitude != null) {
            Marker(
                state = remember {
                    MarkerState(position = LatLng(session.projectedLatitude, session.projectedLongitude))
                },
                title = "Projected target (raw)"
            )
        }
    }
}

/**
 * Every tree of a cluster, one card each: class, size, how its distance was obtained, and species —
 * with an inherited species marked "from cluster" so a suggestion is never mistaken for an
 * identification of that tree.
 *
 * Tree 1 is the session row and is edited in the sections above; its card here is read-only so
 * the list can be read top to bottom as the whole capture. Members get "Identify this tree" (which
 * replaces an inherited suggestion with the tree's own species) and their own class chips.
 */
@Composable
private fun ClusterTreeList(
    session: SessionEntity,
    members: List<ClusterMemberEntity>,
    onIdentifyMember: (ClusterMemberEntity) -> Unit,
    onMemberClass: suspend (ClusterMemberEntity, TreeClass?) -> Unit
) {
    TreeCard(
        number = 1,
        treeClass = parseTreeClass(session.treeClass),
        diameterMeters = session.canopyDiameterMeters,
        areaSquareMeters = session.areaSquareMeters,
        distanceMeters = session.distanceMeters?.toDouble(),
        distanceMethod = session.distanceMethod,
        speciesName = session.speciesScientificName,
        speciesInherited = false,
        note = "centre tree — edit above"
    )
    val scope = rememberCoroutineScope()
    members.forEach { member -> key(member.id) {
        var editingClass by remember(member.id) { mutableStateOf(false) }
        TreeCard(
            number = member.memberIndex,
            treeClass = parseTreeClass(member.treeClass),
            diameterMeters = member.canopyDiameterMeters,
            areaSquareMeters = member.areaSquareMeters,
            distanceMeters = member.distanceMeters,
            distanceMethod = member.distanceMethod,
            speciesName = member.speciesScientificName,
            speciesInherited = member.speciesInherited,
            note = null
        ) {
            Row {
                TextButton(onClick = { onIdentifyMember(member) }) {
                    Text(if (member.speciesScientificName == null || member.speciesInherited) "Identify this tree" else "Identify again")
                }
                TextButton(onClick = { editingClass = !editingClass }) {
                    Text(if (editingClass) "Done" else "Change class")
                }
            }
            if (editingClass) {
                val selected = parseTreeClass(member.treeClass).takeUnless { it == TreeClass.UNCLASSIFIED }
                TreeClassSelector(
                    selectedClass = selected,
                    onClassSelected = { treeClass -> scope.launch { onMemberClass(member, treeClass) } }
                )
            }
        }
    } }
}

@Composable
private fun TreeCard(
    number: Int,
    treeClass: TreeClass,
    diameterMeters: Double?,
    areaSquareMeters: Double?,
    distanceMeters: Double?,
    distanceMethod: String?,
    speciesName: String?,
    speciesInherited: Boolean,
    note: String?,
    actions: @Composable () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(canopyStrokeColor(treeClass))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Tree $number · ${canopyText(diameterMeters)} across",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                note?.let {
                    Text(text = it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DetailRow("Class", treeClass.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() })
            DetailRow("Area", formatArea(areaSquareMeters))
            DetailRow("Distance", distanceText(distanceMeters, distanceMethod))
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    text = "Species",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(150.dp)
                )
                Text(
                    text = speciesName ?: "not identified",
                    fontSize = 13.sp,
                    fontStyle = if (speciesName != null) FontStyle.Italic else FontStyle.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (speciesName != null && speciesInherited) {
                    Text(
                        text = "from cluster",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            actions()
        }
    }
}

/** "12.40 m · depth image" — the method in words, and flagged when it's an estimate. */
private fun distanceText(meters: Double?, method: String?): String {
    val value = meters?.let { "%.2f m".format(it) } ?: return "—"
    val how = when (method) {
        DistanceMethod.ARCORE_DEPTH.name -> "depth (centre)"
        DistanceMethod.ARCORE_DEPTH_IMAGE.name -> "depth image"
        DistanceMethod.GEOMETRY_FALLBACK.name -> "⚠ ground-plane estimate"
        null -> "method unknown"
        else -> method
    }
    return "$value · $how"
}

/**
 * Top bar with an optional back affordance — tab roots pass null, sub-screens pass a handler — and an
 * optional trailing [actions] slot for screen-level actions such as Export all.
 *
 * The title is weighted so actions pin to the right edge regardless of how long it is.
 */
@Composable
private fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("‹ Back") }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
    HorizontalDivider()
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp)
        )
        Text(text = value, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * Loads a local JPEG thumbnail without a third-party image library.
 *
 * We deliberately avoid Coil here: Coil 3.5.x is built with Kotlin 2.4 (class metadata 2.4.0), which
 * AGP 9's built-in Kotlin 2.2.10 compiler can't read. Since these are app-private local files, a
 * downsampled BitmapFactory decode off the main thread is genuinely lightweight and sidesteps that
 * incompatibility. The decode is keyed on [path] so each row decodes once and is cached by remember.
 */
@Composable
private fun LocalPhoto(path: String, reqSizePx: Int, modifier: Modifier = Modifier) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val decoded = withContext(Dispatchers.IO) { decodeDownsampled(path, reqSizePx) }
        if (decoded == null) failed = true else bitmap = decoded.asImageBitmap()
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = "Capture photo",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(Color(0xFFE0E0E0)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (failed) "no photo" else "…",
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
    }
}

/**
 * Decodes [path] downsampled so the loaded bitmap's larger side is roughly [reqSizePx] — keeps
 * memory low for list thumbnails. Returns null if the file is missing or unreadable.
 */
private fun decodeDownsampled(path: String, reqSizePx: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sampleSize * 2) >= reqSizePx) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(path, options)
    } catch (e: Exception) {
        null
    }
}

/** Measured crown diameter, or an explicit dash for rows saved without one. */
private fun canopyText(diameterMeters: Double?): String =
    diameterMeters?.let { formatCanopyDiameter(it) } ?: "—"

private fun latLonText(lat: Double?, lon: Double?): String =
    if (lat != null && lon != null) "%.6f, %.6f".format(lat, lon) else "—"

package com.gops.spatialmapper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Config
import com.gops.spatialmapper.capture.CameraIntrinsicsSnapshot
import com.gops.spatialmapper.capture.backCameraSensorOrientation
import com.gops.spatialmapper.capture.cameraIntrinsicsSnapshot
import com.gops.spatialmapper.capture.fallbackIntrinsicsForPhoto
import com.gops.spatialmapper.capture.saveArFrameAsJpeg
import com.gops.spatialmapper.distance.ArCoreDepthStatus
import com.gops.spatialmapper.distance.backCameraVerticalFovDegrees
import com.gops.spatialmapper.distance.depthAtCenterMeters
import com.gops.spatialmapper.distance.determineDepthStatus
import com.gops.spatialmapper.distance.groundPlaneDistanceMeters
import com.gops.spatialmapper.history.HistoryScreen
import com.gops.spatialmapper.identify.IdentifyPlantScreen
import com.gops.spatialmapper.map.GlobalMapScreen
import com.gops.spatialmapper.map.MapReviewScreen
import com.gops.spatialmapper.measure.CanopyFramingScreen
import com.gops.spatialmapper.measure.CanopyMeasurement
import com.gops.spatialmapper.projection.roundTripDistanceMeters
import com.gops.spatialmapper.telemetry.CaptureTelemetry
import com.gops.spatialmapper.telemetry.DistanceMethod
import com.gops.spatialmapper.telemetry.TelemetryTracker
import com.gops.spatialmapper.ui.theme.SpatialMapperTheme
import io.github.sceneview.ar.ARScene
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private const val TAG = "SpatialMapperCamera"
private const val TELEMETRY_TAG = "SpatialMapperTelemetry"

/** How long to wait for the capture path to hand back a written JPEG before giving up on it. */
private const val CAPTURE_TIMEOUT_MS = 8_000L

/**
 * A capture waiting to be framed. Everything the framing screen needs to turn an ellipse drawn on the
 * still photo into meters: the photo itself, the camera model it was taken with, and the telemetry
 * snapshot that carries the distance reading and the projected map target.
 */
private data class FramingRequest(
    val capture: CaptureTelemetry,
    val photoPath: String,
    val intrinsics: CameraIntrinsicsSnapshot,
    val distanceMeters: Float
)

/**
 * A pending "review this capture on the map" request. Carries the full telemetry snapshot, the path
 * of the JPEG it saved, and the canopy measurement the operator confirmed on the framing screen — so
 * the review screen has everything it needs to draw a correctly-sized canopy and persist a record.
 */
private data class ReviewRequest(
    val capture: CaptureTelemetry,
    val photoPath: String,
    val measurement: CanopyMeasurement
)

/**
 * A pending "identify this plant" request, raised from a session's detail screen.
 *
 * Carries only what the modal can't cheaply re-derive: which row to write back to, a label for the
 * header, and the origin the proximity check compares the current fix against. Deliberately NOT the
 * whole [com.gops.spatialmapper.data.SessionEntity] — identification is decoupled from capture, and
 * handing the flow the entire row would invite it to start using fields it has no business touching.
 */
private data class IdentifyRequest(
    val sessionId: Long,
    val label: String,
    val origin: LatLng?
)

/**
 * The app's three top-level destinations.
 *
 * These are PEERS: each owns its own sub-flow (capture → framing → map review; history list →
 * session detail) and those sub-flows are deliberately not hoisted into the bar itself.
 *
 * Icons come from material-icons-core only. There is no camera glyph in that set, so Capture uses
 * AddCircle — which reads as "record a new plant" and is arguably the more accurate verb anyway.
 */
private enum class AppTab(val label: String, val icon: ImageVector) {
    CAPTURE("Capture", Icons.Filled.AddCircle),
    HISTORY("History", Icons.AutoMirrored.Filled.List),
    MAP("Map", Icons.Filled.Place)
}

private val AppTabSaver: Saver<AppTab, String> = Saver(
    save = { it.name },
    restore = { name -> runCatching { AppTab.valueOf(name) }.getOrDefault(AppTab.CAPTURE) }
)

class MainActivity : ComponentActivity() {

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpatialMapperTheme {
                var cameraGranted by remember { mutableStateOf(hasPermission(Manifest.permission.CAMERA)) }
                var locationGranted by remember {
                    mutableStateOf(hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                }

                // NAVIGATION — still screen state, now with a real top level.
                //
                // Deliberately NOT navigation-compose: the capture sub-flow passes CaptureTelemetry,
                // CameraIntrinsicsSnapshot and CanopyMeasurement between screens in-process, and none
                // of them are Parcelable. A NavHost would force either parcelizing that whole
                // pipeline or parking it in a shared ViewModel keyed by route — a lot of churn in the
                // measurement path, which is the risky part of this app, to gain route strings the
                // app has no deep links for. So the tabs became a proper peer-level abstraction
                // instead, and each tab keeps its own sub-flow state.
                //
                //   CAPTURE: camera → framingRequest (measure the crown) → reviewRequest → Save
                //   HISTORY: list → selectedSessionId (detail) → identifyRequest (species)
                //   MAP:     all trees, or focused on mapFocusSessionId via "View on map"
                //
                // Sub-flow state lives HERE rather than inside each tab so switching tabs doesn't
                // destroy an in-progress capture or lose your place in the history list.
                // Saved by name rather than relying on enum-as-Serializable, so a restore can't fail
                // on an ordinal shifting if a tab is ever added in the middle.
                var selectedTab by rememberSaveable(stateSaver = AppTabSaver) {
                    mutableStateOf(AppTab.CAPTURE)
                }
                var framingRequest by remember { mutableStateOf<FramingRequest?>(null) }
                var reviewRequest by remember { mutableStateOf<ReviewRequest?>(null) }
                var selectedSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
                var mapFocusSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
                var identifyRequest by remember { mutableStateOf<IdentifyRequest?>(null) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    cameraGranted = results[Manifest.permission.CAMERA] ?: cameraGranted
                    locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] ?: locationGranted
                }

                fun requestPermissions() {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    )
                }

                LaunchedEffect(Unit) {
                    if (!cameraGranted || !locationGranted) {
                        requestPermissions()
                    }
                }

                val framing = framingRequest
                val request = reviewRequest
                val identify = identifyRequest

                // The framing, map-review and identification screens are modal: each owns the full
                // screen and each has its own explicit exit. Showing the tab bar over them would
                // offer an extra exit that silently abandons work in progress — an unsaved capture,
                // or a close-up that has been shot but not yet submitted — so it's hidden for the
                // duration. Identification joins the same convention rather than inventing a second
                // one, which is why this is one flag and not three.
                val inModalFlow = framing != null || request != null || identify != null

                // System back now has somewhere to go. Without this, adding tabs would mean back
                // kills the app from every destination, which is worse than the single-screen state
                // it replaced. Scoped to the navigation the tab bar introduced: the modal flows keep
                // their existing behaviour, since a stray back there would silently discard work.
                BackHandler(
                    enabled = !inModalFlow &&
                        (selectedTab != AppTab.CAPTURE || selectedSessionId != null)
                ) {
                    when {
                        selectedTab == AppTab.HISTORY && selectedSessionId != null ->
                            selectedSessionId = null
                        selectedTab != AppTab.CAPTURE -> selectedTab = AppTab.CAPTURE
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (!inModalFlow) {
                            NavigationBar {
                                AppTab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = selectedTab == tab,
                                        onClick = {
                                            // Reaching Map from the bar always means "show me
                                            // everything" — only "View on map" sets a focus.
                                            if (tab == AppTab.MAP) mapFocusSessionId = null
                                            selectedTab = tab
                                        },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    val screenModifier = Modifier.padding(innerPadding)
                    when {
                        // Identification replaces whatever is showing, but `selectedSessionId` is
                        // untouched, so clearing the request drops the operator back on the same
                        // session detail — which observes its row and therefore already shows the
                        // species that was just confirmed.
                        identify != null -> {
                            IdentifyPlantScreen(
                                sessionId = identify.sessionId,
                                sessionLabel = identify.label,
                                sessionOrigin = identify.origin,
                                onDismiss = { identifyRequest = null },
                                // Nothing else to do on success: detail observes its row, so the
                                // species is already on screen by the time this closes.
                                onIdentified = { identifyRequest = null },
                                modifier = screenModifier
                            )
                        }
                        // Checked before the framing branch so that the framing request can stay
                        // alive underneath the review screen — that's what makes "‹ Back to camera"
                        // land back on the photo for a re-frame instead of throwing the capture away.
                        request != null -> {
                            MapReviewScreen(
                                capture = request.capture,
                                photoPath = request.photoPath,
                                measurement = request.measurement,
                                onBack = { reviewRequest = null },
                                // After a successful save, drop the whole capture and land on the
                                // History tab so the user immediately sees what they just persisted.
                                onSaved = {
                                    reviewRequest = null
                                    framingRequest = null
                                    selectedSessionId = null
                                    selectedTab = AppTab.HISTORY
                                },
                                modifier = screenModifier
                            )
                        }
                        framing != null -> {
                            CanopyFramingScreen(
                                photoPath = framing.photoPath,
                                intrinsics = framing.intrinsics,
                                distanceMeters = framing.distanceMeters,
                                // Cancel abandons the capture outright: nothing was saved, so the
                                // JPEG would be orphaned with no row pointing at it.
                                onCancel = {
                                    discardCapturePhoto(framing.photoPath)
                                    framingRequest = null
                                },
                                onConfirm = { measurement ->
                                    reviewRequest = ReviewRequest(
                                        capture = framing.capture,
                                        photoPath = framing.photoPath,
                                        measurement = measurement
                                    )
                                },
                                modifier = screenModifier
                            )
                        }
                        else -> when (selectedTab) {
                            AppTab.CAPTURE -> {
                                if (cameraGranted && locationGranted) {
                                    CameraCaptureScreen(
                                        onFramingReady = { framingRequest = it },
                                        modifier = screenModifier
                                    )
                                } else {
                                    PermissionStatusScreen(
                                        cameraGranted = cameraGranted,
                                        locationGranted = locationGranted,
                                        onRequestClick = { requestPermissions() },
                                        modifier = screenModifier
                                    )
                                }
                            }
                            AppTab.HISTORY -> {
                                HistoryScreen(
                                    selectedSessionId = selectedSessionId,
                                    onSelectSession = { selectedSessionId = it },
                                    onViewOnMap = { sessionId ->
                                        mapFocusSessionId = sessionId
                                        selectedTab = AppTab.MAP
                                    },
                                    onIdentify = { sessionId, label, origin ->
                                        identifyRequest = IdentifyRequest(sessionId, label, origin)
                                    },
                                    modifier = screenModifier
                                )
                            }
                            AppTab.MAP -> {
                                GlobalMapScreen(
                                    focusedSessionId = mapFocusSessionId,
                                    // Tapping a canopy opens that session's detail directly, with no
                                    // intermediate preview — which means landing in the History tab,
                                    // since detail is History's sub-screen.
                                    onOpenSession = { sessionId ->
                                        selectedSessionId = sessionId
                                        selectedTab = AppTab.HISTORY
                                    },
                                    onClearFocus = { mapFocusSessionId = null },
                                    modifier = screenModifier
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionStatusScreen(
    cameraGranted: Boolean,
    locationGranted: Boolean,
    onRequestClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Camera permission: ${if (cameraGranted) "Granted" else "Denied"}")
        Text(text = "Location permission: ${if (locationGranted) "Granted" else "Denied"}")
        Button(onClick = onRequestClick) {
            Text(text = "Request Permissions")
        }
    }
}

/** Mutable holder for an AR-mode shutter request, read/written across the SceneView frame callback. */
private class ArCaptureRequest {
    var pending: Boolean = false
    var file: File? = null
}

/**
 * Where a capture is between the shutter press and the framing screen.
 *
 * Short and linear: [Capturing] while the JPEG is written, [Resolving] while the camera model for it
 * is worked out, then the framing screen. Nothing here can reject a capture on quality grounds — that
 * judgement now belongs to the operator, who makes it while looking at the photo.
 */
private sealed interface CaptureStage {
    data object Idle : CaptureStage
    data object Capturing : CaptureStage
    data class Resolving(
        val capture: CaptureTelemetry,
        val photoPath: String,
        val intrinsics: CameraIntrinsicsSnapshot?
    ) : CaptureStage
}

// Private because [FramingRequest] is: this screen is only ever mounted from onCreate above.
@Composable
private fun CameraCaptureScreen(
    onFramingReady: (FramingRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val telemetryTracker = remember { TelemetryTracker(context) }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    var depthStatus by remember { mutableStateOf(ArCoreDepthStatus.CHECKING) }
    var cameraHeightText by remember { mutableStateOf("1.5") }
    var arDistanceMeters by remember { mutableStateOf<Float?>(null) }
    // The most recent capture's snapshot, shown as the on-screen projected-target readout.
    var lastCapture by remember { mutableStateOf<CaptureTelemetry?>(null) }
    // Absolute path of the JPEG the most recent shutter press wrote (recorded at shutter time, so
    // it's known even before the async encode finishes).
    var lastPhotoPath by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf<CaptureStage>(CaptureStage.Idle) }

    // The AR path writes the raw sensor image, which is rotated relative to how the phone is held.
    val sensorOrientation = remember { backCameraSensorOrientation(context) }

    // CameraX ImageCapture is only created in the fallback path; lifted here so the shared shutter
    // can reach it. In AR-depth mode this stays null and the shutter routes through arCaptureRequest.
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val arCaptureRequest = remember { ArCaptureRequest() }

    // Camera2 vertical FOV — informational for the fallback overlay / future off-center projection.
    val verticalFovDegrees = remember { backCameraVerticalFovDegrees(context) }

    // Start/stop GPS + sensors with screen visibility (same lifecycle discipline as CameraX/ARCore).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    telemetryTracker.startLocationUpdates()
                    telemetryTracker.startSensorUpdates()
                }
                Lifecycle.Event.ON_STOP -> {
                    telemetryTracker.stopLocationUpdates()
                    telemetryTracker.stopSensorUpdates()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            telemetryTracker.stopLocationUpdates()
            telemetryTracker.stopSensorUpdates()
        }
    }

    // Decide the distance-estimation mode once, before mounting either camera path.
    LaunchedEffect(Unit) {
        depthStatus = determineDepthStatus(context)
    }

    val currentStage = stage

    // Escape hatch: the AR shutter waits for the next frame callback to write the JPEG. If ARCore
    // stalls, that callback never arrives and the operator would sit on the spinner with no way out,
    // so give up and re-arm the shutter instead of hanging.
    LaunchedEffect(currentStage) {
        if (currentStage !is CaptureStage.Capturing) return@LaunchedEffect
        delay(CAPTURE_TIMEOUT_MS)
        if (stage is CaptureStage.Capturing) {
            discardCapturePhoto(lastPhotoPath.orEmpty())
            statusMessage = "The camera didn't return a frame in time — try again."
            stage = CaptureStage.Idle
        }
    }

    // Resolve the camera model for the JPEG that was just written, then hand off to the framing
    // screen. The AR path already read intrinsics from the frame it saved; the CameraX path has to
    // derive them from the file's dimensions plus the Camera2 characteristics, which touches disk.
    LaunchedEffect(currentStage) {
        if (currentStage !is CaptureStage.Resolving) return@LaunchedEffect

        val intrinsics = currentStage.intrinsics
            ?: fallbackIntrinsicsForPhoto(context, currentStage.photoPath)
        val capture = currentStage.capture.copy(intrinsics = intrinsics)
        lastCapture = capture

        // Both of these are required downstream and neither can be recovered after the fact: without
        // a focal length there is no pixels→meters conversion, and without a distance there is
        // nothing to scale it by. The shutter already refuses to fire without a projected target
        // (which implies a distance), so reaching either branch here means the camera itself didn't
        // report what it should have.
        val distance = capture.distanceMeters
        when {
            intrinsics == null -> {
                Log.w(TAG, "No camera intrinsics for ${currentStage.photoPath}; discarding capture")
                discardCapturePhoto(currentStage.photoPath)
                statusMessage = "Couldn't read the camera's focal length — capture discarded."
                stage = CaptureStage.Idle
            }
            distance == null || distance <= 0f -> {
                discardCapturePhoto(currentStage.photoPath)
                statusMessage = "No distance reading for that capture — try again."
                stage = CaptureStage.Idle
            }
            else -> {
                stage = CaptureStage.Idle
                onFramingReady(
                    FramingRequest(
                        capture = capture,
                        photoPath = currentStage.photoPath,
                        intrinsics = intrinsics,
                        distanceMeters = distance
                    )
                )
            }
        }
    }

    val cameraHeightMeters = cameraHeightText.toFloatOrNull()
    val distanceMethod = if (depthStatus == ArCoreDepthStatus.ARCORE_DEPTH_AVAILABLE) {
        DistanceMethod.ARCORE_DEPTH
    } else {
        DistanceMethod.GEOMETRY_FALLBACK
    }
    val geometryDistance = groundPlaneDistanceMeters(cameraHeightMeters ?: 0f, telemetryTracker.pitchDegrees)
    val shownDistance = if (depthStatus == ArCoreDepthStatus.ARCORE_DEPTH_AVAILABLE) {
        arDistanceMeters
    } else {
        geometryDistance
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (depthStatus) {
            ArCoreDepthStatus.CHECKING -> {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Text("Checking ARCore / depth support…", color = Color.White)
                }
            }
            ArCoreDepthStatus.ARCORE_DEPTH_AVAILABLE -> {
                ArDepthCameraFeed(
                    captureRequest = arCaptureRequest,
                    sensorOrientation = sensorOrientation,
                    onDistanceMeters = { arDistanceMeters = it },
                    onCaptureResult = { savedFile, intrinsics ->
                        val pending = lastCapture
                        if (savedFile == null || pending == null) {
                            discardCapturePhoto(lastPhotoPath.orEmpty())
                            statusMessage = "Couldn't save the captured frame — try again."
                            stage = CaptureStage.Idle
                        } else {
                            stage = CaptureStage.Resolving(
                                capture = pending,
                                photoPath = savedFile.absolutePath,
                                intrinsics = intrinsics
                            )
                        }
                    }
                )
            }
            else -> {
                CameraXFallbackFeed(
                    lifecycleOwner = lifecycleOwner,
                    onImageCaptureReady = { imageCapture = it },
                    onError = { statusMessage = it }
                )
            }
        }

        // Center reticle: the distance reading refers to whatever is under this point.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(28.dp)
                .border(2.dp, Color.White.copy(alpha = 0.8f))
        )

        // (The floating "History" button that used to sit here is gone — the History tab in the
        // bottom bar replaces it, and two entry points to the same screen over a camera viewfinder
        // is just clutter.)

        TelemetryOverlay(
            telemetryTracker = telemetryTracker,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            DistanceModeOverlay(
                depthStatus = depthStatus,
                distanceMeters = shownDistance,
                verticalFovDegrees = verticalFovDegrees
            )
            if (distanceMethod == DistanceMethod.GEOMETRY_FALLBACK && depthStatus != ArCoreDepthStatus.CHECKING) {
                CameraHeightField(
                    value = cameraHeightText,
                    onValueChange = { cameraHeightText = it }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ProjectedTargetOverlay(
                capture = lastCapture,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            statusMessage?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(8.dp)
                )
            }

            Button(
                onClick = {
                    // Log the telemetry snapshot first — it's the phase's core deliverable and must
                    // succeed even if the (secondary) photo save fails. snapshot() also computes the
                    // projected target coordinate, so this one line carries the full input→output chain.
                    val snapshot = telemetryTracker.snapshot(shownDistance, distanceMethod)
                    lastCapture = snapshot
                    Log.d(TELEMETRY_TAG, "Capture telemetry: $snapshot")

                    // Refuse to spend a capture that can't be placed on the map. The review screen
                    // needs a projected target, and projection needs a GPS fix, a compass heading,
                    // AND a distance — so this one check covers all three. Checked BEFORE writing
                    // anything, so a capture taken indoors doesn't create a JPEG only to delete it.
                    if (snapshot.projectedLatitude == null || snapshot.projectedLongitude == null) {
                        statusMessage =
                            "No GPS fix, heading, or distance yet — can't place this plant on the map."
                        return@Button
                    }

                    // Reserve the photo path up front so the review/persistence step has it even if
                    // the async encode is still in flight; the same file backs both capture paths.
                    val photoFile = newPhotoFile(context)
                    lastPhotoPath = photoFile.absolutePath
                    stage = CaptureStage.Capturing

                    when (depthStatus) {
                        ArCoreDepthStatus.ARCORE_DEPTH_AVAILABLE -> {
                            arCaptureRequest.file = photoFile
                            arCaptureRequest.pending = true
                        }
                        ArCoreDepthStatus.CHECKING -> {
                            // Shutter disabled while checking; nothing to do.
                            stage = CaptureStage.Idle
                        }
                        else -> {
                            val capture = imageCapture
                            if (capture == null) {
                                statusMessage = "Camera not ready yet"
                                stage = CaptureStage.Idle
                            } else {
                                capturePhotoCameraX(
                                    context = context,
                                    imageCapture = capture,
                                    photoFile = photoFile,
                                    onSaved = { file ->
                                        stage = CaptureStage.Resolving(
                                            capture = snapshot,
                                            photoPath = file.absolutePath,
                                            // Resolved from Camera2 characteristics once the JPEG's
                                            // dimensions are known (no AR frame on this path).
                                            intrinsics = null
                                        )
                                    },
                                    onError = { message ->
                                        statusMessage = message
                                        stage = CaptureStage.Idle
                                    }
                                )
                            }
                        }
                    }
                },
                // One capture at a time: the shutter is inert while a measurement is in flight.
                enabled = depthStatus != ArCoreDepthStatus.CHECKING && stage is CaptureStage.Idle,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.size(72.dp)
            ) {}
        }

        // --- Post-shutter overlay: brief "saving" state until the framing screen takes over. ---
        when (stage) {
            is CaptureStage.Capturing, is CaptureStage.Resolving -> CapturingOverlay()
            CaptureStage.Idle -> Unit
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4000)
            statusMessage = null
        }
    }
}

/** Primary path: ARCore/SceneView AR camera feed with metric depth at the screen center. */
@Composable
private fun ArDepthCameraFeed(
    captureRequest: ArCaptureRequest,
    sensorOrientation: Int,
    onDistanceMeters: (Float?) -> Unit,
    onCaptureResult: (File?, CameraIntrinsicsSnapshot?) -> Unit,
    modifier: Modifier = Modifier
) {
    // rememberUpdatedState keeps the long-lived onSessionUpdated callback pointed at the latest
    // lambdas rather than the ones captured on first composition.
    val currentOnDistance by rememberUpdatedState(onDistanceMeters)
    val currentOnCaptureResult by rememberUpdatedState(onCaptureResult)

    ARScene(
        modifier = modifier.fillMaxSize(),
        planeRenderer = false,
        // In arsceneview 2.3.3 all ARCore config goes through sessionConfiguration (there are no
        // typed depthMode/planeFindingMode params). We only mount this feed once depth support is
        // confirmed, so AUTOMATIC is safe. Planes are disabled — we only need depth.
        sessionConfiguration = { _, config ->
            config.depthMode = Config.DepthMode.AUTOMATIC
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        },
        onSessionUpdated = { _, frame ->
            currentOnDistance(frame.depthAtCenterMeters())

            if (captureRequest.pending) {
                val file = captureRequest.file
                // Read intrinsics from the SAME frame we're about to save: they describe the CPU
                // image acquireCameraImage() returns, which is exactly the JPEG written below, so
                // focal length and photo are guaranteed to share a pixel space.
                val intrinsics = frame.cameraIntrinsicsSnapshot(sensorOrientation)
                val saved = if (file != null) saveArFrameAsJpeg(frame, file) else false
                captureRequest.pending = false
                currentOnCaptureResult(if (saved) file else null, intrinsics)
            }
        }
    )
}

/**
 * Blocking "we're working on it" state shown from the shutter press until the framing screen opens.
 *
 * It covers the whole screen on purpose: the capture is already taken, nothing else on this screen is
 * actionable, and a plain spinner tucked in a corner would leave the operator unsure whether the
 * shutter registered. This is only a JPEG write plus a header read now that there is no inference to
 * wait on, so it should be a brief flash.
 */
@Composable
private fun CapturingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text(
                text = "Saving the photo…",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

/** Fallback path: the Phase-2 CameraX preview + ImageCapture, used when ARCore Depth is unavailable. */
@Composable
private fun CameraXFallbackFeed(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundProvider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder().build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                onImageCaptureReady(capture)
            } catch (exc: Exception) {
                Log.e(TAG, "Camera bind failed", exc)
                onError("Camera error: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            boundProvider?.unbindAll()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { previewView }
    )
}

@Composable
fun TelemetryOverlay(telemetryTracker: TelemetryTracker, modifier: Modifier = Modifier) {
    val latitude = telemetryTracker.latitude
    val longitude = telemetryTracker.longitude
    val gpsAccuracyMeters = telemetryTracker.gpsAccuracyMeters
    val headingDegrees = telemetryTracker.headingDegrees
    val headingIsTrueNorth = telemetryTracker.headingIsTrueNorth
    val pitchDegrees = telemetryTracker.pitchDegrees
    val compassReliability = telemetryTracker.compassReliability

    val gpsLine = if (latitude != null && longitude != null) {
        val accuracyText = gpsAccuracyMeters?.let { "±%.1fm".format(it) } ?: "±?"
        "GPS: %.6f, %.6f (%s)".format(latitude, longitude, accuracyText)
    } else {
        "GPS: acquiring fix…"
    }

    val headingLine = if (headingDegrees != null) {
        val referenceLabel = if (headingIsTrueNorth) "true" else "magnetic, no GPS fix"
        "Heading: %.1f° (%s) — Compass: %s".format(headingDegrees, referenceLabel, compassReliability.label)
    } else {
        "Heading: —"
    }

    val pitchLine = pitchDegrees?.let { "Pitch: %.1f°".format(it) } ?: "Pitch: —"

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Text(text = gpsLine, color = Color.White, fontSize = 12.sp)
        Text(text = headingLine, color = Color.White, fontSize = 12.sp)
        Text(text = pitchLine, color = Color.White, fontSize = 12.sp)
    }
}

/** Shows the active distance mode and the current reading, clearly labeled so there's no ambiguity. */
@Composable
private fun DistanceModeOverlay(
    depthStatus: ArCoreDepthStatus,
    distanceMeters: Float?,
    verticalFovDegrees: Float?,
    modifier: Modifier = Modifier
) {
    val modeLine = when (depthStatus) {
        ArCoreDepthStatus.CHECKING -> "Mode: checking…"
        ArCoreDepthStatus.ARCORE_DEPTH_AVAILABLE -> "Mode: ARCore Depth"
        ArCoreDepthStatus.ARCORE_NO_DEPTH -> "Mode: Geometry Fallback (no ARCore Depth)"
        ArCoreDepthStatus.ARCORE_UNAVAILABLE -> "Mode: Geometry Fallback (no ARCore)"
    }

    val isDepth = depthStatus == ArCoreDepthStatus.ARCORE_DEPTH_AVAILABLE
    val distanceLine = when {
        distanceMeters != null && isDepth -> "Distance: %.1f m".format(distanceMeters)
        distanceMeters != null -> "Distance (estimated): %.1f m".format(distanceMeters)
        isDepth -> "Distance: — (no depth at center)"
        depthStatus == ArCoreDepthStatus.CHECKING -> "Distance: —"
        else -> "Distance (estimated): — (aim below horizon)"
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        Text(text = modeLine, color = Color.White, fontSize = 12.sp)
        Text(text = distanceLine, color = Color.White, fontSize = 12.sp)
        if (!isDepth && depthStatus != ArCoreDepthStatus.CHECKING && verticalFovDegrees != null) {
            Text(text = "vFOV: %.1f°".format(verticalFovDegrees), color = Color.White, fontSize = 12.sp)
        }
    }
}

/**
 * After a capture, shows the projected TARGET coordinate — deliberately distinct from the live
 * origin GPS in the top-left overlay, so "where the target is" is never confused with "where I'm
 * standing". Also shows the round-trip distance sanity check (should match the input distance).
 */
@Composable
private fun ProjectedTargetOverlay(capture: CaptureTelemetry?, modifier: Modifier = Modifier) {
    if (capture == null) return

    val projLat = capture.projectedLatitude
    val projLon = capture.projectedLongitude
    val originLat = capture.latitude
    val originLon = capture.longitude

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (projLat != null && projLon != null) {
            // Amber to visually separate the target result from the white live telemetry.
            Text(
                text = "🎯 Target: %.6f, %.6f".format(projLat, projLon),
                color = Color(0xFFFFD54F),
                fontSize = 14.sp
            )
        } else {
            Text(
                text = "No target projected — distance unavailable",
                color = Color(0xFFFFAB91),
                fontSize = 13.sp
            )
        }

        if (originLat != null && originLon != null) {
            Text(
                text = "Origin (you): %.6f, %.6f".format(originLat, originLon),
                color = Color.White,
                fontSize = 11.sp
            )
        }

        // Sanity check: great-circle distance origin→projected must equal the input distance.
        if (projLat != null && projLon != null && originLat != null && originLon != null &&
            capture.distanceMeters != null
        ) {
            val roundTrip = roundTripDistanceMeters(
                LatLng(originLat, originLon),
                LatLng(projLat, projLon)
            )
            Text(
                text = "check: proj %.1f m vs input %.1f m".format(roundTrip, capture.distanceMeters),
                color = Color.White,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CameraHeightField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Camera height (m)", fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
            .padding(top = 8.dp)
            .width(150.dp)
            .background(Color.Black.copy(alpha = 0.4f))
    )
}

/**
 * Deletes a capture's JPEG when that capture is abandoned (the operator cancelled out of framing, or
 * the capture path failed before it could be framed). Nothing in the database will ever reference it,
 * so leaving it on disk would leak a multi-megabyte file per abandoned attempt. Best-effort: a
 * failure here is logged and ignored. This matches the delete-the-photo-with-the-row policy in
 * SessionRepository.
 */
private fun discardCapturePhoto(path: String) {
    if (path.isBlank()) return
    runCatching {
        val file = File(path)
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Abandoned photo could not be deleted: $path")
        }
    }.onFailure { Log.w(TAG, "Error discarding abandoned photo $path", it) }
}

private fun newPhotoFile(context: Context): File = File(
    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
    "SpatialMapper_${timestampNow()}.jpg"
)

private fun capturePhotoCameraX(
    context: Context,
    imageCapture: ImageCapture,
    photoFile: File,
    onSaved: (File) -> Unit,
    onError: (String) -> Unit
) {
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onSaved(photoFile)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Image capture failed", exception)
                onError("Capture failed: ${exception.message}")
            }
        }
    )
}

private fun timestampNow(): String {
    return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
}

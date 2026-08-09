package com.gops.spatialmapper.identify

import android.content.Context
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.data.IdentificationAttemptEntity
import com.gops.spatialmapper.data.SessionRepository
import com.gops.spatialmapper.telemetry.TelemetryTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "SpatialMapperIdentify"

/** Recorded on the session when a candidate is confirmed. A column value, so keep it stable. */
const val SPECIES_SOURCE_PLANTNET = "plantnet"

/** How long the tap-to-focus ring stays on screen after a tap. */
private const val FOCUS_RING_MS = 900L

/** Auto-cancel window for the focus/metering request, matching the ring's purpose: one shot, then release. */
private const val FOCUS_AUTO_CANCEL_SECONDS = 5L

private val ACCENT = Color(0xFF66BB6A)
private val WARNING_BG = Color(0xFFFFF3CD)
private val WARNING_FG = Color(0xFF7A5B00)

/**
 * Where the identification flow is. Linear and short: shoot → submit → pick.
 *
 * [Failed] is a peer of [Candidates] rather than a flag on it because the two screens offer different
 * actions — one lets you confirm a species, the other only lets you retry or leave. Collapsing them
 * would mean a candidate list that is sometimes empty and sometimes not, which is exactly the kind of
 * "empty means error" ambiguity that produces a blank screen in the field.
 */
private sealed interface IdentifyStage {
    data object Capture : IdentifyStage
    data class Submitting(val photo: File) : IdentifyStage
    data class Candidates(
        val attemptId: Long,
        val candidates: List<SpeciesCandidate>
    ) : IdentifyStage
    data class Failed(val kind: IdentificationErrorKind, val message: String) : IdentifyStage
}

/**
 * Full-screen, modal species identification for one already-saved session.
 *
 * DECOUPLED FROM CAPTURE BY DESIGN. This runs whenever the operator has a moment — possibly days
 * after the original capture — so it deliberately collects no telemetry: no heading, no distance, no
 * projection. All of that is already on the session row and re-measuring it here would produce a
 * second, conflicting record of where the plant is. The only location work done is a read-only
 * proximity sanity check.
 *
 * Modal in the same sense as the canopy-framing and map-review screens: it owns the whole window and
 * the bottom tab bar is hidden for its duration (see MainActivity), because a tab tap mid-flow would
 * silently drop a capture-and-submit the operator is in the middle of.
 *
 * @param sessionOrigin where the operator stood when this plant was recorded, for the proximity check.
 * @param onDismiss     leave without changing the species (close, skip, or back).
 * @param onIdentified  a candidate was confirmed and persisted.
 */
@Composable
fun IdentifyPlantScreen(
    sessionId: Long,
    sessionLabel: String,
    sessionOrigin: LatLng?,
    onDismiss: () -> Unit,
    onIdentified: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { SessionRepository.get(context) }
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf<IdentifyStage>(IdentifyStage.Capture) }
    var confirming by remember { mutableStateOf(false) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // The close-up currently in play. Survives the Submitting → Candidates transition because the
    // attempt row it backs is written before the candidate list is shown.
    var photoFile by remember { mutableStateOf<File?>(null) }

    // --- Proximity sanity check (never blocking) ---------------------------------------------
    // A GPS-only reuse of the capture screen's tracker: location updates are started, sensors are
    // not. Identification needs no heading or pitch, and leaving the rotation-vector listener
    // unregistered keeps this screen off the sensor bus entirely.
    val telemetryTracker = remember { TelemetryTracker(context) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> telemetryTracker.startLocationUpdates()
                Lifecycle.Event.ON_STOP -> telemetryTracker.stopLocationUpdates()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            telemetryTracker.stopLocationUpdates()
        }
    }

    var proximityAcknowledged by remember { mutableStateOf(false) }
    val currentLocation = telemetryTracker.latitude?.let { lat ->
        telemetryTracker.longitude?.let { lon -> LatLng(lat, lon) }
    }
    val proximityWarningMeters = proximityWarningDistanceMeters(sessionOrigin, currentLocation)

    // --- Submit ------------------------------------------------------------------------------
    val currentStage = stage
    LaunchedEffect(currentStage) {
        val submitting = currentStage as? IdentifyStage.Submitting ?: return@LaunchedEffect

        when (val result = identifyPlant(context, submitting.photo, plantNetApiKey())) {
            is IdentificationResult.Success -> {
                val attemptId = repository.recordIdentificationAttempt(
                    IdentificationAttemptEntity(
                        sessionId = sessionId,
                        timestampMillis = System.currentTimeMillis(),
                        photoPath = submitting.photo.absolutePath,
                        candidatesJson = encodeCandidates(result.candidates),
                        confirmedScientificName = null
                    )
                )
                // Written BEFORE the operator picks anything, so an attempt that gets skipped or
                // rejected is still on the record. That is the whole point of the attempts table.
                stage = IdentifyStage.Candidates(attemptId, result.candidates)
            }
            is IdentificationResult.Failure -> {
                // Nothing references this photo — no attempt row was written — so leaving it on disk
                // would leak a multi-megabyte file per failed try, and failures are expected here
                // (offline is the normal state in the field).
                discardIdentifyPhoto(submitting.photo)
                photoFile = null
                stage = IdentifyStage.Failed(result.kind, result.message)
            }
        }
    }

    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4000)
            statusMessage = null
        }
    }

    val close = {
        // Only a photo that never became an attempt is ours to delete.
        if (stage is IdentifyStage.Capture || stage is IdentifyStage.Submitting) {
            photoFile?.let { discardIdentifyPhoto(it) }
        }
        onDismiss()
    }

    // Unlike the capture sub-flow, back here is safe to honour: leaving without confirming a species
    // is already one of the offered outcomes ("Skip"), so back simply performs the same explicit
    // Close action — including its photo cleanup — rather than dropping the operator out of the app.
    // Not the capture screens' convention, because there a stray back WOULD discard unsaved work.
    BackHandler(enabled = !confirming) { close() }

    Column(modifier = modifier.fillMaxSize()) {
        IdentifyTopBar(
            title = "Identify plant",
            subtitle = sessionLabel.ifBlank { "(no label)" },
            onClose = close
        )

        when (val showing = stage) {
            IdentifyStage.Capture -> CaptureStep(
                lifecycleOwner = lifecycleOwner,
                proximityWarningMeters = proximityWarningMeters.takeUnless { proximityAcknowledged },
                onAcknowledgeProximity = { proximityAcknowledged = true },
                statusMessage = statusMessage,
                shutterEnabled = imageCapture != null,
                onImageCaptureReady = { imageCapture = it },
                onError = { statusMessage = it },
                onShutter = {
                    val capture = imageCapture
                    if (capture == null) {
                        statusMessage = "Camera not ready yet"
                    } else {
                        val file = newIdentifyPhotoFile(context)
                        photoFile = file
                        takeCloseUpPhoto(
                            context = context,
                            imageCapture = capture,
                            photoFile = file,
                            onSaved = { stage = IdentifyStage.Submitting(it) },
                            onError = { message ->
                                discardIdentifyPhoto(file)
                                photoFile = null
                                statusMessage = message
                            }
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            )

            is IdentifyStage.Submitting -> SubmittingStep(modifier = Modifier.weight(1f))

            is IdentifyStage.Candidates -> CandidatesStep(
                candidates = showing.candidates,
                enabled = !confirming,
                onSelect = { candidate ->
                    // Latched so a double-tap (or a tap on a second row while the first write is in
                    // flight) can't confirm two different species against one attempt.
                    if (!confirming) {
                        confirming = true
                        scope.launch {
                            repository.confirmSpecies(
                                sessionId = sessionId,
                                attemptId = showing.attemptId,
                                scientificName = candidate.scientificName,
                                commonName = candidate.commonName,
                                genus = candidate.genus,
                                confidence = candidate.score,
                                source = SPECIES_SOURCE_PLANTNET
                            )
                            // Only now — the screen tears down as a result of this call, taking
                            // `scope` with it, so nothing may follow the write.
                            onIdentified()
                        }
                    }
                },
                onRetry = {
                    // The previous attempt row and its photo stay exactly where they are — a retry
                    // adds to the history, it never replaces it.
                    photoFile = null
                    stage = IdentifyStage.Capture
                },
                onSkip = onDismiss,
                modifier = Modifier.weight(1f)
            )

            is IdentifyStage.Failed -> FailedStep(
                kind = showing.kind,
                message = showing.message,
                onRetry = { stage = IdentifyStage.Capture },
                onClose = onDismiss,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// --- Steps --------------------------------------------------------------------------------------

@Composable
private fun CaptureStep(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    proximityWarningMeters: Double?,
    onAcknowledgeProximity: () -> Unit,
    statusMessage: String?,
    shutterEnabled: Boolean,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onError: (String) -> Unit,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        CloseUpCameraFeed(
            lifecycleOwner = lifecycleOwner,
            onImageCaptureReady = onImageCaptureReady,
            onError = onError
        )

        if (proximityWarningMeters != null) {
            ProximityWarningCard(
                distanceMeters = proximityWarningMeters,
                onAcknowledge = onAcknowledgeProximity,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Neutral on purpose: both framings are genuinely fine for the classifier, and an app
            // that leans on "pluck a sample" nudges operators into damaging plants they are
            // surveying. Stated as two equal options, in that order, with no recommendation.
            Text(
                text = "Fill the frame with one leaf, flower, fruit, or a patch of bark.\n" +
                    "Hold a plucked sample against a plain background, or photograph it where it " +
                    "grows — both work equally well.",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Tap anywhere to focus",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            statusMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = onShutter,
                enabled = shutterEnabled,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier.size(72.dp)
            ) {}
        }
    }
}

/**
 * CameraX preview + still capture for the close-up, with tap-to-focus.
 *
 * TAP-TO-FOCUS IS THE REASON THIS ISN'T THE CAPTURE SCREEN'S FEED. The measurement capture aims at a
 * whole tree several metres away, where the default continuous autofocus is already looking at the
 * right thing. A close-up of a single leaf is the opposite case: the lens will happily lock onto the
 * background instead, and a soft leaf photo is exactly what makes an identification wrong rather than
 * merely uncertain. So the touch handler drives an explicit AF+AE metering action at the tapped point.
 *
 * CAPTURE_MODE_MAXIMIZE_QUALITY over the default MINIMIZE_LATENCY for the same reason — shutter lag
 * does not matter when photographing a stationary leaf, and detail does.
 */
@Composable
private fun CloseUpCameraFeed(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val previewView = remember { PreviewView(context) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    // Where the last focus tap landed, in view pixels. Purely feedback: without it a tap is
    // indistinguishable from a missed tap, and the operator can't tell whether focus was requested.
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundProvider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                cameraControl = camera.cameraControl
                onImageCaptureReady(capture)
            } catch (exc: Exception) {
                Log.e(TAG, "Close-up camera bind failed", exc)
                onError("Camera error: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraControl = null
            boundProvider?.unbindAll()
        }
    }

    // Clear the ring after a moment so it reads as "focus requested here just now" rather than a
    // permanent marker.
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(FOCUS_RING_MS)
            focusPoint = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView },
            update = { view ->
                view.setOnTouchListener { touched, event ->
                    when (event.action) {
                        // Claim the gesture, otherwise ACTION_UP is never delivered here.
                        MotionEvent.ACTION_DOWN -> true
                        MotionEvent.ACTION_UP -> {
                            val control = cameraControl
                            if (control != null) {
                                // meteringPointFactory maps view coordinates into the sensor's frame,
                                // which is what makes this correct under any preview scale/rotation —
                                // do not hand raw view pixels to FocusMeteringAction.
                                val point = view.meteringPointFactory
                                    .createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(
                                    point,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                )
                                    .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
                                    .build()
                                runCatching { control.startFocusAndMetering(action) }
                                    .onFailure { Log.w(TAG, "Focus request rejected", it) }
                                focusPoint = Offset(event.x, event.y)
                            }
                            touched.performClick()
                            true
                        }
                        else -> false
                    }
                }
            }
        )

        focusPoint?.let { point ->
            val ringSize = 68.dp
            val halfPx = with(density) { (ringSize / 2).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (point.x - halfPx).roundToInt(),
                            (point.y - halfPx).roundToInt()
                        )
                    }
                    .size(ringSize)
                    .border(2.dp, ACCENT, CircleShape)
            )
        }
    }
}

@Composable
private fun ProximityWarningCard(
    distanceMeters: Double,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = WARNING_BG),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "You're some distance from where this plant was recorded",
                color = WARNING_FG,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "About ${formatDistance(distanceMeters)} away — make sure this is the same plant.",
                color = WARNING_FG,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(6.dp))
            // Dismisses the notice, nothing more. There is no "blocked" state to unblock: the
            // shutter underneath has been live the whole time. This is information, not a gate.
            TextButton(
                onClick = onAcknowledge,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Continue anyway", color = WARNING_FG, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SubmittingStep(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = ACCENT)
            Spacer(Modifier.height(16.dp))
            Text("Asking Pl@ntNet…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Matching against the Indian Subcontinent flora",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CandidatesStep(
    candidates: List<SpeciesCandidate>,
    enabled: Boolean,
    onSelect: (SpeciesCandidate) -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Tap the match. Ranked most likely first.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items = candidates, key = { it.scientificName }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    enabled = enabled,
                    onClick = { onSelect(candidate) }
                )
            }
        }

        HorizontalDivider()

        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("None of these look right — retry with another photo")
            }
            Spacer(Modifier.height(8.dp))
            // Leaves species unset; the free-text label field on the session is the fallback, and
            // this attempt stays in the history with nothing confirmed against it.
            TextButton(
                onClick = onSkip,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip — I'll label it manually")
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: SpeciesCandidate, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.scientificName,
                    fontWeight = FontWeight.SemiBold,
                    fontStyle = FontStyle.Italic,
                    fontSize = 15.sp
                )
                candidate.commonName?.let { common ->
                    Spacer(Modifier.height(2.dp))
                    Text(text = common, fontSize = 13.sp)
                }
                candidate.genus?.let { genus ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Genus $genus",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${candidate.confidencePercent}%",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = ACCENT
            )
        }
    }
}

@Composable
private fun FailedStep(
    kind: IdentificationErrorKind,
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when (kind) {
                IdentificationErrorKind.NO_CONNECTIVITY -> "No internet connection"
                IdentificationErrorKind.NO_MATCH -> "No match found"
                IdentificationErrorKind.RATE_LIMITED -> "Daily limit reached"
                IdentificationErrorKind.INVALID_IMAGE -> "That photo didn't work"
                IdentificationErrorKind.AUTH -> "Pl@ntNet key problem"
                IdentificationErrorKind.SERVER -> "Pl@ntNet is unavailable"
                IdentificationErrorKind.UNKNOWN -> "Identification failed"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))

        // A retry is only worth offering when the operator can plausibly change the outcome. A bad
        // API key or an exhausted quota is not fixable from a field screen, so those get a single
        // honest exit instead of a button that will fail again identically.
        if (kind != IdentificationErrorKind.AUTH && kind != IdentificationErrorKind.RATE_LIMITED) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Try another photo")
            }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close — I'll do this later")
        }
    }
}

@Composable
private fun IdentifyTopBar(title: String, subtitle: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onClose) { Text("‹ Close") }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
    HorizontalDivider()
}

// --- Plumbing -----------------------------------------------------------------------------------

private fun newIdentifyPhotoFile(context: Context): File = File(
    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
    "SpatialMapper_ID_${identifyTimestamp()}.jpg"
)

private fun identifyTimestamp(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())

/** Best-effort cleanup for a close-up that no attempt row will ever reference. */
private fun discardIdentifyPhoto(file: File) {
    runCatching {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Abandoned identification photo could not be deleted: ${file.absolutePath}")
        }
    }.onFailure { Log.w(TAG, "Error discarding identification photo ${file.absolutePath}", it) }
}

private fun takeCloseUpPhoto(
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
                Log.e(TAG, "Close-up capture failed", exception)
                onError("Capture failed: ${exception.message}")
            }
        }
    )
}

/** Metres for anything under a kilometre, kilometres above it — a field-legible distance. */
private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) "%.1f km".format(meters / 1000.0) else "${meters.roundToInt()} m"

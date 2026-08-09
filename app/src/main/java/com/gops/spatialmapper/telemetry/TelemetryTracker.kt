package com.gops.spatialmapper.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.capture.CameraIntrinsicsSnapshot
import com.gops.spatialmapper.projection.projectTargetLocation

/**
 * Reported alongside [Sensor.TYPE_ROTATION_VECTOR] events; mirrors
 * SensorManager.SENSOR_STATUS_* so the UI can flag readings that shouldn't be trusted yet.
 */
enum class CompassReliability(val label: String) {
    UNRELIABLE("Unreliable — move device in a figure-8 to calibrate"),
    LOW("Low — move device in a figure-8 to calibrate"),
    MEDIUM("Medium"),
    HIGH("High"),
    UNKNOWN("Unknown");

    companion object {
        fun fromSensorAccuracy(accuracy: Int): CompassReliability = when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE -> UNRELIABLE
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            else -> UNKNOWN
        }
    }
}

/** Which method produced the distance-to-target reading in a [CaptureTelemetry] snapshot. */
enum class DistanceMethod {
    /** Metric depth from the ARCore Depth API at the center pixel. */
    ARCORE_DEPTH,
    /** Ground-plane geometry estimate from camera height + device pitch (see DistanceEstimation). */
    GEOMETRY_FALLBACK
}

/**
 * A snapshot of all telemetry, frozen at the moment the shutter is pressed. Later phases use
 * this to project the real-world GPS location of whatever the camera was pointed at, so every
 * field records exactly what was known/measured at capture time rather than "current" state.
 */
data class CaptureTelemetry(
    val latitude: Double?,
    val longitude: Double?,
    val gpsAccuracyMeters: Float?,
    val headingDegrees: Float?,
    val headingIsTrueNorth: Boolean,
    val pitchDegrees: Float?,
    val compassReliability: CompassReliability,
    val distanceMeters: Float?,
    val distanceMethod: DistanceMethod?,
    // Phase 5: the projected real-world coordinate of the target (origin + heading + distance),
    // or null when any required input is missing (no GPS fix, no heading, or no distance).
    val projectedLatitude: Double?,
    val projectedLongitude: Double?,
    val timestampMillis: Long,
    /**
     * The camera model for this capture — focal length in pixels plus the image dimensions those
     * pixels refer to. Needed by the pinhole conversion that turns a segmented canopy width in
     * pixels into meters.
     *
     * Filled in by the capture path (ARCore's `Frame.camera.imageIntrinsics` on the AR path, Camera2
     * characteristics on the fallback path) rather than by [TelemetryTracker], because it comes from
     * the frame/JPEG rather than the sensors — hence the null default and the `copy()` at capture.
     */
    val intrinsics: CameraIntrinsicsSnapshot? = null
)

/**
 * Owns the FusedLocationProviderClient + rotation-vector sensor listener, exposes their latest
 * filtered readings as Compose state, and can freeze a [CaptureTelemetry] snapshot on demand.
 *
 * Start/stop calls are meant to be driven by lifecycle events (ON_START / ON_STOP), matching how
 * CameraX's own bindToLifecycle keeps the camera use cases tied to activity visibility — this
 * class does not observe the lifecycle itself, the caller wires that up.
 */
class TelemetryTracker(context: Context) : SensorEventListener {

    // Exponential smoothing: filtered = filtered + alpha * (raw - filtered).
    // 0.15 sits between "twitchy" (alpha close to 1, no smoothing) and "laggy" (alpha close to 0).
    // TYPE_ROTATION_VECTOR at SENSOR_DELAY_UI delivers roughly every 60ms; at alpha=0.15 the
    // filter settles to ~95% of a step change in about 20 samples (~1.2s), which is fast enough
    // to track someone panning/tilting the phone but still absorbs the frame-to-frame jitter
    // that a raw rotation vector shows even when the device is held still.
    private val smoothingAlpha = 0.15f

    var latitude by mutableStateOf<Double?>(null)
        private set
    var longitude by mutableStateOf<Double?>(null)
        private set
    var gpsAccuracyMeters by mutableStateOf<Float?>(null)
        private set

    var headingDegrees by mutableStateOf<Float?>(null)
        private set
    var headingIsTrueNorth by mutableStateOf(false)
        private set
    var pitchDegrees by mutableStateOf<Float?>(null)
        private set
    var compassReliability by mutableStateOf(CompassReliability.UNKNOWN)
        private set

    private var lastLocation: android.location.Location? = null
    private var smoothedHeading: Float? = null
    private var smoothedPitch: Float? = null

    private val rotationMatrix = FloatArray(9)
    // Rotation matrix after remapping the axes for a phone held upright (portrait, back camera
    // aimed at the target). See the remap in onSensorChanged for the full rationale.
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastLocation = location
            latitude = location.latitude
            longitude = location.longitude
            gpsAccuracyMeters = location.accuracy
        }
    }

    @SuppressLint("MissingPermission") // Caller only shows this screen once ACCESS_FINE_LOCATION is granted.
    fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_MIN_INTERVAL_MS)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    fun startSensorUpdates() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopSensorUpdates() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // --- PITCH: derived from the RAW (un-remapped) matrix. ---
        // Phase 4's ground-plane fallback (groundPlaneDistanceMeters) expects the raw-frame pitch,
        // where depression = 90 - |pitch| (flat/face-up pitch≈0 → straight down; upright pitch≈±90 →
        // horizon). That convention is intact, so pitch is read here before any remap.
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val rawPitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()

        // --- AZIMUTH / HEADING: derived from a REMAPPED matrix. ---
        // BUG FIX (bearing inversion): this app holds the phone UPRIGHT in portrait with the back
        // camera pointing at a distant target and the screen facing the user. In that pose the
        // device's Y axis (top of phone) points at the sky and its Z axis (out of the screen) points
        // back at the user — the OPPOSITE of where the camera looks. getOrientation() on the raw
        // matrix returns the azimuth of the top-of-phone/flat reference, which for an upright phone
        // is degenerate/inverted, so the projected target landed 180° opposite the real building.
        //
        // remapCoordinateSystem(rotationMatrix, AXIS_X, AXIS_Z, remappedRotationMatrix) builds a
        // frame where the new device Y axis coincides with the camera's forward direction (-Z of the
        // raw frame): mapping raw X→newX and raw Y→newZ forces newY = -rawZ = camera forward. After
        // the remap, getOrientation()[0] is the true compass bearing the camera is pointing along,
        // which is exactly what the GPS projection needs.
        //
        // Do NOT "fix" this by adding 180° — that only masks the coordinate-frame error and would
        // break under landscape/other device orientations. Keep the axis remap.
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            remappedRotationMatrix
        )
        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)
        val rawAzimuthDeg = normalizeDegrees(Math.toDegrees(orientationAngles[0].toDouble()).toFloat())

        val correctedAzimuthDeg = applyTrueNorthCorrection(rawAzimuthDeg)

        smoothedHeading = smoothAngleDegrees(smoothedHeading, correctedAzimuthDeg, smoothingAlpha)
        smoothedPitch = smoothLinear(smoothedPitch, rawPitchDeg, smoothingAlpha)

        headingDegrees = smoothedHeading
        pitchDegrees = smoothedPitch
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            compassReliability = CompassReliability.fromSensorAccuracy(accuracy)
        }
    }

    /** True-north correction needs a real fix; falls back to magnetic north and flags it. */
    private fun applyTrueNorthCorrection(rawAzimuthDeg: Float): Float {
        val location = lastLocation
        if (location == null) {
            headingIsTrueNorth = false
            return rawAzimuthDeg
        }
        headingIsTrueNorth = true
        val geomagneticField = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        )
        return normalizeDegrees(rawAzimuthDeg + geomagneticField.declination)
    }

    /** Exponential smoothing across the 0/360 wraparound, via shortest angular distance. */
    private fun smoothAngleDegrees(previous: Float?, new: Float, alpha: Float): Float {
        if (previous == null) return new
        val shortestDelta = normalizeSignedDegrees(new - previous)
        return normalizeDegrees(previous + alpha * shortestDelta)
    }

    private fun smoothLinear(previous: Float?, new: Float, alpha: Float): Float {
        if (previous == null) return new
        return previous + alpha * (new - previous)
    }

    private fun normalizeDegrees(degrees: Float): Float = (degrees % 360f + 360f) % 360f

    /** Maps a delta to (-180, 180] so smoothing crosses 0/360 the short way. */
    private fun normalizeSignedDegrees(degrees: Float): Float =
        ((degrees + 180f) % 360f + 360f) % 360f - 180f

    /**
     * Freezes the current filtered telemetry into an immutable snapshot. Distance is passed in
     * because it is measured by the camera/AR pipeline (see DistanceEstimation), not by this
     * sensor tracker, but it belongs in the same at-shutter snapshot.
     *
     * The target's projected coordinate is computed here (via [projectTargetLocation]) so every
     * snapshot carries the full input→output chain. It is only computed when origin lat/lon, a
     * heading, and a distance are all available; otherwise both projected fields are null.
     */
    fun snapshot(distanceMeters: Float?, distanceMethod: DistanceMethod?): CaptureTelemetry {
        val currentLatitude = latitude
        val currentLongitude = longitude
        val currentHeading = headingDegrees

        val projected: LatLng? = if (
            currentLatitude != null && currentLongitude != null &&
            currentHeading != null && distanceMeters != null
        ) {
            projectTargetLocation(
                origin = LatLng(currentLatitude, currentLongitude),
                headingDegrees = currentHeading.toDouble(),
                distanceMeters = distanceMeters.toDouble()
            )
        } else {
            null
        }

        return CaptureTelemetry(
            latitude = currentLatitude,
            longitude = currentLongitude,
            gpsAccuracyMeters = gpsAccuracyMeters,
            headingDegrees = currentHeading,
            headingIsTrueNorth = headingIsTrueNorth,
            pitchDegrees = pitchDegrees,
            compassReliability = compassReliability,
            distanceMeters = distanceMeters,
            distanceMethod = distanceMethod,
            projectedLatitude = projected?.latitude,
            projectedLongitude = projected?.longitude,
            timestampMillis = System.currentTimeMillis()
        )
    }

    companion object {
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val LOCATION_MIN_INTERVAL_MS = 500L
    }
}

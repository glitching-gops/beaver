package com.gops.spatialmapper.distance

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.delay
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

private const val TAG = "SpatialMapperDistance"

/**
 * Which distance-estimation method this device can use, decided once when the camera screen opens.
 *
 *  - [ARCORE_DEPTH_AVAILABLE]: ARCore is installed and the ARCore Depth API is supported → primary,
 *    metric depth from the camera feed.
 *  - [ARCORE_NO_DEPTH]: ARCore exists but this device has no Depth support → geometry fallback.
 *  - [ARCORE_UNAVAILABLE]: ARCore not supported/installed on this device → geometry fallback.
 *  - [CHECKING]: still determining (ARCore availability check can be asynchronous).
 */
enum class ArCoreDepthStatus {
    CHECKING,
    ARCORE_DEPTH_AVAILABLE,
    ARCORE_NO_DEPTH,
    ARCORE_UNAVAILABLE
}

/**
 * Determines [ArCoreDepthStatus] for this device.
 *
 * Uses [ArCoreApk.checkAvailability] (polling while it reports UNKNOWN_CHECKING), and — only when
 * ARCore is installed — creates a throwaway [Session] purely to query
 * [Session.isDepthModeSupported], then closes it. The real rendering session is created later by
 * the ARSceneView composable; this probe just lets us decide which camera path to mount so we
 * don't have to mount the AR feed and then tear it down if depth turns out to be unsupported.
 *
 * Must run with CAMERA permission already granted (the caller gates the whole screen on it).
 */
suspend fun determineDepthStatus(context: Context): ArCoreDepthStatus {
    val availability = pollArCoreAvailability(context)
    if (!availability.isSupported) {
        Log.d(TAG, "ARCore not supported/installed: $availability")
        return ArCoreDepthStatus.ARCORE_UNAVAILABLE
    }

    // Only SUPPORTED_INSTALLED lets us open a session without triggering a Play Store install flow,
    // which is out of scope for this phase — anything else falls back to geometry.
    if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
        Log.d(TAG, "ARCore supported but not installed ($availability); using geometry fallback")
        return ArCoreDepthStatus.ARCORE_UNAVAILABLE
    }

    return try {
        val session = Session(context)
        val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        session.close()
        if (depthSupported) {
            ArCoreDepthStatus.ARCORE_DEPTH_AVAILABLE
        } else {
            ArCoreDepthStatus.ARCORE_NO_DEPTH
        }
    } catch (e: Exception) {
        // UnavailableException subclasses (APK too old, device not compatible, etc.) or any other
        // session-creation failure: degrade gracefully to the geometry fallback rather than crash.
        Log.w(TAG, "ARCore session probe failed; using geometry fallback", e)
        ArCoreDepthStatus.ARCORE_UNAVAILABLE
    }
}

private val ArCoreApk.Availability.isSupported: Boolean
    get() = this == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
        this == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ||
        this == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED

private suspend fun pollArCoreAvailability(context: Context): ArCoreApk.Availability {
    // checkAvailability may return UNKNOWN_CHECKING while it queries Play Services; poll briefly.
    repeat(10) {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        if (availability != ArCoreApk.Availability.UNKNOWN_CHECKING) {
            return availability
        }
        delay(200)
    }
    return ArCoreApk.getInstance().checkAvailability(context)
}

/**
 * Reads the ARCore Depth API value at the center pixel of the depth image and returns it in meters,
 * or null if depth isn't available for this frame yet.
 *
 * The center of the depth image corresponds to the center of the camera view (and thus the center
 * of the screen crosshair), so no view-size mapping is needed. DEPTH16 values are unsigned 16-bit
 * millimeters; a value of 0 means "no depth estimate at this pixel".
 */
fun Frame.depthAtCenterMeters(): Float? {
    if (camera.trackingState != TrackingState.TRACKING) return null

    val depthImage = try {
        acquireDepthImage16Bits()
    } catch (e: NotYetAvailableException) {
        // Depth for this frame isn't ready yet — normal for the first frames after resume.
        return null
    } catch (e: Exception) {
        Log.w(TAG, "acquireDepthImage16Bits failed", e)
        return null
    }

    return depthImage.use { image ->
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        val centerX = image.width / 2
        val centerY = image.height / 2
        val offset = centerY * plane.rowStride + centerX * plane.pixelStride
        val depthMillimeters = (buffer.getShort(offset).toInt() and 0xFFFF)
        if (depthMillimeters == 0) null else depthMillimeters / 1000f
    }
}

/**
 * Ground-plane geometry fallback for devices without ARCore Depth.
 *
 * Model: the camera sits at [cameraHeightMeters] above flat ground and its optical axis is aimed
 * some angle below horizontal (the "depression" angle). The central ray hits the ground at
 * horizontal distance:
 *
 *     distance = height / tan(depression)
 *
 * ASSUMPTION: the target's base sits on flat ground at the same level as the camera's footpoint.
 * On sloped ground or for elevated targets this estimate degrades — that's inherent to the method.
 *
 * Deriving depression from the Phase-3 orientation pitch (getOrientation()[1], degrees):
 *   - Phone flat, face up  → pitch ≈ 0°   → back camera points straight down → depression ≈ 90°.
 *   - Phone upright portrait → |pitch| ≈ 90° → camera points at the horizon    → depression ≈ 0°.
 * So depression ≈ 90° − |pitch|. Using |pitch| makes this robust to the platform's pitch sign
 * convention in the near-upright regime this app is used in. NOTE: this convention should still be
 * verified on-device — the pitch/depression mapping is exactly the kind of thing that can flip.
 *
 * Returns null when aiming at/above the horizon (depression ≤ [MIN_DEPRESSION_DEGREES]) or nearly
 * straight down, where the estimate is unusable or wildly noisy.
 */
fun groundPlaneDistanceMeters(cameraHeightMeters: Float, pitchDegrees: Float?): Float? {
    if (pitchDegrees == null || cameraHeightMeters <= 0f) return null

    val depressionDegrees = 90f - abs(pitchDegrees)
    if (depressionDegrees <= MIN_DEPRESSION_DEGREES || depressionDegrees >= MAX_DEPRESSION_DEGREES) {
        return null
    }

    val distance = cameraHeightMeters / tan(Math.toRadians(depressionDegrees.toDouble())).toFloat()
    return if (distance.isFinite() && distance > 0f) distance else null
}

/** Aiming this close to (or above) the horizon makes distance explode toward infinity. */
private const val MIN_DEPRESSION_DEGREES = 1f

/** Aiming this close to straight-down means there is effectively no forward distance to estimate. */
private const val MAX_DEPRESSION_DEGREES = 89f

/**
 * Vertical field of view (degrees) of the back camera, from Camera2 characteristics:
 * vFOV = 2 · atan(sensorHeight / (2 · focalLength)).
 *
 * Exposed for the fallback overlay / future off-center-pixel projection. It does NOT enter the
 * center-pixel distance above: the center of the screen is the optical axis, whose depression is
 * given directly by device pitch regardless of FOV. Returns null if characteristics are missing.
 */
fun backCameraVerticalFovDegrees(context: Context): Float? {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
    return try {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (physicalSize == null || focalLengths == null || focalLengths.isEmpty()) continue

            // NOTE: mapping the sensor's physical axes to the device's "vertical" depends on sensor
            // orientation; physicalSize.height is used here as a reasonable approximation.
            val sensorVerticalMm = physicalSize.height
            val focalLengthMm = focalLengths[0]
            return Math.toDegrees(2.0 * atan((sensorVerticalMm / (2f * focalLengthMm)).toDouble())).toFloat()
        }
        null
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query back-camera vertical FOV", e)
        null
    }
}

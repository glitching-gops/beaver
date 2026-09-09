package com.gops.spatialmapper.scout

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gops.spatialmapper.MainActivity
import com.gops.spatialmapper.R
import com.gops.spatialmapper.survey.saveIfRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SpatialMapperScoutSvc"

/**
 * Keeps high-accuracy location updates flowing for the whole time Scout mode is on — including with
 * the screen locked, which is the entire reason this is a service and not just a
 * [com.google.android.gms.location.FusedLocationProviderClient] owned by the Map composable.
 *
 * A plain in-composable location client (what the Capture screen uses) stops at ON_STOP, so locking
 * the phone while walking between trees would silently break the trail. Android will not let an
 * app receive location in the background without a foreground service of type `location`, so that is
 * what this is.
 *
 * STARTED, NOT BOUND. See the rationale on [ScoutSession]: binding would re-tie the location stream
 * to the UI's lifetime and re-create the problem the service exists to solve. Fixes go into
 * [ScoutSession], which the Map tab observes; [onBind] returns null.
 *
 * ANDROID 14/15/16 FOREGROUND-SERVICE RULES, and how each is met:
 *  - `android:foregroundServiceType="location"` is declared in the manifest, and the same type is
 *    passed to [ServiceCompat.startForeground]. Since API 34 both are required; the manifest alone
 *    throws MissingForegroundServiceTypeException at startForeground time.
 *  - FOREGROUND_SERVICE and FOREGROUND_SERVICE_LOCATION are held (both install-time permissions).
 *  - The start must happen while the app is visible. It is: the only caller is the Scout toggle on
 *    the Map tab, which is by definition on screen when tapped. A start from the background throws
 *    ForegroundServiceStartNotAllowedException, which [start] catches rather than crashing.
 *  - [startForeground] is called FIRST in [onStartCommand], before requesting location, so the 5 s
 *    (ANR) window can't be missed by a slow provider call.
 *  - ACCESS_BACKGROUND_LOCATION is deliberately NOT requested. A `location`-typed foreground service
 *    started while visible may use foreground location for as long as it runs; background location
 *    permission is for receiving location with no foreground service at all, which this never does.
 *  - Android 15's foreground-service timeouts apply to `dataSync`/`mediaProcessing`, not `location`,
 *    so a long walk is not cut off.
 */
class ScoutLocationService : Service() {

    private lateinit var locationClient: FusedLocationProviderClient

    /**
     * Set once [startForeground] has succeeded. Guards [onDestroy] against calling stopForeground on
     * a service that never made it into the foreground (a permission failure, say), which would
     * otherwise log a spurious error.
     */
    private var inForeground = false

    /**
     * The [ScoutSession] token this instance is serving, captured at start. Handed back in
     * [onDestroy] so a superseded instance cannot tear down a session the operator has already
     * restarted — see [ScoutSession.onServiceGone].
     */
    private var servingToken: Long? = null

    /**
     * Scope for the one asynchronous thing this service does: persisting a survey that was still
     * recording when tracking ended.
     *
     * Deliberately NOT cancelled in onDestroy. A survey save started as the service goes away is
     * exactly the work that must survive the component that started it — cancelling it would throw
     * away a walk at the only moment it was ever at risk. The writes are single small inserts, so
     * this cannot outlive the process by long.
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            // A late callback after stop() would otherwise re-populate a session the operator has
            // already ended. ScoutSession.onFix also checks, but dropping it here keeps the log
            // honest about what the service is actually doing.
            if (!ScoutSession.isActive) return
            ScoutSession.onFix(location)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested from the notification")
            // stop() hands back any survey still recording. Persist it BEFORE stopSelf, and stop
            // with the startId so the service stays alive until the write lands — an officer who
            // ends a walk from the lock screen must not lose it to a race with teardown.
            val recording = ScoutSession.stop()
            saveScope.launch {
                withContext(NonCancellable) { saveIfRecording(applicationContext, recording) }
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // FIRST, before anything that can be slow or throw: the system gives a narrow window between
        // startForegroundService() and startForeground() before it kills us with an ANR.
        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        }.onFailure { error ->
            Log.e(TAG, "startForeground failed; Scout cannot run", error)
            ScoutSession.onServiceGone("startForeground failed: ${error.message}")
            stopSelf()
        }.isSuccess
        if (!promoted) return START_NOT_STICKY
        inForeground = true
        servingToken = ScoutSession.currentToken

        if (!hasLocationPermission()) {
            // Should be unreachable — the Map tab is only usable after Phase 1 granted this — but a
            // revoke while the app was backgrounded would land here, and a SecurityException from
            // requestLocationUpdates would take the process down.
            Log.e(TAG, "ACCESS_FINE_LOCATION is not granted; stopping")
            ScoutSession.onServiceGone("location permission missing", servingToken)
            stopSelf()
            return START_NOT_STICKY
        }

        requestLocationUpdates()
        // NOT sticky: a system-killed Scout session must not silently resurrect hours later with an
        // empty trail and a notification the operator never asked for. Restarting is their call.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Scout service destroyed")
        runCatching { locationClient.removeLocationUpdates(locationCallback) }
            .onFailure { Log.w(TAG, "Failed removing location updates", it) }
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        // Covers the system-kill path. A deliberate stop already reset the state, and this is a
        // no-op when inactive, so it cannot clobber a session the operator restarted meanwhile.
        //
        // The return value is the last chance to rescue a survey that was still recording when the
        // system decided to take the service away. Best-effort by nature — onDestroy does not wait
        // for us — but it turns "killed gracefully" from data loss into a saved track, and the
        // NonCancellable block means the save is not aborted by the scope going away underneath it.
        val abandoned = ScoutSession.onServiceGone("service destroyed", servingToken)
        if (abandoned != null) {
            saveScope.launch {
                withContext(NonCancellable) { saveIfRecording(applicationContext, abandoned) }
            }
        }
        super.onDestroy()
    }

    // Lint can't see across the method boundary to the hasLocationPermission() gate in
    // onStartCommand, and doesn't recognise runCatching as handling the SecurityException. Both
    // guards are real and are the two things it asks for: the call is unreachable without the
    // permission, and a SecurityException is caught below and turned into a clean shutdown rather
    // than a crash. Suppressed with the same reasoning TelemetryTracker uses for its own client.
    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            SCOUT_UPDATE_INTERVAL_MILLIS
        )
            .setMinUpdateIntervalMillis(SCOUT_MIN_UPDATE_INTERVAL_MILLIS)
            // Deliver the first fix as soon as there is one, even if it is coarse. The accuracy
            // filter is what decides whether to trust it; waiting here would just leave the operator
            // looking at an empty map for the first half-minute under canopy.
            .setWaitForAccurateLocation(false)
            .build()

        // Looper.getMainLooper() rather than the service's own thread: the callback does nothing but
        // a StateFlow update, and this keeps it off a thread we would then have to manage.
        runCatching {
            locationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }.onSuccess {
            Log.i(
                TAG,
                "Requesting HIGH_ACCURACY fixes every ${SCOUT_UPDATE_INTERVAL_MILLIS}ms " +
                    "(accepting accuracy <= ${SCOUT_MAX_ACCURACY_METERS}m)"
            )
        }.onFailure { error ->
            Log.e(TAG, "requestLocationUpdates failed", error)
            ScoutSession.onServiceGone("location updates rejected: ${error.message}", servingToken)
            stopSelf()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The persistent notification. Tapping it returns to the app; the Stop action ends Scout without
     * making the operator find the app first, which matters when the phone is in a pocket.
     */
    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                // Reuse the existing task rather than stacking a second MainActivity on top of the
                // one the operator left running.
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ScoutLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpatialMapper is tracking your position")
            .setContentText("Tap to return to the map.")
            .setSmallIcon(R.drawable.ic_scout_notification)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            // Ongoing + LOW: it must not be swipeable (it is the thing keeping tracking alive) but it
            // must also not buzz or peek every time the service restarts.
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Scout tracking",
            // LOW: visible and persistent, but silent and never a heads-up banner over the map.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Scout mode is tracking your position in the field."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "scout_tracking"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_STOP = 2
        private const val ACTION_STOP = "com.gops.spatialmapper.scout.STOP"

        /**
         * Starts tracking. MUST be called while the app is in the foreground — see the Android 14
         * note in the class docs.
         *
         * [ScoutSession.start] runs first so the UI flips to "active" immediately rather than after
         * the service has spun up, and is rolled back if the start is refused. Returns false when
         * the system rejected the start, so the caller can tell the operator rather than leaving a
         * toggle that looks on and does nothing.
         */
        fun start(context: Context): Boolean {
            ScoutSession.start()
            val intent = Intent(context, ScoutLocationService::class.java)
            return runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { error ->
                    // ForegroundServiceStartNotAllowedException on API 31+ if we were not actually
                    // foreground. Undo the optimistic state so the toggle snaps back off.
                    Log.e(TAG, "Could not start the Scout service", error)
                    ScoutSession.onServiceGone("start refused: ${error.message}")
                }
                .isSuccess
        }

        /**
         * Stops tracking and clears the trail. Safe to call when nothing is running.
         *
         * Routed through the service's own ACTION_STOP rather than stopService(), so the UI toggle
         * and the notification's Stop button take the SAME path — which is the path that persists a
         * survey still in progress. A direct stopService() here would skip that and lose the walk,
         * and the bug would only appear for operators who happened to use the in-app button.
         *
         * Guarded on isActive because sending an intent to a stopped service would START it; when
         * nothing is running there is nothing to stop.
         */
        fun stop(context: Context) {
            if (!ScoutSession.isActive) return
            val intent = Intent(context, ScoutLocationService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure { error ->
                    // If the service cannot be reached at all, fall back to clearing state directly
                    // so the UI does not sit there claiming to track. Any recording is handed back
                    // by stop() and dropped here — logged, because it is the one path that can.
                    Log.e(TAG, "Could not deliver ACTION_STOP; clearing state directly", error)
                    val orphaned = ScoutSession.stop()
                    if (orphaned != null) {
                        Log.e(TAG, "Lost a survey of ${orphaned.points.size} point(s) — service unreachable")
                    }
                    context.stopService(Intent(context, ScoutLocationService::class.java))
                }
        }
    }
}

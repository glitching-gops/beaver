package com.gops.spatialmapper.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gops.spatialmapper.SpatialMapperApplication
import com.gops.spatialmapper.scout.ScoutLocationService
import com.gops.spatialmapper.scout.ScoutSession
import com.gops.spatialmapper.scout.ScoutState
import com.gops.spatialmapper.survey.endAndSaveSurvey
import com.gops.spatialmapper.survey.formatElapsed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gops.spatialmapper.scout.formatTrailLength
import com.gops.spatialmapper.scout.trailLengthMeters

/**
 * The Scout mode toggle and its live readout, plus the recenter control.
 *
 * Kept out of [GlobalMapScreen] so that screen stays about the map itself; everything here is
 * presentation over [ScoutState], which the service owns.
 */

/**
 * Returns a callback that turns Scout mode on or off, handling the notification permission on the
 * way in.
 *
 * POST_NOTIFICATIONS is a RUNTIME permission from API 33 and this app is minSdk 34, so it always
 * needs asking. It is requested here — at the moment the operator asks for tracking — rather than at
 * launch, because that is the only moment the request makes sense to them.
 *
 * Denying it does NOT block Scout. A foreground service still runs with the notification suppressed
 * (the system surfaces it under Active Apps instead), so refusing the prompt costs the operator the
 * "tap to return" affordance, not the tracking. Blocking on it would be punishing them for declining
 * something the feature does not actually require, so the service is started either way and the
 * result callback simply proceeds.
 *
 * The other two new permissions — FOREGROUND_SERVICE and FOREGROUND_SERVICE_LOCATION — are
 * install-time (normal) permissions. They are granted at install from the manifest and there is
 * nothing to request at runtime.
 */
@Composable
fun rememberScoutToggle(): (Boolean) -> Unit {
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "Scouting will still track, but without the notification you won't be able to tap " +
                    "back to the map from the lock screen.",
                Toast.LENGTH_LONG
            ).show()
        }
        startScouting(context)
    }

    return remember(context) {
        { activate: Boolean ->
            if (!activate) {
                ScoutLocationService.stop(context)
            } else if (hasNotificationPermission(context)) {
                startScouting(context)
            } else {
                // The service is started from the launcher's result callback, so it still happens
                // while the app is foreground — which is what Android 14+ requires.
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/**
 * Returns a callback that starts or ends survey recording.
 *
 * Starting is pure in-memory state ([ScoutSession.startSurvey]) — the points come from the location
 * stream Scout is already consuming, so there is nothing to subscribe to and nothing to wait for.
 *
 * Ending writes a row, and does so on [SpatialMapperApplication.appScope] rather than a
 * `rememberCoroutineScope()`. That is the important detail: a composable's scope dies when the
 * composable leaves composition, so tapping End survey and immediately switching to the History tab
 * would cancel the insert mid-flight and lose the walk. The save has to outlive the screen that
 * asked for it.
 */
@Composable
fun rememberSurveyToggle(): (Boolean) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { record: Boolean ->
            if (record) {
                ScoutSession.startSurvey()
            } else {
                SpatialMapperApplication.appScope.launch { endAndSaveSurvey(context) }
            }
            Unit
        }
    }
}

private fun startScouting(context: Context) {
    if (!ScoutLocationService.start(context)) {
        Toast.makeText(
            context,
            "Couldn't start tracking — Android refused the location service.",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Bottom panel: start/stop, and while running, what tracking is actually doing.
 *
 * Bottom rather than top because it is the one control on this screen the operator uses while
 * walking, one-handed — and because the top is already occupied by the tree-count / focus header.
 *
 * The readout deliberately reports a REJECTED fix's accuracy too. The alternative — showing nothing
 * when the signal is poor — produces the worst field experience there is: a marker that has silently
 * stopped moving with no explanation, which reads as a crash rather than as canopy.
 */
@Composable
fun ScoutPanel(
    scout: ScoutState,
    onToggle: (Boolean) -> Unit,
    onSurveyToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Ticking elapsed time for the in-progress readout. Keyed on the recording's start so the effect
    // is cancelled the instant the survey ends, and restarted from scratch for the next one.
    val recordingStartedAt = scout.survey?.startedAtMillis
    var elapsedMillis by remember(recordingStartedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(recordingStartedAt) {
        if (recordingStartedAt == null) return@LaunchedEffect
        while (true) {
            elapsedMillis = System.currentTimeMillis() - recordingStartedAt
            delay(1_000)
        }
    }

    Card(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
    ) {
        // Card > Column > [status row, divider, survey row]. The status row used to be the Card's
        // only child; the Column is what lets the survey controls sit under it without the two
        // fighting over the same horizontal space.
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (!scout.active) {
                    Text(
                        text = "Scout mode",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = Color.Black
                    )
                    Text(
                        text = "Track your position and the path you walk. Keeps running with the " +
                            "screen locked.",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (scout.lastFixUsable) SCOUT_POSITION_COLOR else WEAK_SIGNAL_COLOR
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = scoutStatusText(scout),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Color.Black
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = scoutTrailText(scout),
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                }
            }

            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { onToggle(!scout.active) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (scout.active) STOP_SCOUTING_COLOR else SCOUT_POSITION_COLOR
                )
            ) {
                Text(if (scout.active) "Stop" else "Start")
            }
            }

            // --- Survey recording, only while Scout is running ---
            // Nested inside the active branch on purpose: a survey records the Scout position
            // stream, so offering to start one with Scout off would be offering to record nothing.
            // ScoutSession.startSurvey refuses in that state too — the hidden button is the
            // affordance, the refusal is the guarantee.
            if (scout.active) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (scout.survey == null) {
                            Text(
                                text = "Survey not recording",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                            Text(
                                text = "Record the path you walk as a saved track.",
                                fontSize = 12.sp,
                                color = Color.DarkGray
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(RECORDING_COLOR)
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    text = "Recording · ${formatElapsed(elapsedMillis)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                            }
                            Text(
                                text = surveyPointsText(scout.survey.points.size),
                                fontSize = 12.sp,
                                color = Color.DarkGray
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    if (scout.survey == null) {
                        OutlinedButton(onClick = { onSurveyToggle(true) }) { Text("Start survey") }
                    } else {
                        Button(
                            onClick = { onSurveyToggle(false) },
                            colors = ButtonDefaults.buttonColors(containerColor = RECORDING_COLOR)
                        ) {
                            Text("End survey")
                        }
                    }
                }
            }
        }
    }
}

private fun surveyPointsText(count: Int): String = when (count) {
    0 -> "No points yet — walk a few metres"
    1 -> "1 point saved so far"
    else -> "$count points saved so far"
}

/**
 * Re-enables auto-follow after a manual pan.
 *
 * Only rendered while Scout is active AND follow is off, so its mere presence is the signal that the
 * camera has been detached from the operator's position — there is no state to read, and no button
 * sitting there doing nothing while following is already on.
 */
@Composable
fun RecenterButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        // clickable on the CARD, not the inner Row: the padding is part of the target, which is what
        // makes this hittable with a thumb while walking.
        modifier = modifier
            .padding(12.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(SCOUT_POSITION_COLOR)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Recenter",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.Black
            )
        }
    }
}

/** Amber, matching the app's existing "look at this" hue rather than inventing a fourth colour. */
private val WEAK_SIGNAL_COLOR = Color(0xFFFF6F00)

/** Red for stop, the one place on this screen where a colour needs to mean "ends something". */
private val STOP_SCOUTING_COLOR = Color(0xFFC62828)

/**
 * Recording indicator. The universal record red, distinct from [STOP_SCOUTING_COLOR]'s darker stop
 * red so "something is being captured" never reads as "press this to end tracking".
 */
private val RECORDING_COLOR = Color(0xFFE53935)

/** "Tracking · ±6 m", or the honest version of why the marker isn't moving. */
private fun scoutStatusText(scout: ScoutState): String {
    val accuracy = scout.accuracyMeters
    return when {
        accuracy == null -> "Waiting for a fix…"
        scout.lastFixUsable -> "Tracking · ±${accuracy.toInt()} m"
        // Rejected. Say the number AND what is being done about it, so a stuck marker is explained.
        scout.position == null -> "Signal too weak · ±${accuracy.toInt()} m"
        else -> "Weak signal · ±${accuracy.toInt()} m · holding last position"
    }
}

private fun scoutTrailText(scout: ScoutState): String {
    if (scout.trail.isEmpty()) return "No trail yet · not saved when you stop"
    val points = if (scout.trail.size == 1) "1 point" else "${scout.trail.size} points"
    val walked = formatTrailLength(trailLengthMeters(scout.trail))
    val dropped = if (scout.rejectedFixCount > 0) " · ${scout.rejectedFixCount} noisy fixes dropped" else ""
    return "$points · ~$walked walked$dropped"
}

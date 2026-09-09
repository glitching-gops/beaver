package com.gops.spatialmapper.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gops.spatialmapper.data.RemovedTreeEntity
import com.gops.spatialmapper.removed.formatProximity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val markedAtFormat = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())

/**
 * The fixed centre reticle for targeting mode.
 *
 * The RETICLE NEVER MOVES — the operator pans the map underneath it. That is the whole pattern, and
 * it is chosen over tap-to-place for a phone used outdoors one-handed: a fingertip covers roughly
 * 8 mm of screen, which at the zoom levels this map works at is several metres of ground, so the
 * operator cannot see the point they are placing at the moment they place it. Panning to a fixed
 * crosshair keeps the target visible the entire time and makes the last adjustment a drag rather
 * than a stab.
 *
 * Drawn at the centre of the same Box the map fills, which is exactly where
 * `cameraPositionState.position.target` resolves to — so what the crosshair covers is what gets
 * saved. Nothing here is interactive; it is deliberately not clickable so it can never swallow a pan.
 */
@Composable
fun CenterReticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(72.dp)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val arm = size.minDimension / 2f
        val gap = arm * 0.28f

        // Every stroke is drawn twice: a wide dark pass, then a narrow white one on top. A single
        // colour disappears somewhere on satellite imagery — white is lost on a track or a rooftop,
        // dark is lost in canopy shadow — and this reticle has to stay visible over both.
        listOf(
            Color.Black.copy(alpha = 0.55f) to 7f,
            Color.White to 3f
        ).forEach { (colour, stroke) ->
            // Four arms with a hole in the middle, so the crosshair never hides the tree itself.
            drawLine(colour, Offset(centre.x, centre.y - arm), Offset(centre.x, centre.y - gap), stroke)
            drawLine(colour, Offset(centre.x, centre.y + gap), Offset(centre.x, centre.y + arm), stroke)
            drawLine(colour, Offset(centre.x - arm, centre.y), Offset(centre.x - gap, centre.y), stroke)
            drawLine(colour, Offset(centre.x + gap, centre.y), Offset(centre.x + arm, centre.y), stroke)
            drawCircle(colour, radius = gap * 0.55f, center = centre, style = Stroke(width = stroke))
        }
    }
}

/**
 * Targeting-mode instructions plus Confirm/Cancel.
 *
 * Says "pan the map", not "tap the tree", because the interaction is the opposite of what a map
 * usually invites and one line up front is cheaper than an operator tapping at the screen and
 * wondering why nothing happens.
 */
@Composable
fun MarkTargetingBar(
    saving: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "Mark a removed tree",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Color.Black
            )
            Text(
                text = "Drag the map until the crosshair sits on the tree that is no longer there, " +
                    "then confirm.",
                fontSize = 12.sp,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !saving,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onConfirm,
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = REMOVED_TREE_COLOR),
                    modifier = Modifier.weight(1f)
                ) {
                    if (saving) {
                        // The save waits up to OFFICER_FIX_TIMEOUT_MILLIS for a GPS fix, so this is
                        // briefly visible outdoors and for the full timeout indoors. Showing it in
                        // place of the label keeps the button from looking unresponsive.
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…")
                    } else {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

/**
 * The tapped-marker info surface: when it was marked, and how far the officer was from it.
 *
 * Deliberately small and read-only. There is no edit path in this phase — a mis-tap is fixed by
 * deleting and re-marking, which is two taps and cannot leave a half-edited record behind.
 */
@Composable
fun RemovedTreeInfoCard(
    removedTree: RemovedTreeEntity,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.96f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(REMOVED_TREE_COLOR)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Removed tree",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.Black,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Marked ${markedAtFormat.format(Date(removedTree.markedAtMillis))}",
                fontSize = 13.sp,
                color = Color.DarkGray
            )
            // The proximity number, unlabelled and uninterpreted — see formatProximity.
            Text(
                text = formatProximity(removedTree.proximityMeters),
                fontSize = 13.sp,
                color = Color.DarkGray
            )
            Text(
                text = "%.6f, %.6f".format(
                    Locale.getDefault(),
                    removedTree.latitude,
                    removedTree.longitude
                ),
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = REMOVED_TREE_COLOR),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Delete this mark")
            }
        }
    }
}

/**
 * Delete confirmation, matching the wording and shape of the session-delete dialog in HistoryScreen
 * — same pattern, so the one destructive gesture in the app behaves identically wherever it appears.
 */
@Composable
fun DeleteRemovedTreeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this mark?") },
        text = {
            Text(
                "This permanently removes the record that this tree is gone. This can't be undone — " +
                    "you would have to mark it again."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = REMOVED_TREE_COLOR)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

package com.gops.spatialmapper.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gops.spatialmapper.data.SurveyTrackRepository
import com.gops.spatialmapper.data.SurveyTrackSummary
import com.gops.spatialmapper.map.SAVED_TRACK_COLOR
import com.gops.spatialmapper.survey.formatSurveyDuration
import com.gops.spatialmapper.survey.formatSurveyStart
import kotlinx.coroutines.launch

/**
 * The saved-surveys half of the History tab: one row per recorded walk, tap to overlay it on the
 * map, long-press-free delete via the row's own action.
 *
 * Reads [SurveyTrackSummary] rather than the full entity, so scrolling never deserializes a single
 * coordinate — see the note on [com.gops.spatialmapper.data.SurveyTrackEntity] for why the geometry
 * can live in a column without making this expensive.
 *
 * @param onOpenOnMap hand this survey to the Map tab as an overlay.
 */
@Composable
fun SurveyList(
    onOpenOnMap: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { SurveyTrackRepository.get(context) }
    val scope = rememberCoroutineScope()
    val surveys by repository.observeSummaries().collectAsState(initial = emptyList())

    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }

    if (surveys.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No surveys recorded yet.\n\nTurn on Scout mode on the Map tab, then tap " +
                    "Start survey to record the path you walk.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(items = surveys, key = { it.id }) { survey ->
            SurveyRow(
                survey = survey,
                onClick = { onOpenOnMap(survey.id) },
                onDelete = { confirmDeleteId = survey.id }
            )
        }
    }

    // Same shape and wording as the session and removed-tree delete dialogs — one destructive
    // gesture, behaving identically everywhere it appears.
    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Delete this survey?") },
            text = {
                Text(
                    "This permanently removes the recorded track. This can't be undone — the walk " +
                        "would have to be repeated."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteId = null
                    scope.launch { repository.delete(id) }
                }) {
                    Text("Delete", color = DELETE_COLOR)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text("Cancel") }
            }
        )
    }
}

private val DELETE_COLOR = Color(0xFFC62828)

/**
 * One survey: when it started, how long it ran, how many points it kept.
 *
 * The point count is shown rather than a distance because it is the honest number — it is what the
 * table actually stores, and the row can display it without touching the geometry. Distance would
 * mean deserializing every track in the list to compute something the thinning has already made
 * approximate.
 */
@Composable
private fun SurveyRow(
    survey: SurveyTrackSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(SAVED_TRACK_COLOR)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatSurveyStart(survey.startedAtMillis),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = surveyDetailText(survey),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDelete) {
                Text("Delete", fontSize = 13.sp, color = DELETE_COLOR)
            }
        }
    }
}

/**
 * "12:04 walked · 187 points", or the honest version for a track that never got an end time.
 *
 * A null endedAtMillis cannot happen from this phase's code — surveys are written only when they
 * finish — but the column is nullable for a future incremental writer, and a row that shows nothing
 * at all would be worse than one that says what is missing.
 */
private fun surveyDetailText(survey: SurveyTrackSummary): String {
    val points = if (survey.pointCount == 1) "1 point" else "${survey.pointCount} points"
    if (survey.endedAtMillis == null) return "Incomplete · $points"
    return "${formatSurveyDuration(survey.startedAtMillis, survey.endedAtMillis)} walked · $points"
}

package com.gops.spatialmapper.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.gops.spatialmapper.data.RemovedTreeEntity
import com.gops.spatialmapper.data.RemovedTreeRepository
import com.gops.spatialmapper.data.SessionEntity
import com.gops.spatialmapper.data.SurveyTrackEntity
import com.gops.spatialmapper.data.SurveyTrackRepository
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The snapshot an export run works from.
 *
 * Taken ONCE when the operator starts the run, rather than read live at each step. The history list
 * is backed by a Flow, so a capture finishing while the file picker is open would otherwise land in
 * the CSV but not the GeoJSON — two files that claim to describe the same survey and don't. The
 * timestamp is captured with it so both filenames carry the same stamp and sort together.
 */
private data class ExportRun(
    val sessions: List<SessionEntity>,
    val removedTrees: List<RemovedTreeEntity>,
    val surveyTracks: List<SurveyTrackEntity>,
    val startedAtMillis: Long,
    val geoJsonBytes: Int = 0,
    val sessionCsvBytes: Int = 0
) {
    /** The third file is only offered when there is something to put in it. */
    val hasRemovedTrees: Boolean get() = removedTrees.isNotEmpty()

    /** How many files this run will actually write. Drives the wording of the first dialog. */
    val fileCount: Int get() = if (hasRemovedTrees) 3 else 2
}

/** Which modal, if any, is showing. One state, so two dialogs can never be up at once. */
private sealed interface ExportDialog {
    data object NothingToExport : ExportDialog
    data object Confirm : ExportDialog
    data object ChooseCsvLocation : ExportDialog
    data object ChooseRemovedTreeCsvLocation : ExportDialog
    data class Finished(val title: String, val message: String) : ExportDialog
}

/**
 * "Export all" — writes every saved session to a GeoJSON and a CSV file the operator places via the
 * system file picker.
 *
 * UX SHAPE: a short confirmation, then two save dialogs with an explicit hand-off screen between
 * them. The Storage Access Framework gives no way to title its picker, so the ONLY things telling
 * the operator which file they are placing are the suggested filename and whatever we said just
 * before opening it. A Toast between the two pickers was the alternative and it is the wrong tool —
 * it is dismissable, easy to miss under a picker animation, and the cost of missing it is placing the
 * CSV where the GeoJSON was meant to go. So the hand-off is a dialog that has to be tapped.
 *
 * Everything is driven off [sessions] as passed in; this composable never queries the database
 * itself, so the button always exports exactly the set the list beneath it is showing.
 */
@Composable
fun ExportAllAction(sessions: List<SessionEntity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sessions are passed IN (the History list is right there, and the button must export exactly
    // what it shows). Removed trees are collected HERE instead, because the History tab does not
    // display them — there is no visible list for the export to agree with, so pushing the query up
    // into HistoryScreen would add a parameter it has no other use for.
    val removedTreeRepository = remember { RemovedTreeRepository.get(context) }
    val removedTrees by removedTreeRepository.observeRemovedTrees().collectAsState(initial = emptyList())

    // Survey tracks likewise: the History tab lists them in its other section, but the export runs
    // from the Trees section's top bar, so there is no on-screen list here for it to agree with.
    // Summaries are not enough — the export needs the geometry — so this is the one place that
    // loads full tracks, and it does so once per run rather than per recomposition.
    val surveyRepository = remember { SurveyTrackRepository.get(context) }
    var surveyTracks by remember { mutableStateOf<List<SurveyTrackEntity>>(emptyList()) }
    val surveySummaries by surveyRepository.observeSummaries().collectAsState(initial = emptyList())
    LaunchedEffect(surveySummaries.size) {
        surveyTracks = runCatching { surveyRepository.getAllOnce() }.getOrDefault(emptyList())
    }

    var run by remember { mutableStateOf<ExportRun?>(null) }
    var dialog by remember { mutableStateOf<ExportDialog?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Step 3, when there are removed trees. Declared first because each launcher hands off to the
    // one above it: removed-tree CSV <- sessions CSV <- GeoJSON.
    val removedTreeCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_MIME_TYPE)
    ) { uri ->
        val current = run
        if (current == null) {
            dialog = interruptedDialog()
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            dialog = ExportDialog.Finished(
                title = "Removed-trees CSV skipped",
                message = "The GeoJSON and the sessions CSV were both saved, and the GeoJSON already " +
                    "contains every removed tree. Only the separate removed-trees spreadsheet was " +
                    "not written."
            )
            run = null
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val result = writeTextDocument(context, uri, removedTreesToCsv(current.removedTrees))
            busy = false
            dialog = result.fold(
                onSuccess = { bytes ->
                    ExportDialog.Finished(
                        title = "Export complete",
                        message = "${sessionCountText(current.sessions.size)} and " +
                            "${removedTreeCountText(current.removedTrees.size)} written.\n\n" +
                            "GeoJSON: ${byteText(current.geoJsonBytes)}\n" +
                            "Sessions CSV: ${byteText(current.sessionCsvBytes)}\n" +
                            "Removed trees CSV: ${byteText(bytes)}"
                    )
                },
                onFailure = { error ->
                    ExportDialog.Finished(
                        title = "Removed-trees CSV failed",
                        message = "The GeoJSON and sessions CSV were saved. The removed-trees " +
                            "spreadsheet could not be written.\n\n${errorText(error)}"
                    )
                }
            )
            run = null
        }
    }

    // Declared before the GeoJSON launcher because that one hands off to this one.
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(CSV_MIME_TYPE)
    ) { uri ->
        val current = run
        if (current == null) {
            dialog = interruptedDialog()
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            // The GeoJSON is already on disk, so this is a partial success, not a cancellation of
            // the whole run. Say so plainly rather than implying nothing was written.
            dialog = ExportDialog.Finished(
                title = "CSV skipped",
                message = "The GeoJSON was saved (${byteText(current.geoJsonBytes)}), but no " +
                    "location was chosen for the CSV, so it was not written. Tap Export all again " +
                    "to redo both files."
            )
            run = null
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val result = writeTextDocument(context, uri, sessionsToCsv(current.sessions))
            busy = false
            result.fold(
                onSuccess = { bytes ->
                    if (current.hasRemovedTrees) {
                        // One more file to place. Keep the run alive and hand off to step 3.
                        run = current.copy(sessionCsvBytes = bytes)
                        dialog = ExportDialog.ChooseRemovedTreeCsvLocation
                    } else {
                        dialog = ExportDialog.Finished(
                            title = "Export complete",
                            message = "${sessionCountText(current.sessions.size)} written to both " +
                                "files.\n\nGeoJSON: ${byteText(current.geoJsonBytes)}\n" +
                                "CSV: ${byteText(bytes)}"
                        )
                        run = null
                    }
                },
                onFailure = { error ->
                    dialog = ExportDialog.Finished(
                        title = "CSV export failed",
                        message = "The GeoJSON was saved, but the CSV could not be written.\n\n" +
                            errorText(error)
                    )
                    run = null
                }
            )
        }
    }

    val geoJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GEOJSON_MIME_TYPE)
    ) { uri ->
        val current = run
        if (current == null) {
            dialog = interruptedDialog()
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            // Cancelling the FIRST picker abandons the run outright. Going on to ask for a CSV
            // location after the operator just backed out would read as the app ignoring them.
            run = null
            dialog = null
            return@rememberLauncherForActivityResult
        }
        busy = true
        scope.launch {
            val result = writeTextDocument(
                context,
                uri,
                sessionsToGeoJson(current.sessions, current.removedTrees, current.surveyTracks)
            )
            busy = false
            result.fold(
                onSuccess = { bytes ->
                    run = current.copy(geoJsonBytes = bytes)
                    dialog = ExportDialog.ChooseCsvLocation
                },
                onFailure = { error ->
                    run = null
                    dialog = ExportDialog.Finished(
                        title = "Export failed",
                        message = "The GeoJSON could not be written, so the CSV was not " +
                            "attempted.\n\n${errorText(error)}"
                    )
                }
            )
        }
    }

    TextButton(
        onClick = {
            if (sessions.isEmpty() && removedTrees.isEmpty() && surveyTracks.isEmpty()) {
                dialog = ExportDialog.NothingToExport
            } else {
                run = ExportRun(
                    sessions = sessions.toList(),
                    removedTrees = removedTrees.toList(),
                    surveyTracks = surveyTracks.toList(),
                    startedAtMillis = System.currentTimeMillis()
                )
                dialog = ExportDialog.Confirm
            }
        },
        enabled = !busy,
        modifier = modifier
    ) {
        Text(
            text = if (busy) "Exporting…" else "Export all",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }

    when (val current = dialog) {
        null -> Unit

        // Step 0: nothing to do. A dialog rather than a disabled button, because a disabled control
        // with no explanation reads as a bug — this says why.
        ExportDialog.NothingToExport -> SingleActionDialog(
            title = "Nothing to export",
            message = "Nothing has been recorded yet. Capture a plant and save it, or mark a " +
                "removed tree on the Map tab — then this will write every saved record to a " +
                "GeoJSON and a CSV file.",
            onDismiss = { dialog = null }
        )

        // Step 1: tell the operator up front that there are two dialogs, so the second one isn't a
        // surprise that gets dismissed.
        ExportDialog.Confirm -> {
            val pending = run
            val removedCount = pending?.removedTrees?.size ?: 0
            AlertDialog(
                onDismissRequest = { dialog = null; run = null },
                title = {
                    Text(
                        if (removedCount > 0) {
                            "Export ${sessionCountText(pending?.sessions?.size ?: 0)} and " +
                                removedTreeCountText(removedCount)
                        } else {
                            "Export ${sessionCountText(pending?.sessions?.size ?: 0)}"
                        }
                    )
                },
                text = {
                    Text(
                        buildString {
                            append("You will be asked to choose a location ")
                            append(if (pending?.hasRemovedTrees == true) "three times" else "twice")
                            append(":\n\n")
                            append(
                                "1.  the GeoJSON file — canopy outlines, removed trees and survey " +
                                    "tracks, for GIS\n"
                            )
                            append("2.  the sessions CSV — recorded trees as a spreadsheet table\n")
                            if (pending?.hasRemovedTrees == true) {
                                append("3.  the removed-trees CSV — the marks as their own table\n")
                            }
                            append("\nAll are named with the same timestamp so they stay together.")
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val stamp = pending?.startedAtMillis ?: System.currentTimeMillis()
                        geoJsonLauncher.launch(defaultExportFileName("geojson", stamp))
                    }) { Text("Choose GeoJSON location") }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null; run = null }) { Text("Cancel") }
                }
            )
        }

        // Step 2: the hand-off. Names the file that was just written and the one being asked for.
        ExportDialog.ChooseCsvLocation -> {
            val pending = run
            AlertDialog(
                onDismissRequest = { /* Not dismissable: step 2 of 2 is not optional-by-accident. */ },
                title = { Text("GeoJSON saved") },
                text = {
                    Text(
                        "Saved ${byteText(pending?.geoJsonBytes ?: 0)} of GeoJSON.\n\n" +
                            "Now choose where to save the CSV file — the same records as a " +
                            "spreadsheet table. Its suggested name ends in .csv."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val stamp = pending?.startedAtMillis ?: System.currentTimeMillis()
                        csvLauncher.launch(defaultExportFileName("csv", stamp))
                    }) { Text("Choose CSV location") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog = ExportDialog.Finished(
                            title = "CSV skipped",
                            message = "Only the GeoJSON was written. Tap Export all again to " +
                                "produce both files."
                        )
                        run = null
                    }) { Text("Skip CSV") }
                }
            )
        }

        // Step 3 hand-off, same reasoning as step 2: the picker cannot be titled, so the only way
        // the operator knows which file they are placing is being told immediately beforehand.
        ExportDialog.ChooseRemovedTreeCsvLocation -> {
            val pending = run
            AlertDialog(
                onDismissRequest = { /* Not dismissable — see ChooseCsvLocation. */ },
                title = { Text("Sessions CSV saved") },
                text = {
                    Text(
                        "Saved ${byteText(pending?.sessionCsvBytes ?: 0)}.\n\n" +
                            "One more: choose where to save the removed-trees CSV — the " +
                            "${removedTreeCountText(pending?.removedTrees?.size ?: 0)} you marked, as " +
                            "their own table. Its suggested name ends in _removed_trees.csv.\n\n" +
                            "They are already inside the GeoJSON, so skipping this loses no data."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val stamp = pending?.startedAtMillis ?: System.currentTimeMillis()
                        removedTreeCsvLauncher.launch(defaultRemovedTreeCsvFileName(stamp))
                    }) { Text("Choose CSV location") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog = ExportDialog.Finished(
                            title = "Removed-trees CSV skipped",
                            message = "The GeoJSON and sessions CSV were written. Every removed tree " +
                                "is already in the GeoJSON, so nothing was lost."
                        )
                        run = null
                    }) { Text("Skip") }
                }
            )
        }

        is ExportDialog.Finished -> SingleActionDialog(
            title = current.title,
            message = current.message,
            onDismiss = { dialog = null }
        )
    }
}

@Composable
private fun SingleActionDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

/**
 * The run vanished while the system file picker was in front of us — i.e. Android killed the process
 * to reclaim memory and restored the Activity afterwards.
 *
 * The snapshot is held in a plain `remember`, not `rememberSaveable`, because saving it would mean
 * making [SessionEntity] Parcelable — a change to the data layer to serve a rare recovery path, and
 * against this project's standing decision to keep the pipeline types plain (see the navigation note
 * in MainActivity). So the run is genuinely gone and cannot be resumed. What must NOT happen is
 * failing silently: the operator picked a location and would otherwise be left staring at a file that
 * never appeared.
 */
private fun interruptedDialog(): ExportDialog = ExportDialog.Finished(
    title = "Export interrupted",
    message = "The app was restarted while the file picker was open, so the export was not " +
        "written. Tap Export all to start again."
)

private fun sessionCountText(count: Int): String =
    if (count == 1) "1 session" else "$count sessions"

private fun removedTreeCountText(count: Int): String =
    if (count == 1) "1 removed tree" else "$count removed trees"

/** Human-readable size. KB above a kilobyte, and never "0 KB" for a file that does have content. */
private fun byteText(bytes: Int): String =
    if (bytes < 1024) "$bytes bytes" else "${max(1, bytes / 1024)} KB"

/** A failed write is usually a full volume or a revoked grant; show the cause, not a generic apology. */
private fun errorText(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

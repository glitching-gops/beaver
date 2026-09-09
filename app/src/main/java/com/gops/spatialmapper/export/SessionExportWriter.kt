package com.gops.spatialmapper.export

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "SpatialMapperExport"

/** MIME types handed to CreateDocument. Both keep their suggested extension through SAF's picker. */
const val GEOJSON_MIME_TYPE = "application/geo+json"
const val CSV_MIME_TYPE = "text/csv"

/**
 * Filename stamp: sortable, no separators a file system or a spreadsheet import could object to, and
 * in LOCAL time because the operator naming a file in the field is thinking in local time. The UTC
 * instant of every record is inside the file itself, so nothing is lost by being friendly here.
 */
private val filenameTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)

/** Default name suggested in the save dialog, e.g. `spatialmapper_export_20260904_143012.geojson`. */
fun defaultExportFileName(extension: String, atMillis: Long): String =
    "spatialmapper_export_${filenameTimestampFormat.format(Date(atMillis))}.$extension"

/**
 * Name for the removed-trees spreadsheet. Same stamp as the other two files of the run, with a
 * `_removed_trees` suffix so the three sort together in a folder and the odd one out is obvious at a
 * glance — which is also the only cue the operator gets about which file the picker is asking for.
 */
fun defaultRemovedTreeCsvFileName(atMillis: Long): String =
    "spatialmapper_export_${filenameTimestampFormat.format(Date(atMillis))}_removed_trees.csv"

/**
 * Writes [content] as UTF-8 to a Storage Access Framework [uri] the operator picked.
 *
 * Mode "wt" — write + TRUNCATE. Plain "w" is not guaranteed to shorten an existing file, so
 * overwriting a previous, longer export would leave its tail dangling past the new content and
 * produce a file that is neither valid JSON nor valid CSV. CreateDocument usually hands back a fresh
 * document, but "usually" is not a guarantee: pick an existing name in the picker and it is the same
 * document again.
 *
 * Returns the number of bytes written, or a failure carrying whatever went wrong — a full volume, a
 * revoked permission, a provider that hands back a null stream. Callers surface it; nothing here
 * swallows an error, because a silently-failed export is worse than no export.
 */
suspend fun writeTextDocument(context: Context, uri: Uri, content: String): Result<Int> =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: error("Content provider returned no output stream for $uri")
            stream.use { it.write(bytes) }
            Log.i(TAG, "Wrote ${bytes.size} bytes to $uri")
            bytes.size
        }.onFailure { Log.e(TAG, "Failed writing export to $uri", it) }
    }

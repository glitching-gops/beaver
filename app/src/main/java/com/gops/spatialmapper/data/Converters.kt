package com.gops.spatialmapper.data

import androidx.room.TypeConverter
import com.google.android.gms.maps.model.LatLng

/**
 * Room can't persist a `List<LatLng>` directly, so the corrected footprint is stored as a single
 * TEXT column using this format:
 *
 *     "lat,lng;lat,lng;lat,lng;..."
 *
 * i.e. vertices separated by ';', each vertex a "latitude,longitude" pair. Doubles are written with
 * [Double.toString] (full round-trippable precision — no lossy fixed formatting). An empty list
 * serializes to "" and deserializes back to an empty list.
 *
 * Deserialization is defensive: null/blank input, empty segments, wrong-arity segments, and
 * unparseable numbers are skipped rather than throwing, so a partially-corrupt row degrades to
 * whatever valid vertices it still has instead of crashing the history screen.
 */
class Converters {

    // ---------------------------------------------------------------------------------------
    // Canopy footprints: List<LatLng> <-> "lat,lng;lat,lng;..."
    // ---------------------------------------------------------------------------------------


    @TypeConverter
    fun latLngListToString(vertices: List<LatLng>?): String =
        vertices.orEmpty().joinToString(separator = ";") { "${it.latitude},${it.longitude}" }

    @TypeConverter
    fun stringToLatLngList(data: String?): List<LatLng> {
        if (data.isNullOrBlank()) return emptyList()
        return data.split(";").mapNotNull { segment ->
            if (segment.isBlank()) return@mapNotNull null
            val parts = segment.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].trim().toDoubleOrNull()
            val lng = parts[1].trim().toDoubleOrNull()
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Survey tracks: List<SurveyPoint> <-> "lat,lng,millis;lat,lng,millis;..."
    // ---------------------------------------------------------------------------------------

    /**
     * The same scheme as the footprint converter above, widened by one field for the per-point
     * timestamp. Deliberately the same shape rather than JSON: a reader who has already worked out
     * what `polygonVertices` looks like can read this column too, and the app gains no second
     * serialization format to keep working.
     *
     * A separate pair of @TypeConverter methods rather than a shared generic one because Room
     * dispatches on the declared type — `List<LatLng>` and `List<SurveyPoint>` are different types
     * and each needs its own registered conversion.
     */
    @TypeConverter
    fun surveyPointListToString(points: List<SurveyPoint>?): String =
        points.orEmpty().joinToString(separator = ";") {
            "${it.position.latitude},${it.position.longitude},${it.timestampMillis}"
        }

    /**
     * Defensive in exactly the way [stringToLatLngList] is: a malformed segment is skipped rather
     * than thrown on, so a partially-corrupt row degrades to the points it can still read instead of
     * crashing the survey list.
     *
     * A segment with only two fields (an old-style "lat,lng" pair) is accepted with a zero timestamp
     * rather than discarded. Nothing writes that shape today, but silently dropping geometry is the
     * worst possible response to an unexpected format, and a zero timestamp is visibly wrong in a way
     * a missing point is not.
     */
    @TypeConverter
    fun stringToSurveyPointList(data: String?): List<SurveyPoint> {
        if (data.isNullOrBlank()) return emptyList()
        return data.split(";").mapNotNull { segment ->
            if (segment.isBlank()) return@mapNotNull null
            val parts = segment.split(",")
            if (parts.size < 2) return@mapNotNull null
            val lat = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lng = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            val millis = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
            SurveyPoint(LatLng(lat, lng), millis)
        }
    }
}

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
}

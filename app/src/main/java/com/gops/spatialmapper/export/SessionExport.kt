package com.gops.spatialmapper.export

import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.data.RemovedTreeEntity
import com.gops.spatialmapper.data.SessionEntity
import com.gops.spatialmapper.data.SurveyTrackEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Serializes saved sessions to the two interchange formats the app exports: GeoJSON (geometry + full
 * attributes) and CSV (flat attributes, for a spreadsheet).
 *
 * Everything here is a pure `List<SessionEntity> -> String`, with no Android or filesystem
 * dependency, so the awkward parts — coordinate order, ring closure, CSV quoting — are unit-testable
 * off-device. Writing the strings to a user-picked location is [SessionExportWriter]'s job.
 */

/** One exported attribute. Defined once so the GeoJSON properties and the CSV columns cannot drift. */
private class ExportField(val name: String, val value: (SessionEntity) -> Any?)

/**
 * The audit record, in the order it is written to both formats: identity, then what the human
 * confirmed, then what was measured, then the raw sensor readings behind it.
 *
 * Values are Double/Long/Int/String or null — deliberately typed rather than pre-formatted, so
 * GeoJSON gets real JSON numbers (not quoted strings) and the CSV gets locale-independent toString
 * output. A "%.2f" here would emit "12,34" on a device set to a comma-decimal locale and quietly
 * corrupt every numeric column in the CSV.
 *
 * Excluded on purpose: anything binary (there is none — the photo is referenced by [photo_path], not
 * embedded), and derived-but-redundant values such as area in hectares. A consumer can divide by
 * 10,000; two columns that can disagree are a liability in an audit record.
 */
private val EXPORT_FIELDS: List<ExportField> = listOf(
    // Present on every record of both kinds, so record type is never inferred from column presence.
    ExportField("record_type") { RECORD_TYPE_SESSION },
    ExportField("session_id") { it.id },
    ExportField("label") { it.label.ifBlank { null } },
    ExportField("timestamp_millis") { it.timestampMillis },
    ExportField("timestamp_utc") { Instant.ofEpochMilli(it.timestampMillis).toString() },

    // --- Human-confirmed / derived ---
    ExportField("area_square_meters") { it.areaSquareMeters },
    ExportField("polygon_vertex_count") { it.polygonVertices.size },

    // --- Species (the current answer; the per-attempt trail is not part of this export) ---
    ExportField("species_scientific_name") { it.speciesScientificName },
    ExportField("species_common_name") { it.speciesCommonName },
    ExportField("species_genus") { it.speciesGenus },
    ExportField("species_confidence") { it.speciesConfidence },
    ExportField("species_source") { it.speciesSource },

    // --- Canopy measurement ---
    ExportField("canopy_diameter_mean_m") { it.canopyDiameterMeters },
    ExportField("canopy_diameter_horizontal_m") { it.canopyDiameterHorizontalMeters },
    ExportField("canopy_diameter_vertical_m") { it.canopyDiameterVerticalMeters },

    // --- Raw telemetry ---
    ExportField("origin_latitude") { it.latitude },
    ExportField("origin_longitude") { it.longitude },
    ExportField("gps_accuracy_meters") { it.gpsAccuracyMeters },
    ExportField("heading_degrees") { it.headingDegrees },
    ExportField("heading_reference") { if (it.headingIsTrueNorth) "true_north" else "magnetic" },
    ExportField("pitch_degrees") { it.pitchDegrees },
    ExportField("compass_reliability") { it.compassReliability },
    ExportField("distance_meters") { it.distanceMeters },
    ExportField("distance_method") { it.distanceMethod },
    ExportField("projected_latitude") { it.projectedLatitude },
    ExportField("projected_longitude") { it.projectedLongitude },

    // --- Camera model ---
    ExportField("focal_length_pixels") { it.focalLengthPixels },
    ExportField("image_width_pixels") { it.imageWidthPixels },
    ExportField("image_height_pixels") { it.imageHeightPixels },

    // --- Provenance ---
    ExportField("photo_path") { it.photoPath }
)

/**
 * CSV-only trailing column carrying the footprint in the app's own storage format
 * ("lat,lng;lat,lng;...").
 *
 * DECISION: the CSV duplicates the geometry rather than omitting it. GeoJSON is the
 * geometry-carrying format and a consumer that needs to *map* the data should use it — but the two
 * files get separated, and a CSV that silently drops the most expensive thing the operator produced
 * in the field is a trap. One column is cheap insurance. It goes last so it never gets in the way of
 * reading the attribute columns in a spreadsheet, and it stays in lat,lng order because that is
 * verbatim what the database holds; re-ordering it here would create a second, subtly different
 * convention sitting next to the GeoJSON's lon,lat, which is exactly the confusion worth avoiding.
 * The column name says which order it is in.
 */
private const val CSV_GEOMETRY_COLUMN = "polygon_vertices_lat_lng"

/**
 * The removed-tree record: every column of `removed_trees`, plus the record-type discriminator.
 *
 * Note what is NOT here: any interpretation of [RemovedTreeEntity.proximityMeters]. No
 * "field_verified" boolean, no confidence band. The phase's decision is that confidence is inferred
 * from the distance, and emitting a derived flag would bake one particular threshold — chosen here,
 * by us, silently — into every downstream analysis. The metres go out; the judgement stays with
 * whoever is reading.
 */
private val REMOVED_TREE_FIELDS: List<ExportField2> = listOf(
    ExportField2("record_type") { RECORD_TYPE_REMOVED_TREE },
    ExportField2("removed_tree_id") { it.id },
    ExportField2("latitude") { it.latitude },
    ExportField2("longitude") { it.longitude },
    ExportField2("marked_at_millis") { it.markedAtMillis },
    ExportField2("marked_at_utc") { Instant.ofEpochMilli(it.markedAtMillis).toString() },
    ExportField2("officer_latitude") { it.officerLatitude },
    ExportField2("officer_longitude") { it.officerLongitude },
    ExportField2("officer_accuracy_meters") { it.officerAccuracyMeters },
    ExportField2("proximity_meters") { it.proximityMeters }
)

/** Same shape as [ExportField], over the other entity. */
private class ExportField2(val name: String, val value: (RemovedTreeEntity) -> Any?)

private fun propertiesOf(removedTree: RemovedTreeEntity): JSONObject {
    val properties = JSONObject()
    REMOVED_TREE_FIELDS.forEach { field ->
        properties.put(field.name, jsonValueOf(field.value(removedTree)))
    }
    return properties
}

/**
 * The removed trees as their own CSV.
 *
 * DECISION: a SECOND file, not extra rows in the sessions CSV.
 *
 * The sessions CSV is 30 columns describing a photographed, measured, sometimes identified tree.
 * A removed tree shares four concepts with it (an id, a position, a time, and that is nearly all) and
 * has three the sessions file has no column for. Appending them to the same sheet would mean every
 * removed-tree row carrying ~26 empty cells, and every sessions row carrying three — a shape that is
 * awkward to read, awkward to filter, and actively misleading in any tool that infers a column's type
 * from its first few values.
 *
 * The cost of a separate file is one more save dialog, and it is only charged when there is something
 * to put in it: the export flow skips this step entirely when no trees have been marked, so an
 * operator not using the feature sees exactly the two dialogs they saw before.
 *
 * Both files still carry `record_type`, so if someone does concatenate them the rows stay
 * distinguishable. Same RFC 4180 quoting, CRLF, and locale-independent numbers as [sessionsToCsv] —
 * see the note there on why the decimal separator must not follow the device locale.
 */
fun removedTreesToCsv(removedTrees: List<RemovedTreeEntity>): String {
    val builder = StringBuilder()
    builder.append(REMOVED_TREE_FIELDS.joinToString(",") { csvField(it.name) })
        .append(CSV_LINE_TERMINATOR)
    removedTrees.forEach { removedTree ->
        builder.append(
            REMOVED_TREE_FIELDS.joinToString(",") { csvField(csvValueOf(it.value(removedTree))) }
        ).append(CSV_LINE_TERMINATOR)
    }
    return builder.toString()
}

// ---------------------------------------------------------------------------------------------
// GeoJSON
// ---------------------------------------------------------------------------------------------

/**
 * Discriminates the two kinds of Feature in the collection. Every Feature carries it, including
 * session ones, so a consumer never has to infer record type from which properties happen to be
 * present — a rule that would break the moment either schema gains a column.
 */
const val RECORD_TYPE_SESSION = "session"
const val RECORD_TYPE_REMOVED_TREE = "removed_tree"
const val RECORD_TYPE_SURVEY_TRACK = "survey_track"

/**
 * A GeoJSON FeatureCollection (RFC 7946) holding all three record types: one Polygon Feature per
 * captured session, one Point Feature per removed tree, and one LineString Feature per recorded
 * survey track.
 *
 * MIXED GEOMETRY IS VALID: RFC 7946 section 3.3 puts no constraint on the geometry types within a
 * FeatureCollection, and every mainstream consumer (QGIS, PostGIS via ogr2ogr, geojson.io, Leaflet)
 * handles the mix. One file is also the honest representation of what the officer produced — the
 * standing trees and the gaps between them are one survey of one block, and splitting them would
 * make "what did this survey find" a join rather than a file open.
 *
 * COORDINATE ORDER: RFC 7946 section 3.1.1 positions are [longitude, latitude] — x, y — which is the
 * reverse of the lat,lng order used everywhere else in this app and in [LatLng] itself. Getting it
 * backwards produces a file that parses cleanly and plots the whole survey in the wrong hemisphere,
 * so the swap happens in exactly one place ([position]) and is covered by a unit test — for the
 * removed-tree Points as much as for the session Polygons.
 *
 * A session whose outline cannot form a linear ring gets "geometry": null — explicitly allowed by
 * section 3.2, and far better than dropping the row and silently exporting fewer records than the
 * operator has. A removed tree always has a geometry: its coordinates are non-null columns.
 */
fun sessionsToGeoJson(
    sessions: List<SessionEntity>,
    removedTrees: List<RemovedTreeEntity> = emptyList(),
    surveyTracks: List<SurveyTrackEntity> = emptyList()
): String {
    val features = JSONArray()
    sessions.forEach { session ->
        val feature = JSONObject()
        feature.put("type", "Feature")
        // Feature-level id (section 3.2) as well as a session_id property: some consumers surface
        // one, some the other, and neither survives every conversion tool.
        //
        // PREFIXED, because ids must be unique across the collection and the two tables both
        // autoincrement from 1 — raw ids would collide on the very first record of each.
        feature.put("id", "session-${session.id}")
        feature.put("geometry", polygonGeometry(session.polygonVertices) ?: JSONObject.NULL)
        feature.put("properties", propertiesOf(session))
        features.put(feature)
    }
    removedTrees.forEach { removedTree ->
        val feature = JSONObject()
        feature.put("type", "Feature")
        feature.put("id", "removed-${removedTree.id}")
        feature.put("geometry", pointGeometry(removedTree.latitude, removedTree.longitude))
        feature.put("properties", propertiesOf(removedTree))
        features.put(feature)
    }
    surveyTracks.forEach { track ->
        val feature = JSONObject()
        feature.put("type", "Feature")
        feature.put("id", "survey-${track.id}")
        feature.put("geometry", lineStringGeometry(track) ?: JSONObject.NULL)
        feature.put("properties", propertiesOf(track))
        features.put(feature)
    }

    return JSONObject().apply {
        put("type", "FeatureCollection")
        // Foreign members (section 6.1) — ignored by strict readers, useful to whoever opens the raw
        // file and needs to know what produced it and whether it looks complete.
        put("name", "spatialmapper_survey")
        put("generated_utc", Instant.now().toString())
        put("session_count", sessions.size)
        put("removed_tree_count", removedTrees.size)
        put("survey_track_count", surveyTracks.size)
        put("features", features)
    }.toString(2)
}

/**
 * A LineString for a walked survey track, or null when the track has fewer than two points.
 *
 * RFC 7946 section 3.1.4 requires at least two positions in a LineString, so a one-point track — a
 * survey started and ended on the spot — gets `"geometry": null` rather than an invalid geometry.
 * Same reasoning as a session with a degenerate outline: emit the record with its attributes and be
 * honest about the missing shape, rather than dropping a row the operator can see in the app.
 *
 * NOT a Polygon: a track is an open path that may cross itself and does not enclose anything. Nor is
 * it closed or rewound — the winding rules that apply to the canopy rings are meaningless here, and
 * the point order IS the data, because it is the direction the officer walked.
 */
private fun lineStringGeometry(track: SurveyTrackEntity): JSONObject? {
    if (track.points.size < 2) return null
    val coordinates = JSONArray()
    track.points.forEach { coordinates.put(position(it.position)) }
    return JSONObject().apply {
        put("type", "LineString")
        put("coordinates", coordinates)
    }
}

/**
 * The survey-track record. Timestamps and counts, plus the two derived numbers a reader would
 * otherwise have to compute from the geometry themselves.
 *
 * The per-point timestamps are NOT flattened into properties — they belong to the coordinates and
 * there is nowhere in a GeoJSON Feature they would sensibly go. A consumer that needs them can read
 * this app's CSV-style geometry column, or the track's own points via the app; GeoJSON carries the
 * shape and the summary, which is what a GIS opens it for.
 */
private val SURVEY_TRACK_FIELDS: List<ExportField3> = listOf(
    ExportField3("record_type") { RECORD_TYPE_SURVEY_TRACK },
    ExportField3("survey_track_id") { it.id },
    ExportField3("label") { it.label },
    ExportField3("started_at_millis") { it.startedAtMillis },
    ExportField3("started_at_utc") { Instant.ofEpochMilli(it.startedAtMillis).toString() },
    ExportField3("ended_at_millis") { it.endedAtMillis },
    ExportField3("ended_at_utc") { it.endedAtMillis?.let { end -> Instant.ofEpochMilli(end).toString() } },
    ExportField3("duration_seconds") {
        it.endedAtMillis?.let { end -> (end - it.startedAtMillis) / 1000 }
    },
    ExportField3("point_count") { it.pointCount }
)

/** Same shape as [ExportField], over the survey-track entity. */
private class ExportField3(val name: String, val value: (SurveyTrackEntity) -> Any?)

private fun propertiesOf(track: SurveyTrackEntity): JSONObject {
    val properties = JSONObject()
    SURVEY_TRACK_FIELDS.forEach { field ->
        properties.put(field.name, jsonValueOf(field.value(track)))
    }
    return properties
}

/** A Point geometry. Same [longitude, latitude] rule as every other position in this file. */
private fun pointGeometry(latitude: Double, longitude: Double): JSONObject =
    JSONObject().apply {
        put("type", "Point")
        put("coordinates", JSONArray().put(longitude).put(latitude))
    }

/** A Polygon geometry with a single, closed, right-hand-rule exterior ring; null if not formable. */
private fun polygonGeometry(vertices: List<LatLng>): JSONObject? {
    val ring = closedRing(vertices) ?: return null
    val coordinates = JSONArray().put(JSONArray().apply { ring.forEach { put(position(it)) } })
    return JSONObject().apply {
        put("type", "Polygon")
        put("coordinates", coordinates)
    }
}

/**
 * Prepares [vertices] as a GeoJSON linear ring: oriented counterclockwise and explicitly closed.
 *
 * The stored outline is an OPEN path wound clockwise (generateCanopyPolygon walks bearings 0, 15,
 * 30... which is N to E to S to W). RFC 7946 section 3.1.6 says an exterior ring SHOULD follow the
 * right-hand rule, i.e. counterclockwise, so it is reversed when needed; section 3.1.1 requires the
 * first and last positions to be identical, so the first is appended. Both are cheap, and both are
 * things some consumers (PostGIS, several web viewers) actually enforce.
 *
 * Returns null unless the result is a valid ring: four or more positions, i.e. three real vertices.
 */
private fun closedRing(vertices: List<LatLng>): List<LatLng>? {
    if (vertices.size < 3) return null
    val open = if (vertices.first() == vertices.last()) vertices.dropLast(1) else vertices
    if (open.size < 3) return null
    val oriented = if (isCounterClockwise(open)) open else open.reversed()
    return oriented + oriented.first()
}

/**
 * Planar shoelace on (longitude, latitude) — the plane GeoJSON's winding rule is defined in.
 *
 * Deliberately NOT SphericalUtil.computeSignedArea: this is a pure orientation test over a canopy a
 * few tens of meters across, where the planar and spherical answers cannot disagree, and the planar
 * formula's sign convention (positive = counterclockwise in x,y) is the one RFC 7946 is written
 * against. Area itself still comes from the spherical calculation — see
 * [com.gops.spatialmapper.area.polygonAreaSquareMeters].
 */
private fun isCounterClockwise(vertices: List<LatLng>): Boolean {
    var twiceSignedArea = 0.0
    for (i in vertices.indices) {
        val current = vertices[i]
        val next = vertices[(i + 1) % vertices.size]
        twiceSignedArea += current.longitude * next.latitude - next.longitude * current.latitude
    }
    return twiceSignedArea > 0.0
}

/** THE coordinate-order swap: GeoJSON positions are [longitude, latitude]. See [sessionsToGeoJson]. */
private fun position(vertex: LatLng): JSONArray =
    JSONArray().put(vertex.longitude).put(vertex.latitude)

private fun propertiesOf(session: SessionEntity): JSONObject {
    val properties = JSONObject()
    EXPORT_FIELDS.forEach { field ->
        // JSONObject.put(key, null) REMOVES the key. An absent property and a null one are different
        // things to a consumer diffing records, so nulls are written explicitly as JSON null.
        properties.put(field.name, jsonValueOf(field.value(session)))
    }
    return properties
}

/**
 * Normalizes a field value for JSON. Float is widened to Double through its own decimal string
 * rather than by toDouble(): 12.3f.toDouble() is 12.300000190734863, which would turn a
 * 4-significant-digit sensor reading into noise that looks like precision.
 */
private fun jsonValueOf(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Float -> value.toString().toDouble()
    else -> value
}

// ---------------------------------------------------------------------------------------------
// CSV
// ---------------------------------------------------------------------------------------------

/**
 * RFC 4180 CSV: a header row, then one row per session, with columns matching the GeoJSON properties
 * exactly plus [CSV_GEOMETRY_COLUMN].
 *
 * Encoding is plain UTF-8 with NO byte-order mark. A BOM would make Excel open a non-ASCII label
 * correctly on a double-click, but it also leaks a stray U+FEFF into the first column name for
 * pandas, R, and most CLI tooling. Field data heads for a script far more often than for a
 * double-click, so the BOM stays off; an operator who needs Excel should use its text-import step and
 * pick UTF-8.
 *
 * Line terminator is CRLF, as RFC 4180 specifies.
 */
fun sessionsToCsv(sessions: List<SessionEntity>): String {
    val builder = StringBuilder()
    val headers = EXPORT_FIELDS.map { it.name } + CSV_GEOMETRY_COLUMN
    builder.append(headers.joinToString(",") { csvField(it) }).append(CSV_LINE_TERMINATOR)

    sessions.forEach { session ->
        val cells = EXPORT_FIELDS.map { csvValueOf(it.value(session)) } +
            session.polygonVertices.joinToString(";") { "${it.latitude},${it.longitude}" }
        builder.append(cells.joinToString(",") { csvField(it) }).append(CSV_LINE_TERMINATOR)
    }
    return builder.toString()
}

private const val CSV_LINE_TERMINATOR = "\r\n"

/**
 * Renders a value as CSV text. Null becomes an EMPTY field, not the string "null" — a spreadsheet
 * reads the former as a blank cell and the latter as data.
 *
 * Numbers go through toString, which is locale-independent, so the decimal separator is always '.'
 * regardless of device locale. That is not a nicety: a comma decimal separator inside a
 * comma-delimited file is a data-corruption bug that only shows up on someone else's phone.
 */
private fun csvValueOf(value: Any?): String = value?.toString() ?: ""

/**
 * Quotes a field per RFC 4180 sections 2.6-2.7: wrap in double quotes if it contains a comma, a
 * double quote, CR or LF, and double any embedded quote. The geometry column and free-text labels are
 * what actually need this — a label like `Neem, by the gate` would otherwise become two columns.
 */
private fun csvField(raw: String): String {
    val needsQuoting = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuoting) return raw
    return "\"" + raw.replace("\"", "\"\"") + "\""
}

package com.gops.spatialmapper.export

import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.data.RemovedTreeEntity
import com.gops.spatialmapper.data.SessionEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removed trees in the export: Point features alongside the session polygons, and their own CSV.
 *
 * Same fixture convention as [SessionExportTest] — latitude ~12, longitude ~77, far enough apart
 * that a swapped pair cannot accidentally look plausible. The coordinate-order test is repeated for
 * Points rather than assumed from the Polygon one: they go through a different code path
 * ([pointGeometry] vs [position]), so passing there proves nothing here.
 */
class RemovedTreeExportTest {

    private fun removedTree(
        id: Long = 1L,
        officerLatitude: Double? = 12.9717,
        officerLongitude: Double? = 77.5947,
        officerAccuracy: Float? = 4.5f,
        proximity: Double? = 15.3
    ) = RemovedTreeEntity(
        id = id,
        latitude = 12.9716,
        longitude = 77.5946,
        officerLatitude = officerLatitude,
        officerLongitude = officerLongitude,
        officerAccuracyMeters = officerAccuracy,
        proximityMeters = proximity,
        markedAtMillis = 1_700_000_000_000L
    )

    private fun session(id: Long = 1L) = SessionEntity(
        id = id,
        timestampMillis = 1_700_000_000_000L,
        photoPath = "/data/photo.jpg",
        latitude = 12.97,
        longitude = 77.59,
        gpsAccuracyMeters = 4.5f,
        headingDegrees = 90f,
        headingIsTrueNorth = true,
        pitchDegrees = 0f,
        compassReliability = "HIGH",
        distanceMeters = 12f,
        distanceMethod = "ARCORE_DEPTH",
        projectedLatitude = 12.9717,
        projectedLongitude = 77.5947,
        focalLengthPixels = 1400f,
        imageWidthPixels = 1080,
        imageHeightPixels = 1440,
        canopyDiameterMeters = 6.0,
        canopyDiameterHorizontalMeters = 6.0,
        canopyDiameterVerticalMeters = 6.0,
        label = "Standing neem",
        polygonVertices = listOf(
            LatLng(12.9716, 77.5946),
            LatLng(12.9716, 77.5948),
            LatLng(12.9718, 77.5948),
            LatLng(12.9718, 77.5946)
        ),
        areaSquareMeters = 484.2
    )

    private fun featuresOf(json: String) = JSONObject(json).getJSONArray("features")

    // --- GeoJSON ------------------------------------------------------------------------------

    @Test
    fun `removed tree point coordinates are longitude first then latitude`() {
        val feature = featuresOf(sessionsToGeoJson(emptyList(), listOf(removedTree())))
            .getJSONObject(0)
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")

        assertEquals("Point", feature.getJSONObject("geometry").getString("type"))
        assertEquals("x must be longitude", 77.5946, coordinates.getDouble(0), 1e-9)
        assertEquals("y must be latitude", 12.9716, coordinates.getDouble(1), 1e-9)
        assertNotEquals("lat/lon must not be swapped", 12.9716, coordinates.getDouble(0), 0.01)
    }

    @Test
    fun `sessions and removed trees share one mixed-geometry FeatureCollection`() {
        val root = JSONObject(sessionsToGeoJson(listOf(session()), listOf(removedTree())))
        assertEquals("FeatureCollection", root.getString("type"))
        assertEquals(1, root.getInt("session_count"))
        assertEquals(1, root.getInt("removed_tree_count"))

        val features = root.getJSONArray("features")
        assertEquals(2, features.length())
        // Sessions first, then removed trees — Polygon and Point in one collection, which RFC 7946
        // section 3.3 permits.
        assertEquals("Polygon", features.getJSONObject(0).getJSONObject("geometry").getString("type"))
        assertEquals("Point", features.getJSONObject(1).getJSONObject("geometry").getString("type"))
    }

    @Test
    fun `feature ids are prefixed so the two tables cannot collide`() {
        // Both tables autoincrement from 1, so raw ids would clash on the very first record of each.
        val features = featuresOf(sessionsToGeoJson(listOf(session(id = 1L)), listOf(removedTree(id = 1L))))
        val ids = (0 until features.length()).map { features.getJSONObject(it).getString("id") }
        assertEquals(listOf("session-1", "removed-1"), ids)
        assertEquals("ids must be unique across the collection", ids.size, ids.toSet().size)
    }

    @Test
    fun `every feature carries a record_type discriminator`() {
        val features = featuresOf(sessionsToGeoJson(listOf(session()), listOf(removedTree())))
        assertEquals(
            RECORD_TYPE_SESSION,
            features.getJSONObject(0).getJSONObject("properties").getString("record_type")
        )
        assertEquals(
            RECORD_TYPE_REMOVED_TREE,
            features.getJSONObject(1).getJSONObject("properties").getString("record_type")
        )
    }

    @Test
    fun `removed tree properties cover every column`() {
        val properties = featuresOf(sessionsToGeoJson(emptyList(), listOf(removedTree())))
            .getJSONObject(0).getJSONObject("properties")

        assertEquals(1L, properties.getLong("removed_tree_id"))
        assertEquals(12.9716, properties.getDouble("latitude"), 1e-9)
        assertEquals(77.5946, properties.getDouble("longitude"), 1e-9)
        assertEquals(1_700_000_000_000L, properties.getLong("marked_at_millis"))
        assertEquals("2023-11-14T22:13:20Z", properties.getString("marked_at_utc"))
        assertEquals(12.9717, properties.getDouble("officer_latitude"), 1e-9)
        assertEquals(77.5947, properties.getDouble("officer_longitude"), 1e-9)
        assertEquals(4.5, properties.getDouble("officer_accuracy_meters"), 1e-9)
        assertEquals(15.3, properties.getDouble("proximity_meters"), 1e-9)
    }

    @Test
    fun `a desk mark exports explicit nulls rather than dropped keys`() {
        val bare = removedTree(
            officerLatitude = null,
            officerLongitude = null,
            officerAccuracy = null,
            proximity = null
        )
        val properties = featuresOf(sessionsToGeoJson(emptyList(), listOf(bare)))
            .getJSONObject(0).getJSONObject("properties")

        listOf(
            "officer_latitude", "officer_longitude", "officer_accuracy_meters", "proximity_meters"
        ).forEach { key ->
            assertTrue("$key must be present", properties.has(key))
            assertTrue("$key must be null", properties.isNull(key))
        }
        // The tree itself is still fully located — a desk mark is a complete record, not a partial.
        assertEquals(12.9716, properties.getDouble("latitude"), 1e-9)
    }

    @Test
    fun `exporting with no removed trees is unchanged from before the feature`() {
        val root = JSONObject(sessionsToGeoJson(listOf(session())))
        assertEquals(0, root.getInt("removed_tree_count"))
        assertEquals(1, root.getJSONArray("features").length())
        assertEquals("Polygon", root.getJSONArray("features").getJSONObject(0)
            .getJSONObject("geometry").getString("type"))
    }

    @Test
    fun `no records at all still yields a valid empty FeatureCollection`() {
        val root = JSONObject(sessionsToGeoJson(emptyList(), emptyList()))
        assertEquals("FeatureCollection", root.getString("type"))
        assertEquals(0, root.getJSONArray("features").length())
    }

    // --- CSV ----------------------------------------------------------------------------------

    private fun rows(csv: String) = csv.split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun `removed tree csv has a header plus one row each`() {
        val lines = rows(removedTreesToCsv(listOf(removedTree(id = 1L), removedTree(id = 2L))))
        assertEquals(3, lines.size)
        assertEquals(
            "record_type,removed_tree_id,latitude,longitude,marked_at_millis,marked_at_utc," +
                "officer_latitude,officer_longitude,officer_accuracy_meters,proximity_meters",
            lines[0]
        )
        assertTrue(lines[1].startsWith("removed_tree,1,"))
        assertTrue(lines[2].startsWith("removed_tree,2,"))
    }

    @Test
    fun `removed tree csv columns match the geojson properties exactly`() {
        val properties = featuresOf(sessionsToGeoJson(emptyList(), listOf(removedTree())))
            .getJSONObject(0).getJSONObject("properties")
        val headers = rows(removedTreesToCsv(listOf(removedTree())))[0].split(",")

        assertEquals(properties.length(), headers.size)
        properties.keys().forEach { key ->
            assertTrue("csv is missing column $key", headers.contains(key))
        }
    }

    @Test
    fun `removed tree csv writes dots for decimals and blanks for nulls`() {
        val bare = removedTree(
            officerLatitude = null,
            officerLongitude = null,
            officerAccuracy = null,
            proximity = null
        )
        val lines = rows(removedTreesToCsv(listOf(bare)))
        val headers = lines[0].split(",")
        val cells = lines[1].split(",")

        assertEquals("", cells[headers.indexOf("proximity_meters")])
        assertEquals("", cells[headers.indexOf("officer_latitude")])
        assertEquals("12.9716", cells[headers.indexOf("latitude")])
        assertEquals("77.5946", cells[headers.indexOf("longitude")])
    }

    @Test
    fun `an empty removed tree csv is a header only`() {
        val lines = rows(removedTreesToCsv(emptyList()))
        assertEquals(1, lines.size)
        assertTrue(lines[0].startsWith("record_type,removed_tree_id,"))
    }

    @Test
    fun `the sessions csv gains record_type and is otherwise untouched`() {
        val headers = rows(sessionsToCsv(listOf(session())))[0].split(",")
        assertEquals("record_type", headers.first())
        assertEquals("session_id", headers[1])
        // The geometry column still trails the sessions file.
        assertEquals("polygon_vertices_lat_lng", headers.last())
        // And no removed-tree columns leaked into it.
        assertFalse(headers.contains("proximity_meters"))
        assertFalse(headers.contains("removed_tree_id"))
    }
}

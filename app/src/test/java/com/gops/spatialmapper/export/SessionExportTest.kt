package com.gops.spatialmapper.export

import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.data.SessionEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization of saved sessions to GeoJSON and CSV.
 *
 * The fixture uses coordinates where latitude and longitude are FAR apart and unambiguously
 * distinguishable (lat ~12, lon ~77 — Bengaluru), so a swapped pair can't accidentally look right.
 * That is the whole point of the coordinate-order tests below.
 */
class SessionExportTest {

    private val square = listOf(
        LatLng(12.9716, 77.5946),
        LatLng(12.9716, 77.5948),
        LatLng(12.9718, 77.5948),
        LatLng(12.9718, 77.5946)
    )

    private fun session(
        id: Long = 1L,
        label: String = "Neem by the gate",
        vertices: List<LatLng> = square
    ) = SessionEntity(
        id = id,
        timestampMillis = 1_700_000_000_000L,
        photoPath = "/data/user/0/com.gops.spatialmapper/files/capture_1.jpg",
        latitude = 12.9700,
        longitude = 77.5900,
        gpsAccuracyMeters = 4.5f,
        headingDegrees = 123.4f,
        headingIsTrueNorth = true,
        pitchDegrees = -3.2f,
        compassReliability = "HIGH",
        distanceMeters = 12.5f,
        distanceMethod = "ARCORE_DEPTH",
        projectedLatitude = 12.9717,
        projectedLongitude = 77.5947,
        focalLengthPixels = 1450.0f,
        imageWidthPixels = 1920,
        imageHeightPixels = 1080,
        canopyDiameterMeters = 6.4,
        canopyDiameterHorizontalMeters = 6.8,
        canopyDiameterVerticalMeters = 6.0,
        label = label,
        polygonVertices = vertices,
        speciesScientificName = "Azadirachta indica",
        speciesCommonName = "Neem",
        speciesGenus = "Azadirachta",
        speciesConfidence = 0.87,
        speciesSource = "plantnet",
        areaSquareMeters = 484.2
    )

    private fun firstFeature(json: String): JSONObject =
        JSONObject(json).getJSONArray("features").getJSONObject(0)

    // -----------------------------------------------------------------------------------------
    // The one that matters: GeoJSON is [longitude, latitude].
    // -----------------------------------------------------------------------------------------

    @Test
    fun `geojson positions are longitude first then latitude`() {
        val ring = firstFeature(sessionsToGeoJson(listOf(session())))
            .getJSONObject("geometry")
            .getJSONArray("coordinates")
            .getJSONArray(0)

        for (i in 0 until ring.length()) {
            val position = ring.getJSONArray(i)
            val x = position.getDouble(0)
            val y = position.getDouble(1)
            // Longitude first: ~77 in this fixture. Latitude second: ~12.
            assertEquals("position $i x should be longitude", 77.59, x, 0.01)
            assertEquals("position $i y should be latitude", 12.97, y, 0.01)
            assertNotEquals("lat/lon must not be swapped", 12.97, x, 0.01)
        }
    }

    @Test
    fun `geojson ring is closed and follows the right-hand rule`() {
        val ring = firstFeature(sessionsToGeoJson(listOf(session())))
            .getJSONObject("geometry")
            .getJSONArray("coordinates")
            .getJSONArray(0)

        // Closed: 4 vertices in, 5 positions out, first == last.
        assertEquals(5, ring.length())
        val first = ring.getJSONArray(0)
        val last = ring.getJSONArray(ring.length() - 1)
        assertEquals(first.getDouble(0), last.getDouble(0), 0.0)
        assertEquals(first.getDouble(1), last.getDouble(1), 0.0)

        // Counterclockwise in (lon, lat): positive shoelace over the closed ring.
        var twiceArea = 0.0
        for (i in 0 until ring.length() - 1) {
            val a = ring.getJSONArray(i)
            val b = ring.getJSONArray(i + 1)
            twiceArea += a.getDouble(0) * b.getDouble(1) - b.getDouble(0) * a.getDouble(1)
        }
        assertTrue("exterior ring should be counterclockwise, got $twiceArea", twiceArea > 0.0)
    }

    @Test
    fun `clockwise input is rewound counterclockwise`() {
        val clockwise = sessionsToGeoJson(listOf(session(vertices = square.reversed())))
        val counterClockwise = sessionsToGeoJson(listOf(session(vertices = square)))
        // Same ring either way in, so the emitted geometry must be identical.
        assertEquals(
            firstFeature(counterClockwise).getJSONObject("geometry").toString(),
            firstFeature(clockwise).getJSONObject("geometry").toString()
        )
    }

    // -----------------------------------------------------------------------------------------
    // Structure and properties
    // -----------------------------------------------------------------------------------------

    @Test
    fun `collection is a well formed FeatureCollection over every session`() {
        val root = JSONObject(sessionsToGeoJson(listOf(session(id = 1L), session(id = 2L))))
        assertEquals("FeatureCollection", root.getString("type"))
        assertEquals(2, root.getInt("session_count"))
        val features = root.getJSONArray("features")
        assertEquals(2, features.length())
        assertEquals("Feature", features.getJSONObject(0).getString("type"))
        // Prefixed since removed trees joined the collection: both tables autoincrement from 1, so
        // a bare id would collide across record types. See RemovedTreeExportTest.
        assertEquals("session-1", features.getJSONObject(0).getString("id"))
        assertEquals("session-2", features.getJSONObject(1).getString("id"))
        assertEquals("Polygon", features.getJSONObject(0).getJSONObject("geometry").getString("type"))
    }

    @Test
    fun `properties carry the full audit record`() {
        val properties = firstFeature(sessionsToGeoJson(listOf(session()))).getJSONObject("properties")

        assertEquals(1L, properties.getLong("session_id"))
        assertEquals("Neem by the gate", properties.getString("label"))
        assertEquals(1_700_000_000_000L, properties.getLong("timestamp_millis"))
        assertEquals("2023-11-14T22:13:20Z", properties.getString("timestamp_utc"))
        assertEquals(484.2, properties.getDouble("area_square_meters"), 1e-9)
        assertEquals(4, properties.getInt("polygon_vertex_count"))
        assertEquals("Azadirachta indica", properties.getString("species_scientific_name"))
        assertEquals("Neem", properties.getString("species_common_name"))
        assertEquals("Azadirachta", properties.getString("species_genus"))
        assertEquals(0.87, properties.getDouble("species_confidence"), 1e-9)
        assertEquals("plantnet", properties.getString("species_source"))
        assertEquals(6.4, properties.getDouble("canopy_diameter_mean_m"), 1e-9)
        assertEquals(6.8, properties.getDouble("canopy_diameter_horizontal_m"), 1e-9)
        assertEquals(6.0, properties.getDouble("canopy_diameter_vertical_m"), 1e-9)
        assertEquals(12.97, properties.getDouble("origin_latitude"), 0.01)
        assertEquals(77.59, properties.getDouble("origin_longitude"), 0.01)
        assertEquals(4.5, properties.getDouble("gps_accuracy_meters"), 1e-9)
        assertEquals(123.4, properties.getDouble("heading_degrees"), 1e-9)
        assertEquals("true_north", properties.getString("heading_reference"))
        assertEquals(-3.2, properties.getDouble("pitch_degrees"), 1e-9)
        assertEquals("HIGH", properties.getString("compass_reliability"))
        assertEquals(12.5, properties.getDouble("distance_meters"), 1e-9)
        assertEquals("ARCORE_DEPTH", properties.getString("distance_method"))
        assertEquals(12.9717, properties.getDouble("projected_latitude"), 1e-9)
        assertEquals(77.5947, properties.getDouble("projected_longitude"), 1e-9)
        assertEquals(1450.0, properties.getDouble("focal_length_pixels"), 1e-9)
        assertEquals(1920, properties.getInt("image_width_pixels"))
        assertEquals(1080, properties.getInt("image_height_pixels"))
        assertTrue(properties.getString("photo_path").endsWith("capture_1.jpg"))
    }

    @Test
    fun `float readings are not widened into float noise`() {
        val properties = firstFeature(sessionsToGeoJson(listOf(session()))).getJSONObject("properties")
        // 123.4f.toDouble() == 123.4000015258789 — the widening must go through the decimal string.
        assertEquals("123.4", properties.get("heading_degrees").toString())
        assertEquals("4.5", properties.get("gps_accuracy_meters").toString())
    }

    @Test
    fun `null fields are written as JSON null rather than dropped`() {
        val bare = session().copy(
            label = "",
            areaSquareMeters = null,
            speciesScientificName = null,
            speciesConfidence = null,
            distanceMeters = null,
            distanceMethod = null
        )
        val properties = firstFeature(sessionsToGeoJson(listOf(bare))).getJSONObject("properties")
        listOf(
            "label", "area_square_meters", "species_scientific_name", "species_confidence",
            "distance_meters", "distance_method"
        ).forEach { key ->
            assertTrue("$key should be present", properties.has(key))
            assertTrue("$key should be null", properties.isNull(key))
        }
    }

    @Test
    fun `a footprint too small to be a ring gets a null geometry but keeps its properties`() {
        val degenerate = session(vertices = listOf(LatLng(12.97, 77.59), LatLng(12.98, 77.59)))
        val feature = firstFeature(sessionsToGeoJson(listOf(degenerate)))
        assertTrue(feature.isNull("geometry"))
        assertEquals(2, feature.getJSONObject("properties").getInt("polygon_vertex_count"))
    }

    @Test
    fun `no sessions still yields a valid empty FeatureCollection`() {
        val root = JSONObject(sessionsToGeoJson(emptyList()))
        assertEquals("FeatureCollection", root.getString("type"))
        assertEquals(0, root.getJSONArray("features").length())
    }

    // -----------------------------------------------------------------------------------------
    // CSV
    // -----------------------------------------------------------------------------------------

    private fun csvRows(csv: String) = csv.split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun `csv has a header plus one row per session and matches the geojson properties`() {
        val rows = csvRows(sessionsToCsv(listOf(session(id = 1L), session(id = 2L))))
        assertEquals(3, rows.size)

        val headers = rows[0].split(",")
        val properties = firstFeature(sessionsToGeoJson(listOf(session()))).getJSONObject("properties")
        // Every GeoJSON property is a CSV column; the only extra is the geometry column.
        properties.keys().forEach { key ->
            assertTrue("csv is missing column $key", headers.contains(key))
        }
        assertEquals(properties.length() + 1, headers.size)
        assertEquals("polygon_vertices_lat_lng", headers.last())
    }

    @Test
    fun `csv quotes fields containing delimiters and doubles embedded quotes`() {
        val rows = csvRows(sessionsToCsv(listOf(session(label = "Neem, the \"big\" one"))))
        assertTrue(
            "label should be quoted with doubled inner quotes, got: ${rows[1]}",
            rows[1].contains("\"Neem, the \"\"big\"\" one\"")
        )
        // The geometry column contains commas, so it must be quoted too.
        assertTrue(rows[1].trimEnd().endsWith("\""))
    }

    @Test
    fun `csv writes numbers with a dot separator and blanks for nulls`() {
        val rows = csvRows(sessionsToCsv(listOf(session().copy(areaSquareMeters = null))))
        val headers = rows[0].split(",")
        val cells = rows[1].split(",")
        assertEquals("", cells[headers.indexOf("area_square_meters")])
        assertEquals("6.4", cells[headers.indexOf("canopy_diameter_mean_m")])
        assertEquals("12.9717", cells[headers.indexOf("projected_latitude")])
    }

    @Test
    fun `csv geometry column keeps the stored lat lng order`() {
        val csv = sessionsToCsv(listOf(session()))
        assertTrue(
            "geometry column should hold lat,lng pairs verbatim",
            csv.contains("12.9716,77.5946;12.9716,77.5948;12.9718,77.5948;12.9718,77.5946")
        )
    }

    @Test
    fun `no sessions yields a header only csv`() {
        val rows = csvRows(sessionsToCsv(emptyList()))
        assertEquals(1, rows.size)
        // record_type leads every row now that the export carries two kinds of record.
        assertTrue(rows[0].startsWith("record_type,session_id,label,"))
    }
}

package com.gops.spatialmapper.export

import com.google.android.gms.maps.model.LatLng
import com.gops.spatialmapper.data.SurveyPoint
import com.gops.spatialmapper.data.SurveyTrackEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Survey tracks in the GeoJSON export: LineString features sharing the collection with the session
 * Polygons and removed-tree Points.
 *
 * Same fixture convention as the other export tests — latitude ~12, longitude ~77, far enough apart
 * that a swapped pair cannot accidentally look plausible. The coordinate-order check is repeated for
 * LineStrings rather than assumed from the Polygon and Point ones: it is a third code path, and the
 * whole reason those tests exist is that this mistake parses cleanly and plots in the wrong ocean.
 */
class SurveyTrackExportTest {

    private fun track(
        id: Long = 1L,
        points: List<SurveyPoint> = listOf(
            SurveyPoint(LatLng(12.9716, 77.5946), 1_700_000_000_000L),
            SurveyPoint(LatLng(12.9718, 77.5948), 1_700_000_003_000L),
            SurveyPoint(LatLng(12.9720, 77.5950), 1_700_000_006_000L)
        ),
        endedAtMillis: Long? = 1_700_000_600_000L
    ) = SurveyTrackEntity(
        id = id,
        label = "Survey 14 Nov 2023 · 22:13",
        startedAtMillis = 1_700_000_000_000L,
        endedAtMillis = endedAtMillis,
        pointCount = points.size,
        points = points
    )

    private fun featuresOf(json: String) = JSONObject(json).getJSONArray("features")

    private fun firstTrackFeature(json: String): JSONObject {
        val features = featuresOf(json)
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            if (feature.getJSONObject("properties").getString("record_type") ==
                RECORD_TYPE_SURVEY_TRACK
            ) {
                return feature
            }
        }
        error("no survey_track feature in the collection")
    }

    // --- Geometry -------------------------------------------------------------------------------

    @Test
    fun `linestring positions are longitude first then latitude`() {
        val geometry = firstTrackFeature(
            sessionsToGeoJson(emptyList(), emptyList(), listOf(track()))
        ).getJSONObject("geometry")
        val coordinates = geometry.getJSONArray("coordinates")

        assertEquals("LineString", geometry.getString("type"))
        assertEquals(3, coordinates.length())
        for (i in 0 until coordinates.length()) {
            val position = coordinates.getJSONArray(i)
            assertEquals("position $i x should be longitude", 77.59, position.getDouble(0), 0.01)
            assertEquals("position $i y should be latitude", 12.97, position.getDouble(1), 0.01)
            assertNotEquals("lat/lon must not be swapped", 12.97, position.getDouble(0), 0.01)
        }
    }

    @Test
    fun `a track keeps its walked order and is not closed`() {
        val coordinates = firstTrackFeature(
            sessionsToGeoJson(emptyList(), emptyList(), listOf(track()))
        ).getJSONObject("geometry").getJSONArray("coordinates")

        // Point order IS the data — it is the direction the officer walked — so unlike a canopy ring
        // it must be neither rewound nor closed.
        assertEquals(77.5946, coordinates.getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(77.5950, coordinates.getJSONArray(2).getDouble(0), 1e-9)
        assertNotEquals(
            "a track must not be closed like a polygon ring",
            coordinates.getJSONArray(0).getDouble(0),
            coordinates.getJSONArray(coordinates.length() - 1).getDouble(0),
            1e-12
        )
    }

    @Test
    fun `a one point track gets a null geometry rather than an invalid LineString`() {
        // RFC 7946 section 3.1.4 requires two positions. A survey started and ended on the spot is a
        // real record, so it is exported with its attributes and an explicit null geometry.
        val stationary = track(points = listOf(SurveyPoint(LatLng(12.9716, 77.5946), 1L)))
        val feature = firstTrackFeature(sessionsToGeoJson(emptyList(), emptyList(), listOf(stationary)))

        assertTrue(feature.isNull("geometry"))
        assertEquals(1, feature.getJSONObject("properties").getInt("point_count"))
    }

    @Test
    fun `an empty track still exports as a record`() {
        val empty = track(points = emptyList())
        val feature = firstTrackFeature(sessionsToGeoJson(emptyList(), emptyList(), listOf(empty)))
        assertTrue(feature.isNull("geometry"))
        assertEquals(0, feature.getJSONObject("properties").getInt("point_count"))
    }

    // --- Collection membership ------------------------------------------------------------------

    @Test
    fun `all three record types share one mixed-geometry FeatureCollection`() {
        val root = JSONObject(
            sessionsToGeoJson(emptyList(), emptyList(), listOf(track(id = 1L), track(id = 2L)))
        )
        assertEquals("FeatureCollection", root.getString("type"))
        assertEquals(0, root.getInt("session_count"))
        assertEquals(0, root.getInt("removed_tree_count"))
        assertEquals(2, root.getInt("survey_track_count"))
        assertEquals(2, root.getJSONArray("features").length())
    }

    @Test
    fun `feature ids are prefixed so the three tables cannot collide`() {
        // sessions, removed_trees and survey_tracks all autoincrement from 1.
        val features = featuresOf(sessionsToGeoJson(emptyList(), emptyList(), listOf(track(id = 1L))))
        assertEquals("survey-1", features.getJSONObject(0).getString("id"))
    }

    @Test
    fun `exporting with no tracks is unchanged from before the feature`() {
        val root = JSONObject(sessionsToGeoJson(emptyList(), emptyList()))
        assertEquals(0, root.getInt("survey_track_count"))
        assertEquals(0, root.getJSONArray("features").length())
    }

    // --- Properties -----------------------------------------------------------------------------

    @Test
    fun `track properties cover the row plus the derived duration`() {
        val properties = firstTrackFeature(
            sessionsToGeoJson(emptyList(), emptyList(), listOf(track()))
        ).getJSONObject("properties")

        assertEquals(RECORD_TYPE_SURVEY_TRACK, properties.getString("record_type"))
        assertEquals(1L, properties.getLong("survey_track_id"))
        assertEquals("Survey 14 Nov 2023 · 22:13", properties.getString("label"))
        assertEquals(1_700_000_000_000L, properties.getLong("started_at_millis"))
        assertEquals("2023-11-14T22:13:20Z", properties.getString("started_at_utc"))
        assertEquals(1_700_000_600_000L, properties.getLong("ended_at_millis"))
        assertEquals("2023-11-14T22:23:20Z", properties.getString("ended_at_utc"))
        assertEquals(600L, properties.getLong("duration_seconds"))
        assertEquals(3, properties.getInt("point_count"))
    }

    @Test
    fun `an unfinished track exports explicit nulls rather than dropped keys`() {
        val properties = firstTrackFeature(
            sessionsToGeoJson(emptyList(), emptyList(), listOf(track(endedAtMillis = null)))
        ).getJSONObject("properties")

        listOf("ended_at_millis", "ended_at_utc", "duration_seconds").forEach { key ->
            assertTrue("$key must be present", properties.has(key))
            assertTrue("$key must be null for a track with no end", properties.isNull(key))
        }
        // Everything that IS known is still there.
        assertEquals(1_700_000_000_000L, properties.getLong("started_at_millis"))
        assertEquals(3, properties.getInt("point_count"))
    }
}

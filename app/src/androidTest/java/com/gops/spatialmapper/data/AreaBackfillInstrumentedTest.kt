package com.gops.spatialmapper.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import com.gops.spatialmapper.area.polygonAreaSquareMeters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The area backfill, against a real (in-memory) Room database.
 *
 * Instrumented rather than local because the thing under test IS the SQL: that
 * `areaSquareMeters IS NULL AND polygonVertices != ''` selects the right rows, that the TypeConverter
 * round-trips a stored footprint back into vertices the calculation can use, and that a second run
 * finds nothing. None of that can be exercised without a working SQLite + Room, which the mockable
 * android.jar used by local unit tests stubs out. The geometry itself is covered locally by
 * CanopyAreaTest — this is about the pass over the table.
 *
 * NOTE: the app is built for arm64-v8a only (see the abiFilters comment in build.gradle.kts), so this
 * needs a physical device; the standard x86_64 emulator can't install it.
 */
@RunWith(AndroidJUnit4::class)
class AreaBackfillInstrumentedTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SessionDao

    /** A ~20 m square around [center], built with computeOffset so its sides are real ground meters. */
    private fun square(center: LatLng, sideMeters: Double = 20.0): List<LatLng> {
        val half = sideMeters / 2.0
        val diagonal = half * Math.sqrt(2.0)
        return listOf(225.0, 315.0, 45.0, 135.0).map { bearing ->
            SphericalUtil.computeOffset(center, diagonal, bearing)
        }
    }

    private fun session(
        vertices: List<LatLng>,
        area: Double? = null,
        label: String = "test"
    ) = SessionEntity(
        timestampMillis = System.currentTimeMillis(),
        photoPath = "/dev/null/photo.jpg",
        latitude = 12.97,
        longitude = 77.59,
        gpsAccuracyMeters = 5f,
        headingDegrees = 90f,
        headingIsTrueNorth = true,
        pitchDegrees = 0f,
        compassReliability = "HIGH",
        distanceMeters = 10f,
        distanceMethod = "ARCORE_DEPTH",
        projectedLatitude = 12.97,
        projectedLongitude = 77.59,
        focalLengthPixels = 1400f,
        imageWidthPixels = 1920,
        imageHeightPixels = 1080,
        canopyDiameterMeters = 6.0,
        canopyDiameterHorizontalMeters = 6.0,
        canopyDiameterVerticalMeters = 6.0,
        label = label,
        polygonVertices = vertices,
        areaSquareMeters = area
    )

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dao = database.sessionDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun backfillCandidatesAreOnlyArealessRowsWithAFootprint() = runBlocking {
        val withFootprint = dao.insert(session(square(LatLng(12.9716, 77.5946))))
        val alreadyComputed = dao.insert(session(square(LatLng(12.9720, 77.5946)), area = 123.0))
        val noFootprint = dao.insert(session(emptyList()))

        val candidates = dao.getSessionsMissingArea().map { it.id }

        assertEquals(listOf(withFootprint), candidates)
        assertNotNull(dao.getSessionById(alreadyComputed)?.areaSquareMeters)
        assertNull(dao.getSessionById(noFootprint)?.areaSquareMeters)
    }

    @Test
    fun backfillWritesTheSphericalAreaOfTheStoredFootprint() = runBlocking {
        val id = dao.insert(session(square(LatLng(12.9716, 77.5946), sideMeters = 20.0)))

        val pending = dao.getSessionsMissingArea()
        pending.forEach { dao.updateArea(it.id, polygonAreaSquareMeters(it.polygonVertices)!!) }

        // ~400 m², round-tripped through the "lat,lng;..." TEXT column and back.
        assertEquals(400.0, dao.getSessionById(id)!!.areaSquareMeters!!, 1.0)
    }

    @Test
    fun backfillIsIdempotentAndSelfTerminating() = runBlocking {
        repeat(3) { dao.insert(session(square(LatLng(12.9716 + it * 0.001, 77.5946)))) }

        assertEquals(3, dao.getSessionsMissingArea().size)
        dao.getSessionsMissingArea()
            .forEach { dao.updateArea(it.id, polygonAreaSquareMeters(it.polygonVertices)!!) }

        // Second launch: nothing left to do, so the startup pass costs one empty SELECT.
        assertEquals(0, dao.getSessionsMissingArea().size)
    }

    @Test
    fun aFootprintTooSmallForAPolygonIsLeftNull() = runBlocking {
        val id = dao.insert(session(listOf(LatLng(12.97, 77.59), LatLng(12.98, 77.59))))

        val pending = dao.getSessionsMissingArea()
        // It IS a candidate (non-empty vertices), but yields no area — so nothing is written rather
        // than a fabricated 0.0.
        assertEquals(listOf(id), pending.map { it.id })
        assertNull(polygonAreaSquareMeters(pending.single().polygonVertices))
        assertNull(dao.getSessionById(id)!!.areaSquareMeters)
    }
}

package com.gops.spatialmapper.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test.db"

/**
 * Every shipped upgrade, run for real against an older database with rows in it: v4 -> v5, v5 -> v6,
 * and the v4 -> v6 chain a device takes when it skips a release.
 *
 * This is the check that matters for this project. Every schema change before v5 used
 * `fallbackToDestructiveMigration`, which silently empties the database; the app is a documentation
 * tool for field observations that cannot be re-collected, so "the upgrade keeps your data" has to be
 * a tested property rather than a claim in a comment.
 *
 * [MigrationTestHelper.runMigrationsAndValidate] does two things a hand-rolled test would not: it
 * runs the actual shipped Migration objects, and it then validates the resulting database against the
 * exported schema JSON — the same comparison Room performs when opening the database on a user's
 * phone. A migration that produces a subtly wrong table (missing NOT NULL, INTEGER where REAL was
 * expected) fails here rather than crashing on first launch after an update.
 *
 * The helper builds each old database from `app/schemas/.../<version>.json`, which is why those files
 * are checked in and must never be edited or deleted.
 *
 * Add a test here for every future version bump; the pattern is one hop plus one chain from the
 * oldest supported version.
 *
 * NOTE: needs a physical device — the app is built for arm64-v8a only (see abiFilters in
 * build.gradle.kts), so the standard x86_64 emulator cannot install it. A device-free equivalent of
 * the same checks lives in `tools/verify_migration.py`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate5To6_keepsSessionsAttemptsAndRemovedTrees() {
        // A v5 database holding one of everything v5 could hold.
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO sessions " +
                    "(timestampMillis, photoPath, headingIsTrueNorth, compassReliability, label, " +
                    "polygonVertices, areaSquareMeters) VALUES " +
                    "(1700000000000, '/data/photo_1.jpg', 1, 'HIGH', 'Neem by the gate', " +
                    "'12.9716,77.5946;12.9716,77.5948;12.9718,77.5948', 484.2)"
            )
            execSQL(
                "INSERT INTO identification_attempts " +
                    "(sessionId, timestampMillis, photoPath, candidatesJson, confirmedScientificName) " +
                    "VALUES (1, 1700000000001, '/data/leaf.jpg', '[]', 'Azadirachta indica')"
            )
            execSQL(
                "INSERT INTO removed_trees (latitude, longitude, officerLatitude, officerLongitude, " +
                    "officerAccuracyMeters, proximityMeters, markedAtMillis) " +
                    "VALUES (12.9716, 77.5946, 12.9717, 77.5947, 4.5, 15.3, 1700000000002)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        // Everything from v5 is still there and still says what it said.
        db.query("SELECT label, areaSquareMeters FROM sessions").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Neem by the gate", cursor.getString(0))
            assertEquals(484.2, cursor.getDouble(1), 1e-9)
        }
        db.query("SELECT confirmedScientificName FROM identification_attempts").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Azadirachta indica", cursor.getString(0))
        }
        db.query("SELECT proximityMeters FROM removed_trees").use { cursor ->
            assertEquals("last phase's table must survive this phase's migration", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals(15.3, cursor.getDouble(0), 1e-9)
        }

        // And the new table exists, empty, accepting both a finished and an unfinished track.
        db.query("SELECT COUNT(*) FROM survey_tracks").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO survey_tracks (label, startedAtMillis, endedAtMillis, pointCount, points) " +
                "VALUES ('Survey 14 Nov 2023', 1700000000000, 1700000600000, 2, " +
                "'12.9716,77.5946,1700000000000;12.9718,77.5948,1700000003000')"
        )
        db.execSQL(
            "INSERT INTO survey_tracks (label, startedAtMillis, endedAtMillis, pointCount, points) " +
                "VALUES ('Interrupted', 1700000700000, NULL, 0, '')"
        )
        db.query(
            "SELECT id, endedAtMillis, pointCount, points FROM survey_tracks ORDER BY id"
        ).use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals(1700000600000L, cursor.getLong(1))
            assertEquals(2, cursor.getInt(2))
            assertTrue("the serialized track round-trips", cursor.getString(3).contains(";"))
            val firstId = cursor.getLong(0)
            cursor.moveToNext()
            assertTrue("autoincrement must issue distinct ids", cursor.getLong(0) != firstId)
            assertNull("an unfinished track keeps a null end time", cursor.getString(1))
            assertEquals("empty geometry is storable, not null", "", cursor.getString(3))
        }
        db.close()
    }

    /**
     * The full chain a real device takes when it skips a release: v4 straight to v6, running both
     * migrations in order. Worth its own test because "each hop works" and "the chain works" are
     * different claims, and an operator who updates monthly takes the chain.
     */
    @Test
    fun migrate4To6_runsBothMigrationsInSequence() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO sessions " +
                    "(timestampMillis, photoPath, headingIsTrueNorth, compassReliability, label, " +
                    "polygonVertices) VALUES " +
                    "(1700000000000, '/data/photo_1.jpg', 1, 'HIGH', 'Two versions old', " +
                    "'12.9716,77.5946;12.9716,77.5948;12.9718,77.5948')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_4_5, MIGRATION_5_6)

        db.query("SELECT label FROM sessions").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Two versions old", cursor.getString(0))
        }
        // Both new tables exist after the chain.
        db.query("SELECT COUNT(*) FROM removed_trees").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM survey_tracks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()
    }

    @Test
    fun migrate4To5_keepsExistingSessionsAndAttempts() {
        // --- Arrange: a v4 database holding what a real device would hold. ---
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO sessions " +
                    "(timestampMillis, photoPath, headingIsTrueNorth, compassReliability, label, " +
                    "polygonVertices, latitude, longitude, canopyDiameterMeters) VALUES " +
                    "(1700000000000, '/data/photo_1.jpg', 1, 'HIGH', 'Neem by the gate', " +
                    "'12.9716,77.5946;12.9716,77.5948;12.9718,77.5948', 12.9716, 77.5946, 6.4)"
            )
            execSQL(
                "INSERT INTO identification_attempts " +
                    "(sessionId, timestampMillis, photoPath, candidatesJson, confirmedScientificName) " +
                    "VALUES (1, 1700000000001, '/data/leaf.jpg', '[]', 'Azadirachta indica')"
            )
            close()
        }

        // --- Act: the real migration, then Room's own schema validation against 5.json. ---
        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        // --- Assert: the pre-existing rows are still there, with their values intact. ---
        db.query("SELECT label, polygonVertices, canopyDiameterMeters FROM sessions").use { cursor ->
            assertEquals("the session row must survive the upgrade", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Neem by the gate", cursor.getString(0))
            assertEquals(
                "12.9716,77.5946;12.9716,77.5948;12.9718,77.5948",
                cursor.getString(1)
            )
            assertEquals(6.4, cursor.getDouble(2), 1e-9)
        }
        db.query("SELECT confirmedScientificName FROM identification_attempts").use { cursor ->
            assertEquals("the identification attempt must survive too", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Azadirachta indica", cursor.getString(0))
        }

        // --- And the new table exists, empty, and accepts both shapes of row. ---
        db.query("SELECT COUNT(*) FROM removed_trees").use { cursor ->
            cursor.moveToFirst()
            assertEquals("a new table starts empty", 0, cursor.getInt(0))
        }
        db.execSQL(
            "INSERT INTO removed_trees (latitude, longitude, officerLatitude, officerLongitude, " +
                "officerAccuracyMeters, proximityMeters, markedAtMillis) " +
                "VALUES (12.9716, 77.5946, 12.9717, 77.5947, 4.5, 15.3, 1700000000002)"
        )
        // The desk-marked case: no officer position at all.
        db.execSQL(
            "INSERT INTO removed_trees (latitude, longitude, markedAtMillis) " +
                "VALUES (12.98, 77.60, 1700000000003)"
        )
        db.query(
            "SELECT id, proximityMeters, officerLatitude FROM removed_trees ORDER BY id"
        ).use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals(15.3, cursor.getDouble(1), 1e-9)
            assertEquals(12.9717, cursor.getDouble(2), 1e-9)
            val firstId = cursor.getLong(0)
            cursor.moveToNext()
            assertTrue("autoincrement must issue distinct ids", cursor.getLong(0) != firstId)
            assertNull("a desk mark keeps null officer fields", cursor.getString(1))
            assertNull(cursor.getString(2))
        }
        db.close()
    }
}

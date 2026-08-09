package com.gops.spatialmapper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's Room database: [SessionEntity] (one row per confirmed capture) plus
 * [IdentificationAttemptEntity] (every species-identification submission for a session). See the
 * design note on SessionEntity for the multi-capture migration path.
 *
 * exportSchema is false: schema export is only useful once we start writing real migrations, and
 * enabling it requires wiring a schema output directory. When the first data-preserving migration
 * lands, flip this to true, add the KSP `room.schemaLocation` arg, and check the JSON schema in.
 *
 * VERSION 4 — DESTRUCTIVE. v4 adds the five `species*` columns to `sessions` and introduces the
 * `identification_attempts` table. As with every schema change so far there is no data migration: it
 * lands with [RoomDatabase.Builder.fallbackToDestructiveMigration] below, so an existing database is
 * DROPPED and recreated empty — anything already captured on a test device is lost on first launch
 * after this update. That remains acceptable only because there is still no production field data. If
 * real data ever exists, this is the wrong call and a proper [androidx.room.migration.Migration] must
 * be written instead — and for this particular change an additive one would be easy (five ALTER TABLE
 * ADD COLUMN plus a CREATE TABLE), so it is worth reconsidering the moment data matters.
 */
@Database(
    entities = [SessionEntity::class, IdentificationAttemptEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun identificationAttemptDao(): IdentificationAttemptDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spatialmapper.db"
                )
                    // Destructive on ANY version mismatch: wipe and recreate rather than migrate.
                    // Chosen deliberately for all three schema changes so far (v1→v2 category
                    // removal, v2→v3 segmentation removal, v3→v4 species identification) because no
                    // production data exists. dropAllTables = true drops every table Room manages
                    // (not just changed ones), which is the honest behaviour for a wipe-and-recreate
                    // policy — and now that there are two tables, it is what keeps them consistent.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

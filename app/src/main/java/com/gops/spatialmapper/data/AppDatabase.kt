package com.gops.spatialmapper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The app's Room database: [SessionEntity] (one row per confirmed capture),
 * [IdentificationAttemptEntity] (every species-identification submission for a session),
 * [RemovedTreeEntity] (a tree visible in the imagery but gone on the ground), and
 * [SurveyTrackEntity] (a deliberately recorded walk). See the design note on SessionEntity for the
 * multi-capture migration path.
 *
 * exportSchema is now TRUE, and `app/schemas/` is checked in. It was off while every schema change
 * wiped the database — there was no history worth keeping — and the previous revision of this file
 * said to flip it "when the first data-preserving migration lands". This is that version.
 *
 * VERSION 6 — THE SECOND REAL MIGRATION, AND STILL NO DESTRUCTIVE FALLBACK.
 *
 * v4 -> v5 added the `removed_trees` table via [MIGRATION_4_5]; v5 -> v6 adds `survey_tracks` via
 * [MIGRATION_5_6]. Both are single CREATE TABLE statements for tables that did not previously exist
 * and neither touches an existing table, so every row a user already has survives. That is not an
 * aspiration — it is a property of the statements, verified in MigrationTest and in
 * `tools/verify_migration.py`.
 *
 * `fallbackToDestructiveMigration` is GONE, deliberately, and should not come back. This app is a
 * documentation tool for a government officer: the rows in it are field observations that cannot be
 * re-collected by walking back to the same tree a month later. The old policy traded that data for
 * developer convenience on every version bump. The new policy is that an unhandled version mismatch
 * throws IllegalStateException on open — loudly, on the developer's own device, before release —
 * rather than silently emptying somebody's survey. A crash is a bug you can ship a fix for; a wipe
 * is data that no longer exists.
 *
 * Consequence for anyone changing the schema from here: bump [version], add a Migration to
 * [ALL_MIGRATIONS], and let the exported JSON regenerate. Skipping any of the three fails fast.
 */
@Database(
    entities = [
        SessionEntity::class,
        IdentificationAttemptEntity::class,
        RemovedTreeEntity::class,
        SurveyTrackEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun identificationAttemptDao(): IdentificationAttemptDao

    abstract fun removedTreeDao(): RemovedTreeDao

    abstract fun surveyTrackDao(): SurveyTrackDao

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
                    // The whole point of this phase. No fallbackToDestructiveMigration: an upgrade
                    // path that isn't covered here is a build error waiting to happen, not a licence
                    // to delete the user's records. See the class note.
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

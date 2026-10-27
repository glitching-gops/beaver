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
 * VERSION 7 — TREE CLASSIFICATION (manual growth forms).
 *
 * v6 -> v7 adds four new columns to the sessions table: treeClass, classSource, classConfirmedAtMillis,
 * and classLookupVersion. All are additive columns with defaults, so every existing row survives with
 * UNCLASSIFIED as the default class and UNSET as the source. No table rebuild, no data copy.
 *
 * VERSION 8 — CLUSTERS (several trees per capture).
 *
 * v7 -> v8 via [MIGRATION_7_8]: a new `cluster_members` child table (one row per EXTRA tree in a
 * capture, ON DELETE CASCADE from `sessions`), five nullable `sessions` columns describing the depth
 * image saved at shutter time, and a nullable `identification_attempts.memberIndex`. Additive only:
 * the session row still IS the centre tree, so every existing session reads as a cluster of one with
 * no data moved.
 *
 * v4 -> v5 added the `removed_trees` table via [MIGRATION_4_5]; v5 -> v6 adds `survey_tracks` via
 * [MIGRATION_5_6]. v6 -> v7 alters `sessions` via [MIGRATION_6_7]. All preserve existing rows by
 * construction, verified in MigrationTest and in `tools/verify_migration.py`.
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
        SurveyTrackEntity::class,
        ClusterMemberEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    abstract fun identificationAttemptDao(): IdentificationAttemptDao

    abstract fun removedTreeDao(): RemovedTreeDao

    abstract fun surveyTrackDao(): SurveyTrackDao

    abstract fun clusterMemberDao(): ClusterMemberDao

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

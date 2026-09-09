package com.gops.spatialmapper.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real, data-preserving schema migrations. This file starts the project's migration history.
 *
 * Everything before v4 was handled by `fallbackToDestructiveMigration`, which drops every table and
 * recreates it empty. That was defensible only while the database held nothing but test captures.
 * It is not defensible for a tool a forest officer records real field observations with, so from v4
 * onwards each version bump gets a Migration here and the destructive fallback is gone from
 * [AppDatabase] entirely — see the note there on why a loud crash beats a silent wipe.
 */

/**
 * v4 -> v5: adds the `removed_trees` table. Nothing else.
 *
 * This is deliberately the smallest schema change the project could have chosen to migrate for real:
 * a single CREATE TABLE for a brand-new, independent table. No ALTER, no column rename, no data
 * copy, no table rebuild, and — critically — not one statement that touches `sessions` or
 * `identification_attempts`. Every row a user already has is untouched by definition, because this
 * migration cannot reach those tables.
 *
 * THE SQL MUST MATCH ROOM'S OWN EXACTLY. Room stores a hash of the expected schema and, on the first
 * open after a migration, compares the real database against the schema it generated from the
 * @Entity classes. A migration that produces a *nearly* right table (a missing NOT NULL, INTEGER
 * where REAL was expected, a different default) passes the migration and then throws
 * IllegalStateException on open, which is a far more confusing failure than a syntax error. So this
 * statement is copied verbatim from the `createSql` that Room emitted into
 * `app/schemas/com.gops.spatialmapper.data.AppDatabase/5.json`, with the `${'$'}{TABLE_NAME}`
 * placeholder replaced by the literal table name. Do not hand-edit it; if the entity changes,
 * regenerate and copy again.
 *
 * Type mapping, for reference when reading it: Kotlin `Long`/`Int` -> INTEGER, `Double`/`Float` ->
 * REAL, nullable -> no NOT NULL. `id` is INTEGER PRIMARY KEY AUTOINCREMENT because the entity
 * declares `autoGenerate = true`.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `removed_trees` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`latitude` REAL NOT NULL, " +
                "`longitude` REAL NOT NULL, " +
                "`officerLatitude` REAL, " +
                "`officerLongitude` REAL, " +
                "`officerAccuracyMeters` REAL, " +
                "`proximityMeters` REAL, " +
                "`markedAtMillis` INTEGER NOT NULL)"
        )
    }
}

/**
 * v5 -> v6: adds the `survey_tracks` table. Nothing else.
 *
 * Structurally identical to [MIGRATION_4_5], and for the same reason: a brand-new, independent table
 * added by a single CREATE TABLE. No ALTER, no data copy, no table rebuild, and not one statement
 * that can reach `sessions`, `identification_attempts` or `removed_trees`. Rows a user already has
 * are untouched by construction, not by care.
 *
 * The track geometry is a TEXT column rather than a child table — see the long note on
 * [SurveyTrackEntity] for why — which is also what keeps this migration to one statement with no
 * foreign key and no index to get wrong.
 *
 * As with v4 -> v5, the statement below is copied verbatim from the `createSql` Room emitted into
 * `app/schemas/com.gops.spatialmapper.data.AppDatabase/6.json`, with `${'$'}{TABLE_NAME}` replaced by
 * the literal name. Room compares the migrated database against that schema on first open and throws
 * if they disagree, so hand-editing this is how you get a migration that succeeds and then crashes.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `survey_tracks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`startedAtMillis` INTEGER NOT NULL, " +
                "`endedAtMillis` INTEGER, " +
                "`pointCount` INTEGER NOT NULL, " +
                "`points` TEXT NOT NULL)"
        )
    }
}

/**
 * Every migration the database knows about, in one list so [AppDatabase] cannot forget to register
 * one. Append here; never remove or renumber an entry that has shipped.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_4_5, MIGRATION_5_6)

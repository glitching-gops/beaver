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
 * v6 -> v7: adds four new columns to the `sessions` table for manual tree classification.
 *
 * The columns are all additive with defaults: an existing row needs no migration logic, just the
 * four new columns appended with their default values. The tree class defaults to UNCLASSIFIED
 * (as its .name string), the source to UNSET, and the timestamps to null. This keeps the migration
 * to a single ALTER TABLE with four ADD COLUMN statements, no data copy, no table rebuild.
 *
 * The columns are:
 * - `treeClass` TEXT NOT NULL DEFAULT 'UNCLASSIFIED' (TreeClass.name)
 * - `classSource` TEXT NOT NULL DEFAULT 'UNSET' (ClassSource.name)
 * - `classConfirmedAtMillis` INTEGER (nullable)
 * - `classLookupVersion` TEXT (nullable, reserved for next phase)
 *
 * The type mapping: Kotlin `Long` -> INTEGER, `String` -> TEXT, nullable -> no NOT NULL.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `treeClass` TEXT NOT NULL DEFAULT 'UNCLASSIFIED'")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `classSource` TEXT NOT NULL DEFAULT 'UNSET'")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `classConfirmedAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `classLookupVersion` TEXT")
    }
}

/**
 * v7 -> v8: clusters — several trees recorded from one capture.
 *
 * Additive in exactly the two ways the policy allows, and nothing else:
 *
 *  1. NEW COLUMNS, all nullable with no default, so every existing row reads NULL ("no depth image
 *     was saved", "not a per-tree attempt") — which is the truth for every pre-v8 capture:
 *       - `sessions`: depthImagePath, depthImageWidth, depthImageHeight, depthRotationDegrees,
 *         depthTransform — the depth image saved at shutter time and how photo pixels map into it.
 *       - `identification_attempts`: memberIndex — which cluster tree an attempt identified on its
 *         own; NULL = the capture as a whole, which is what every older attempt was.
 *  2. A NEW TABLE, `cluster_members`, one row per EXTRA tree, ON DELETE CASCADE from `sessions`,
 *     plus its unique (sessionId, memberIndex) index.
 *
 * No table rebuild and no data copy: the session row keeps meaning "the centre tree", so an
 * existing session simply has zero members — a cluster of one — without anything being moved.
 *
 * The CREATE TABLE and CREATE INDEX are copied verbatim from the `createSql` Room emitted into
 * `app/schemas/com.gops.spatialmapper.data.AppDatabase/8.json`, with `${'$'}{TABLE_NAME}` replaced
 * by the literal name. The ADD COLUMN types follow that file's field list (Kotlin `String?` -> TEXT,
 * `Int?` -> INTEGER, nullable -> no NOT NULL). Do not hand-edit; regenerate and copy again.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `depthImagePath` TEXT")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `depthImageWidth` INTEGER")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `depthImageHeight` INTEGER")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `depthRotationDegrees` INTEGER")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `depthTransform` TEXT")
        db.execSQL("ALTER TABLE `identification_attempts` ADD COLUMN `memberIndex` INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cluster_members` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`memberIndex` INTEGER NOT NULL, " +
                "`ellipseCenterXPixels` REAL NOT NULL, " +
                "`ellipseCenterYPixels` REAL NOT NULL, " +
                "`ellipseRadiusXPixels` REAL NOT NULL, " +
                "`ellipseRadiusYPixels` REAL NOT NULL, " +
                "`distanceMeters` REAL NOT NULL, " +
                "`distanceMethod` TEXT NOT NULL, " +
                "`bearingDegrees` REAL NOT NULL, " +
                "`canopyDiameterHorizontalMeters` REAL NOT NULL, " +
                "`canopyDiameterVerticalMeters` REAL NOT NULL, " +
                "`canopyDiameterMeters` REAL NOT NULL, " +
                "`projectedLatitude` REAL NOT NULL, " +
                "`projectedLongitude` REAL NOT NULL, " +
                "`polygonVertices` TEXT NOT NULL, " +
                "`areaSquareMeters` REAL, " +
                "`treeClass` TEXT NOT NULL, " +
                "`classSource` TEXT NOT NULL, " +
                "`classConfirmedAtMillis` INTEGER, " +
                "`speciesScientificName` TEXT, " +
                "`speciesCommonName` TEXT, " +
                "`speciesGenus` TEXT, " +
                "`speciesConfidence` REAL, " +
                "`speciesSource` TEXT, " +
                "`speciesInherited` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_cluster_members_sessionId_memberIndex` " +
                "ON `cluster_members` (`sessionId`, `memberIndex`)"
        )
    }
}

/**
 * Every migration the database knows about, in one list so [AppDatabase] cannot forget to register
 * one. Append here; never remove or renumber an entry that has shipped.
 */
val ALL_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)

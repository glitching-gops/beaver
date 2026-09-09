package com.gops.spatialmapper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A tree that is visible in the satellite imagery but is no longer standing on the ground.
 *
 * WHY A SEPARATE TABLE, not a flag on [SessionEntity]: a removed tree is precisely a tree that was
 * NEVER captured — there is no photo, no canopy measurement, no species, no AR telemetry, because
 * there is nothing there to point a camera at. It is an observation about the imagery, made by an
 * officer standing in a clearing. Modelling it as a session with 25 null columns would misrepresent
 * what the record is and make every query over sessions have to remember to exclude it.
 *
 * That independence is also what makes this the right change to carry the project's first real
 * additive migration (v4 -> v5, see [AppDatabase]): one CREATE TABLE, no existing table touched.
 *
 * CONFIDENCE IS INFERRED, NOT ASKED. There is deliberately no `confidence` or `reason` column and no
 * prompt in the UI. [proximityMeters] carries that signal on its own: a few metres means the officer
 * was standing at the stump when they marked it, a few hundred means they marked it from a desk off
 * the imagery, and null means there was no fix to compare against. Anyone reading the export can
 * apply their own threshold to a real number, which is more honest than a self-reported dropdown —
 * and it costs the officer nothing at marking time, which is the point of keeping this fast.
 *
 * @param latitude          where the removed tree was: the map centre under the reticle at Confirm.
 * @param longitude         same.
 * @param officerLatitude   where the OFFICER was when they marked it. Nullable as a group with
 * @param officerLongitude  [officerLongitude] and [officerAccuracyMeters] — all three are present or
 * @param officerAccuracyMeters  all three are null. Marking off-site with no fix is a supported,
 *                          normal case, not a failure.
 * @param proximityMeters   ground distance between the two points, or null when there was no officer
 *                          position. Stored rather than derived on read so the number reflects where
 *                          the officer stood AT MARKING TIME, which is the only moment it means
 *                          anything — recomputing it later against a different position would
 *                          silently rewrite the evidence.
 * @param markedAtMillis    when the mark was made.
 */
@Entity(tableName = "removed_trees")
data class RemovedTreeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // --- The removed tree, off the imagery ---
    val latitude: Double,
    val longitude: Double,

    // --- The officer, at marking time (all null together when there was no fix) ---
    val officerLatitude: Double? = null,
    val officerLongitude: Double? = null,
    val officerAccuracyMeters: Float? = null,

    /** Silent confidence signal — see the class note. */
    val proximityMeters: Double? = null,

    val markedAtMillis: Long
)

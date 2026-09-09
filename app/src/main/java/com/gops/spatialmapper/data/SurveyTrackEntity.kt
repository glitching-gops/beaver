package com.gops.spatialmapper.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.android.gms.maps.model.LatLng

/**
 * One point on a walked survey track: where, and when.
 *
 * The timestamp is what separates a track from a polygon. A path with no time on it says where the
 * officer went; a path with time on it says how they went — where they paused, where they hurried,
 * whether a block was walked or driven past. That is the difference between a route and evidence,
 * and it costs one number per point.
 */
data class SurveyPoint(
    val position: LatLng,
    val timestampMillis: Long
)

/**
 * A deliberately recorded walk: the officer pressed Start survey, walked, and pressed End.
 *
 * NOT the same thing as Scout's live breadcrumb. The breadcrumb is a glance-down aid that exists for
 * as long as Scout is on and is then thrown away; this is a record someone chose to keep. They share
 * a point stream and share the filtering that cleans it (see [com.gops.spatialmapper.scout.ScoutTrail]),
 * but they differ in the only way that matters: one was asked for.
 *
 * WHY THE POINTS LIVE IN A COLUMN AND NOT A CHILD TABLE.
 *
 * The obvious relational answer is a `survey_track_points` table with a foreign key. It was rejected
 * for the same reason, and by the same precedent, as [IdentificationAttemptEntity.candidatesJson]:
 * "stored as a JSON blob rather than a third table because it is display-only history — nothing
 * queries across candidates, and a candidates table would triple the schema for no read we actually
 * perform." Track points are display-only geometry in exactly that sense. Nothing in the app selects,
 * filters, joins or aggregates an individual point; the only two reads are "draw this whole track"
 * and "how many points does it have", and the second is answered by [pointCount] without touching
 * the geometry at all.
 *
 * What that buys, concretely:
 *  - The v5 -> v6 migration is one CREATE TABLE with no foreign key and no index, which is the
 *    smallest, most obviously-additive change available.
 *  - A track is written in a single insert and read in a single row, so there is no way to end up
 *    with a track row whose points are half-written.
 *  - The survey list never loads geometry: [SurveyTrackDao.observeSummaries] projects onto
 *    [SurveyTrackSummary], so scrolling a year of surveys costs five small columns per row instead
 *    of a megabyte of coordinates.
 *
 * What it costs: no per-point SQL. If a later phase genuinely needs "which surveys passed within 50 m
 * of this tree", that is the moment to normalise — and it is a straightforward additive migration
 * that reads these columns and fans them out, not a rewrite.
 *
 * The serialization format extends the one [Converters] already uses for
 * [SessionEntity.polygonVertices] with a third field: `"lat,lng,millis;lat,lng,millis;..."`.
 *
 * @param label          auto-generated from the start time. There is deliberately no naming UI —
 *                       an officer ending a walk wants the record filed, not a text field.
 * @param endedAtMillis  null means "not finished". This phase only ever writes completed surveys, so
 *                       in practice it is always set; it is nullable because the schema has to
 *                       accommodate a future incremental writer that flushes points during a long
 *                       walk (see the process-death note on SurveyRecorder), and because a null here
 *                       is the only honest way to render a track that was interrupted.
 * @param pointCount     denormalized [points].size, so the list can show it without deserializing.
 */
@Entity(tableName = "survey_tracks")
data class SurveyTrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val label: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val pointCount: Int,
    val points: List<SurveyPoint>
)

/**
 * The list view of a survey: everything a row shows, and nothing else.
 *
 * A projection rather than the full entity because [SurveyTrackEntity.points] is by far the largest
 * thing in the table and the list never draws it. Room populates this from a column-listing SELECT;
 * it is not an @Entity and owns no table of its own.
 */
data class SurveyTrackSummary(
    val id: Long,
    val label: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val pointCount: Int
)

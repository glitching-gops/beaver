package com.gops.spatialmapper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [SurveyTrackEntity], in the same shape as [SessionDao] and [RemovedTreeDao]:
 * suspend writes, a [Flow] read that redraws the list on insert/delete, and no update method.
 *
 * No update is the point rather than an omission — editing a recorded walk's points after the fact
 * is explicitly out of scope, and a track you can retouch is not evidence of anything.
 *
 * Note the two different reads. The list uses [observeSummaries], which never selects the `points`
 * column; the map overlay uses [getById], which loads exactly one full track. That split is the
 * whole reason the geometry can live in a column without making the list expensive — see the note on
 * [SurveyTrackEntity].
 */
@Dao
interface SurveyTrackDao {

    @Insert
    suspend fun insert(track: SurveyTrackEntity): Long

    /**
     * Newest first. Column-listing SELECT, not `SELECT *`: the projection is the optimisation, and
     * spelling the columns out is what keeps the serialized geometry off this query.
     */
    @Query(
        "SELECT id, label, startedAtMillis, endedAtMillis, pointCount FROM survey_tracks " +
            "ORDER BY startedAtMillis DESC"
    )
    fun observeSummaries(): Flow<List<SurveyTrackSummary>>

    /** The full track, geometry included. One row, loaded only when a track is actually drawn. */
    @Query("SELECT * FROM survey_tracks WHERE id = :id")
    suspend fun getById(id: Long): SurveyTrackEntity?

    /** Reactive full read, so the map overlay clears itself if the track it is showing is deleted. */
    @Query("SELECT * FROM survey_tracks WHERE id = :id")
    fun observeById(id: Long): Flow<SurveyTrackEntity?>

    /** Non-reactive read for the exporter, which wants one consistent snapshot rather than a stream. */
    @Query("SELECT * FROM survey_tracks ORDER BY startedAtMillis DESC")
    suspend fun getAllOnce(): List<SurveyTrackEntity>

    @Query("DELETE FROM survey_tracks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

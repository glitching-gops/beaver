package com.gops.spatialmapper.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Thin repository over [SurveyTrackDao], mirroring [RemovedTreeRepository]: private constructor, a
 * `get(context)` factory over the shared [AppDatabase], Flow reads.
 *
 * Like removed trees and unlike sessions, a survey track owns no files on disk, so deleting one is
 * exactly a row delete with no cleanup to forget.
 */
class SurveyTrackRepository private constructor(private val dao: SurveyTrackDao) {

    /** Newest first, geometry excluded — see [SurveyTrackDao.observeSummaries]. */
    fun observeSummaries(): Flow<List<SurveyTrackSummary>> = dao.observeSummaries()

    /** One full track, observed so a deletion clears whatever is drawing it. */
    fun observeTrack(id: Long): Flow<SurveyTrackEntity?> = dao.observeById(id)

    suspend fun getTrack(id: Long): SurveyTrackEntity? = dao.getById(id)

    /** One consistent snapshot, for the exporter. */
    suspend fun getAllOnce(): List<SurveyTrackEntity> = dao.getAllOnce()

    suspend fun insert(track: SurveyTrackEntity): Long = dao.insert(track)

    suspend fun delete(id: Long) = dao.deleteById(id)

    companion object {
        fun get(context: Context): SurveyTrackRepository =
            SurveyTrackRepository(AppDatabase.getInstance(context).surveyTrackDao())
    }
}

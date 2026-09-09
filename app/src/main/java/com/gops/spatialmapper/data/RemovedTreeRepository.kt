package com.gops.spatialmapper.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Thin repository over [RemovedTreeDao], mirroring [SessionRepository]'s shape: a private
 * constructor, a `get(context)` factory over the shared [AppDatabase] instance, and Flow reads.
 *
 * Separate from [SessionRepository] rather than folded into it because the two own genuinely
 * different records — a session is a captured tree with a photo and a measurement, a removed tree is
 * an observation that something in the imagery is gone. SessionRepository also owns photo-file
 * cleanup on delete, which has no analogue here: a removed-tree record references no files, so
 * deleting one is exactly a row delete and nothing more.
 */
class RemovedTreeRepository private constructor(private val dao: RemovedTreeDao) {

    /** Newest first. Re-emits on every insert/delete, which is what redraws the map markers. */
    fun observeRemovedTrees(): Flow<List<RemovedTreeEntity>> = dao.getAllRemovedTrees()

    /** One consistent snapshot, for the exporter. */
    suspend fun getAllOnce(): List<RemovedTreeEntity> = dao.getAllRemovedTreesOnce()

    suspend fun insert(removedTree: RemovedTreeEntity): Long = dao.insert(removedTree)

    suspend fun delete(id: Long) = dao.deleteById(id)

    companion object {
        fun get(context: Context): RemovedTreeRepository =
            RemovedTreeRepository(AppDatabase.getInstance(context).removedTreeDao())
    }
}

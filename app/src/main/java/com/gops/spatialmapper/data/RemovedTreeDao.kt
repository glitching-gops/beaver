package com.gops.spatialmapper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [RemovedTreeEntity], following the same shape as [SessionDao]: suspend writes,
 * a [Flow] read so the map redraws itself on insert/delete, and no update method at all.
 *
 * The missing update is deliberate and matches the phase's scope: a removed-tree record is a single
 * observation made in one action, and correcting a mis-tap is delete-and-remark rather than an edit
 * flow that would need its own targeting mode. Nothing here can rewrite a stored mark.
 */
@Dao
interface RemovedTreeDao {

    @Insert
    suspend fun insert(removedTree: RemovedTreeEntity): Long

    /** Newest first, so the map and any future list agree on ordering. */
    @Query("SELECT * FROM removed_trees ORDER BY markedAtMillis DESC")
    fun getAllRemovedTrees(): Flow<List<RemovedTreeEntity>>

    /** Non-reactive read for the exporter, which wants one consistent snapshot rather than a stream. */
    @Query("SELECT * FROM removed_trees ORDER BY markedAtMillis DESC")
    suspend fun getAllRemovedTreesOnce(): List<RemovedTreeEntity>

    @Query("DELETE FROM removed_trees WHERE id = :id")
    suspend fun deleteById(id: Long)
}

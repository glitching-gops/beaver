package com.gops.spatialmapper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [SessionEntity]. All access is suspend/Flow so it never touches the main thread:
 * Room runs suspend queries on its own executor, and the [Flow] from [getAllSessions] emits a fresh
 * list on every write, which is what makes the history list update reactively after a save/delete.
 */
@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    /** Newest first. Returns a Flow so the history UI re-renders automatically on insert/delete. */
    @Query("SELECT * FROM sessions ORDER BY timestampMillis DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): SessionEntity?

    /**
     * Reactive single-row read, used by the session detail screen.
     *
     * Detail used to load once with [getSessionById], which was fine while a row never changed after
     * it was saved. Confirming a species mutates the row from a screen that stays on top of it, so
     * detail has to observe rather than snapshot or the newly-identified species wouldn't appear
     * until you navigated away and back.
     */
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSessionById(id: Long): Flow<SessionEntity?>

    /**
     * Writes the current species answer.
     *
     * A targeted UPDATE rather than `@Update(session)`: the detail screen holds a row that was read
     * some time ago, and re-inserting all of it would let a stale copy clobber anything else that
     * changed meanwhile. This touches exactly the five columns identification owns.
     */
    @Query(
        "UPDATE sessions SET speciesScientificName = :scientificName, " +
            "speciesCommonName = :commonName, speciesGenus = :genus, " +
            "speciesConfidence = :confidence, speciesSource = :source WHERE id = :id"
    )
    suspend fun updateSpecies(
        id: Long,
        scientificName: String?,
        commonName: String?,
        genus: String?,
        confidence: Double?,
        source: String?
    )

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

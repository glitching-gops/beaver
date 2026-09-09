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

    /**
     * Rows that the area backfill has to visit: no area yet, but a footprint to derive one from.
     *
     * Both halves of the filter are deliberate. `areaSquareMeters IS NULL` makes the backfill
     * idempotent — a row that has already been computed is never revisited, so the startup pass is
     * a no-op from the second launch onwards. `polygonVertices != ''` skips rows that could never
     * produce an area (the converter serializes an empty list to the empty string), so they don't
     * get re-read on every single launch forever.
     */
    @Query("SELECT * FROM sessions WHERE areaSquareMeters IS NULL AND polygonVertices != ''")
    suspend fun getSessionsMissingArea(): List<SessionEntity>

    /**
     * Writes one computed area. A targeted UPDATE for the same reason [updateSpecies] is one: the
     * backfill holds rows read at startup and must not write back any other column from that
     * possibly-stale snapshot.
     */
    @Query("UPDATE sessions SET areaSquareMeters = :areaSquareMeters WHERE id = :id")
    suspend fun updateArea(id: Long, areaSquareMeters: Double)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

package com.gops.spatialmapper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [IdentificationAttemptEntity].
 *
 * There is no update-the-whole-row method on purpose: an attempt is immutable once written, apart
 * from the single moment the operator picks one of its candidates. [markConfirmed] is that moment,
 * and confining it to one targeted UPDATE means nothing else can quietly rewrite a stored attempt.
 */
@Dao
interface IdentificationAttemptDao {

    @Insert
    suspend fun insert(attempt: IdentificationAttemptEntity): Long

    /** Newest attempt first — the history list reads top-down as "most recent try". */
    @Query(
        "SELECT * FROM identification_attempts WHERE sessionId = :sessionId " +
            "ORDER BY timestampMillis DESC"
    )
    fun observeForSession(sessionId: Long): Flow<List<IdentificationAttemptEntity>>

    /** Non-reactive read, used to collect photo paths for cleanup when a session is deleted. */
    @Query("SELECT * FROM identification_attempts WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<IdentificationAttemptEntity>

    @Query(
        "UPDATE identification_attempts SET confirmedScientificName = :scientificName WHERE id = :id"
    )
    suspend fun markConfirmed(id: Long, scientificName: String)
}

package com.gops.spatialmapper.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thin repository over [SessionDao]. Exists mainly to own the one piece of logic that isn't a plain
 * DB call: cleaning up the on-disk JPEG when a session is deleted.
 */
class SessionRepository private constructor(
    private val dao: SessionDao,
    private val attemptDao: IdentificationAttemptDao
) {

    fun observeSessions(): Flow<List<SessionEntity>> = dao.getAllSessions()

    fun observeSession(id: Long): Flow<SessionEntity?> = dao.observeSessionById(id)

    suspend fun insert(session: SessionEntity): Long = dao.insert(session)

    suspend fun getById(id: Long): SessionEntity? = dao.getSessionById(id)

    // --- Species identification ---

    fun observeIdentificationAttempts(sessionId: Long): Flow<List<IdentificationAttemptEntity>> =
        attemptDao.observeForSession(sessionId)

    /** Records a submission and its ranked candidates. Returns the new attempt's id. */
    suspend fun recordIdentificationAttempt(attempt: IdentificationAttemptEntity): Long =
        attemptDao.insert(attempt)

    /**
     * Writes the operator's choice to both places it belongs: the session's current-answer columns,
     * and the attempt row that produced it.
     *
     * Two writes rather than one because they answer different questions — "what is this plant?" and
     * "which submission settled it?". Attempt-first so that a failure between them leaves the audit
     * trail ahead of the summary rather than behind it: a confirmed attempt with an unset session is
     * recoverable by looking at the history; a species with no attempt behind it is not.
     */
    suspend fun confirmSpecies(
        sessionId: Long,
        attemptId: Long,
        scientificName: String,
        commonName: String?,
        genus: String?,
        confidence: Double?,
        source: String
    ) {
        attemptDao.markConfirmed(attemptId, scientificName)
        dao.updateSpecies(
            id = sessionId,
            scientificName = scientificName,
            commonName = commonName,
            genus = genus,
            confidence = confidence,
            source = source
        )
    }

    /**
     * Deletes the DB row AND every JPEG it owns — the capture photo plus one per identification
     * attempt.
     *
     * DECISION: we delete the photo files alongside the row. They live in the app's private external
     * files dir (getExternalFilesDir) — this app is their sole owner and the paths are never shared
     * outside the app in the current phases, so orphaned files would just be dead weight. Each file
     * delete is best-effort and wrapped so a failure (or an already-missing file) never blocks
     * removing the row. If a future phase starts exporting/sharing these paths, revisit this — a
     * shared path shouldn't be deleted out from under whoever it was shared with.
     *
     * The attempt ROWS go away on their own via ON DELETE CASCADE, but a cascade knows nothing about
     * the filesystem, so their paths are collected BEFORE the delete and unlinked afterwards.
     * Skipping that would leak a multi-megabyte close-up per identification attempt, invisibly.
     */
    suspend fun delete(session: SessionEntity) = withContext(Dispatchers.IO) {
        val attemptPhotos = runCatching { attemptDao.getForSession(session.id).map { it.photoPath } }
            .onFailure { Log.w(TAG, "Could not list identification photos for ${session.id}", it) }
            .getOrDefault(emptyList())

        dao.deleteById(session.id)

        (listOf(session.photoPath) + attemptPhotos).forEach { path -> deleteFileQuietly(path) }
        Unit
    }

    private fun deleteFileQuietly(path: String) {
        if (path.isBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Photo file existed but could not be deleted: $path")
            }
        }.onFailure { Log.w(TAG, "Error deleting photo file $path", it) }
    }

    companion object {
        private const val TAG = "SpatialMapperRepo"

        fun get(context: Context): SessionRepository {
            val database = AppDatabase.getInstance(context)
            return SessionRepository(database.sessionDao(), database.identificationAttemptDao())
        }
    }
}

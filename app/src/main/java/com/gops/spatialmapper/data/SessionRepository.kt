package com.gops.spatialmapper.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.gops.spatialmapper.area.polygonAreaSquareMeters
import java.io.File

/**
 * Thin repository over [SessionDao]. Exists mainly to own the logic that isn't a plain DB call:
 * cleaning up the on-disk files when a session is deleted, and the multi-table writes a cluster
 * needs (a capture and its members saved together; a species applied to a cluster).
 */
class SessionRepository private constructor(
    private val database: AppDatabase,
    private val dao: SessionDao,
    private val attemptDao: IdentificationAttemptDao,
    private val memberDao: ClusterMemberDao
) {

    fun observeSessions(): Flow<List<SessionEntity>> = dao.getAllSessions()

    fun observeSession(id: Long): Flow<SessionEntity?> = dao.observeSessionById(id)

    suspend fun insert(session: SessionEntity): Long = dao.insert(session)

    suspend fun getById(id: Long): SessionEntity? = dao.getSessionById(id)

    // --- Clusters ---

    /** Every member of every session; the maps group them by [ClusterMemberEntity.sessionId]. */
    fun observeAllMembers(): Flow<List<ClusterMemberEntity>> = memberDao.observeAll()

    fun observeMembers(sessionId: Long): Flow<List<ClusterMemberEntity>> =
        memberDao.observeForSession(sessionId)

    suspend fun getAllMembers(): List<ClusterMemberEntity> = memberDao.getAll()

    /**
     * Saves one capture: the centre tree as the session row, exactly as a single-tree capture always
     * was, plus its extra trees as [members] pointing at it.
     *
     * One transaction, so a capture is never half-saved — a session whose members failed to write
     * would silently claim to be a cluster of one. The members' sessionId is filled in here from the
     * new row's id; whatever the caller put there is ignored.
     */
    suspend fun insertCapture(session: SessionEntity, members: List<ClusterMemberEntity>): Long =
        database.withTransaction {
            val id = dao.insert(session)
            if (members.isNotEmpty()) memberDao.insertAll(members.map { it.copy(id = 0, sessionId = id) })
            id
        }

    // --- Area backfill ---

    /**
     * Computes and stores [SessionEntity.areaSquareMeters] for every row that is missing one, and
     * returns how many rows were written.
     *
     * Exists because the column shipped nullable and unpopulated: rows captured before area
     * calculation existed carry a confirmed footprint but no area. Rather than a data migration
     * (Room migrations run raw SQL, and the area of a spherical polygon is not something SQLite can
     * compute from a "lat,lng;..." string), this is a plain idempotent pass over the rows the DAO
     * says still need it — see [SessionDao.getSessionsMissingArea] for why it self-terminates.
     *
     * Row-at-a-time rather than one big transaction: the whole point is that a partial run is
     * useful. If the process dies halfway, the rows already written stay written and the next launch
     * picks up exactly the remainder, because "needs backfilling" is a property of the row itself
     * rather than of a stored migration flag.
     *
     * A row whose stored vertices refuse to parse into a polygon is skipped and left null — it has
     * no area, and writing 0.0 would be a fabricated measurement.
     */
    suspend fun backfillMissingAreas(): Int = withContext(Dispatchers.IO) {
        val pending = dao.getSessionsMissingArea()
        if (pending.isEmpty()) return@withContext 0

        var updated = 0
        var skipped = 0
        pending.forEach { session ->
            val area = polygonAreaSquareMeters(session.polygonVertices)
            if (area == null) {
                skipped++
                return@forEach
            }
            runCatching { dao.updateArea(session.id, area) }
                .onSuccess { updated++ }
                .onFailure { Log.w(TAG, "Area backfill failed for session ${session.id}", it) }
        }
        Log.i(
            TAG,
            "Area backfill: ${pending.size} candidate row(s), $updated updated, $skipped skipped " +
                "(unusable footprint)"
        )
        updated
    }

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
        source: String,
        memberId: Long? = null
    ) {
        attemptDao.markConfirmed(attemptId, scientificName)
        if (memberId != null) {
            // One tree of a cluster, identified on its own: its OWN value, which clears any inherited
            // flag. The session row and the other members are deliberately left alone.
            memberDao.updateOwnSpecies(memberId, scientificName, commonName, genus, confidence, source)
            return
        }
        // The capture as a whole: the centre tree, as always, plus — as a suggestion — every member
        // without a species of its own. Together, so the cluster never shows the centre identified
        // and its members not.
        database.withTransaction {
            dao.updateSpecies(
                id = sessionId,
                scientificName = scientificName,
                commonName = commonName,
                genus = genus,
                confidence = confidence,
                source = source
            )
            memberDao.inheritSpecies(sessionId, scientificName, commonName, genus, confidence, source)
        }
    }

    // --- Tree classification (manual growth form) ---

    /**
     * Writes the operator's chosen tree class (growth form) to the session, along with the source
     * and confirmation timestamp.
     *
     * A targeted UPDATE so that a possibly-stale row read earlier cannot clobber other columns.
     * If the operator deselects all chips (sets class back to UNCLASSIFIED), this clears the
     * confirmation timestamp but keeps the source as a record of what happened.
     */
    suspend fun updateTreeClass(
        sessionId: Long,
        treeClass: TreeClass?,
        classSource: ClassSource = ClassSource.MANUAL
    ) {
        val classValue = (treeClass ?: TreeClass.UNCLASSIFIED).name
        val source = classSource.name
        val confirmedAtMillis = if (treeClass != null) System.currentTimeMillis() else null
        dao.updateTreeClass(sessionId, classValue, source, confirmedAtMillis)
    }

    /** Same as [updateTreeClass], for one member of a cluster. */
    suspend fun updateMemberTreeClass(memberId: Long, treeClass: TreeClass?) {
        memberDao.updateTreeClass(
            id = memberId,
            treeClass = (treeClass ?: TreeClass.UNCLASSIFIED).name,
            classSource = ClassSource.MANUAL.name,
            classConfirmedAtMillis = if (treeClass != null) System.currentTimeMillis() else null
        )
    }

    /**
     * Deletes the DB row AND every file it owns — the capture photo, the saved depth image (v8+), and
     * one JPEG per identification attempt. Cluster members go with the row via ON DELETE CASCADE.
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

        // The saved depth image goes with the photo it was captured alongside. Member rows need no
        // file cleanup of their own: they reference the same photo and depth file as the session.
        (listOfNotNull(session.photoPath, session.depthImagePath) + attemptPhotos)
            .forEach { path -> deleteFileQuietly(path) }
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
            return SessionRepository(
                database,
                database.sessionDao(),
                database.identificationAttemptDao(),
                database.clusterMemberDao()
            )
        }
    }
}

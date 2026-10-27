package com.gops.spatialmapper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [ClusterMemberEntity]. Writes after the initial insert are targeted UPDATEs, for
 * the same reason as [SessionDao]'s: the screens that issue them hold rows read some time ago.
 */
@Dao
interface ClusterMemberDao {

    @Insert
    suspend fun insertAll(members: List<ClusterMemberEntity>)

    /** Every member of every session, for the maps and the export. */
    @Query("SELECT * FROM cluster_members ORDER BY sessionId, memberIndex")
    fun observeAll(): Flow<List<ClusterMemberEntity>>

    @Query("SELECT * FROM cluster_members WHERE sessionId = :sessionId ORDER BY memberIndex")
    fun observeForSession(sessionId: Long): Flow<List<ClusterMemberEntity>>

    @Query("SELECT * FROM cluster_members ORDER BY sessionId, memberIndex")
    suspend fun getAll(): List<ClusterMemberEntity>

    @Query(
        "UPDATE cluster_members SET treeClass = :treeClass, classSource = :classSource, " +
            "classConfirmedAtMillis = :classConfirmedAtMillis WHERE id = :id"
    )
    suspend fun updateTreeClass(id: Long, treeClass: String, classSource: String, classConfirmedAtMillis: Long?)

    /** This tree's OWN species: clears the inherited flag. */
    @Query(
        "UPDATE cluster_members SET speciesScientificName = :scientificName, " +
            "speciesCommonName = :commonName, speciesGenus = :genus, " +
            "speciesConfidence = :confidence, speciesSource = :source, speciesInherited = 0 " +
            "WHERE id = :id"
    )
    suspend fun updateOwnSpecies(
        id: Long,
        scientificName: String?,
        commonName: String?,
        genus: String?,
        confidence: Double?,
        source: String?
    )

    /**
     * The cluster's identification, offered to every member that hasn't got a species of its own.
     *
     * THE WHERE CLAUSE IS THE RULE: a member qualifies only if it has no species at all, or the one
     * it has was itself inherited (so a later cluster identification supersedes an earlier
     * suggestion). A species confirmed for that tree specifically is never overwritten from here.
     */
    @Query(
        "UPDATE cluster_members SET speciesScientificName = :scientificName, " +
            "speciesCommonName = :commonName, speciesGenus = :genus, " +
            "speciesConfidence = :confidence, speciesSource = :source, speciesInherited = 1 " +
            "WHERE sessionId = :sessionId " +
            "AND (speciesScientificName IS NULL OR speciesInherited = 1)"
    )
    suspend fun inheritSpecies(
        sessionId: Long,
        scientificName: String,
        commonName: String?,
        genus: String?,
        confidence: Double?,
        source: String
    )
}

package com.gops.spatialmapper.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One submission to the identification service, kept forever.
 *
 * WHY A SEPARATE TABLE. Retrying an identification must not erase what the previous run suggested. A
 * botanist reviewing this data later needs to see that the first photo returned a confident wrong
 * answer and the second returned the right one — that history is evidence about the record's
 * trustworthiness, exactly like the raw telemetry on [SessionEntity]. Overwriting five columns on the
 * session would destroy it.
 *
 * So the session's `species*` columns are only ever "the current answer", and every attempt — the
 * confirmed one, the rejected ones, and the ones the operator skipped without picking anything —
 * lands here.
 *
 * @param photoPath the close-up JPEG that was submitted. Full-size on disk; the upload itself is
 *   downscaled in flight (see PlantNetClient), so this is the original evidence, not what was sent.
 * @param candidatesJson the ranked candidate list as returned, serialized by
 *   [com.gops.spatialmapper.identify.encodeCandidates]. Stored as a JSON blob rather than a third
 *   table because it is display-only history — nothing queries across candidates, and a
 *   candidates table would triple the schema for no read we actually perform.
 * @param confirmedScientificName the candidate the operator picked, or null when they retried or
 *   skipped. Null is a meaningful, common value here, not an error state.
 *
 * ON DELETE CASCADE removes these rows with their session. The JPEGs they point at are NOT covered by
 * the cascade — [SessionRepository.delete] collects and unlinks them explicitly.
 */
@Entity(
    tableName = "identification_attempts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class IdentificationAttemptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,
    val timestampMillis: Long,
    val photoPath: String,
    val candidatesJson: String,
    val confirmedScientificName: String? = null
)

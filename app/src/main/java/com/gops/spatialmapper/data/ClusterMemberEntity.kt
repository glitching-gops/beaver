package com.gops.spatialmapper.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.android.gms.maps.model.LatLng

/**
 * One EXTRA tree recorded in the same capture as a session — a neighbour the operator framed with
 * its own ellipse on the same photo.
 *
 * THE SESSION ROW IS STILL THE CENTRE TREE. Nothing about [SessionEntity] changed meaning when
 * clusters arrived: it holds the tree at the optical axis, measured from the shutter-time centre
 * reading, exactly as before. A session with no rows here is a cluster of one, which is what every
 * capture saved before v8 is — so no existing data had to move.
 *
 * [memberIndex] is the tree's number as the operator saw it on the framing screen: the centre tree is
 * 1 (implicit, never stored here) and members are 2..N. Unique per session, and never renumbered
 * after the save, because exports and the identification history refer to it.
 *
 * The capture-level facts — photo, origin, heading, pitch, focal length, timestamp — are NOT
 * duplicated here; they live on the parent row and are the same for every tree in the photo.
 *
 * Deleting the session deletes its members (ON DELETE CASCADE), the same rule as the
 * identification attempts.
 */
@Entity(
    tableName = "cluster_members",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Leads with sessionId, so it also serves the foreign key's lookups.
    indices = [Index(value = ["sessionId", "memberIndex"], unique = true)]
)
data class ClusterMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val memberIndex: Int,

    // --- Framing, in captured-photo pixels (the upright JPEG on the parent row) ---
    val ellipseCenterXPixels: Double,
    val ellipseCenterYPixels: Double,
    val ellipseRadiusXPixels: Double,
    val ellipseRadiusYPixels: Double,

    // --- Distance and direction (audit log) ---
    /** AXIS depth used for sizing: depth-image median, or the ground-plane estimate. */
    val distanceMeters: Double,
    /** DistanceMethod.name: ARCORE_DEPTH_IMAGE or GEOMETRY_FALLBACK. */
    val distanceMethod: String,
    /** Capture heading + atan(dx / f): this tree's own compass bearing from the origin. */
    val bearingDegrees: Double,

    // --- Canopy measurement (audit log), same convention as the session row ---
    val canopyDiameterHorizontalMeters: Double,
    val canopyDiameterVerticalMeters: Double,
    val canopyDiameterMeters: Double,

    /** Raw projected position: origin + bearing + line-of-sight range, before any map nudge. */
    val projectedLatitude: Double,
    val projectedLongitude: Double,

    // --- User-confirmed ---
    /** Confirmed outline, serialized like SessionEntity.polygonVertices. */
    val polygonVertices: List<LatLng>,
    val areaSquareMeters: Double?,

    val treeClass: String = TreeClass.UNCLASSIFIED.name,
    val classSource: String = ClassSource.UNSET.name,
    val classConfirmedAtMillis: Long? = null,

    // --- Species ---
    val speciesScientificName: String? = null,
    val speciesCommonName: String? = null,
    val speciesGenus: String? = null,
    val speciesConfidence: Double? = null,
    val speciesSource: String? = null,
    /**
     * True when the species above was copied from the cluster's identification as a suggestion,
     * false when it was confirmed for THIS tree (or there is none). A cluster identification only
     * ever writes to members that have no species or an inherited one — never over a tree's own.
     */
    val speciesInherited: Boolean = false
)

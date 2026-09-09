package com.gops.spatialmapper.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.android.gms.maps.model.LatLng

/**
 * One saved plant record = one confirmed capture.
 *
 * SCOPE NOTE: the app records flora exclusively, so there is no category column — every row is a
 * plant. (An `assetCategory` column existed while the app also handled buildings/water/other; it was
 * dropped, along with the whole category concept, when the scope narrowed.)
 *
 * DESIGN NOTE — migratable to multiple-captures-per-session later: this is deliberately a single
 * flat table keyed by an auto-generated [id]. When multi-capture sessions arrive (a later phase),
 * the migration path is to introduce a parent `session` row and move the per-capture columns
 * (telemetry + photo + footprint) into a child `capture` table with a `sessionId` foreign key. We do
 * NOT build that now, but nothing here blocks it — no column assumes exactly one row per session.
 *
 * Field groups:
 *  - Provenance: [id], [timestampMillis], [photoPath].
 *  - Raw telemetry (the audit log — exactly what the sensors/algorithm produced at shutter time, so
 *    a reviewer can see how an outline was derived and judge its trustworthiness). Enums are stored
 *    as their [Enum.name] String so the DB stays decoupled from UI/telemetry enum ordinals.
 *  - Camera intrinsics + canopy measurement: the inputs and outputs of the manual framing step, kept
 *    in the audit log for the same reason as the sensor readings — a diameter is only interpretable
 *    if you can see the focal length and distance behind it.
 *  - User-confirmed data: [label], [polygonVertices] — what the human verified on the review screen.
 *  - [areaSquareMeters]: spherical area of [polygonVertices]. Still nullable, because a footprint
 *    with fewer than three vertices genuinely has no area — and because rows saved before area
 *    calculation existed are filled in by a startup backfill (SpatialMapperApplication) rather than
 *    by a migration, so null also means "not backfilled yet".
 *
 * The photo itself is NOT stored as a BLOB — the JPEG stays where the capture pipeline already wrote
 * it and only its absolute [photoPath] is persisted here.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // --- Provenance ---
    val timestampMillis: Long,
    val photoPath: String,

    // --- Raw telemetry (audit log) ---
    val latitude: Double?,            // origin (photo location) latitude
    val longitude: Double?,           // origin (photo location) longitude
    val gpsAccuracyMeters: Float?,
    val headingDegrees: Float?,
    val headingIsTrueNorth: Boolean,
    val pitchDegrees: Float?,
    val compassReliability: String,   // CompassReliability.name
    val distanceMeters: Float?,
    val distanceMethod: String?,      // DistanceMethod.name, null if no distance was measured
    val projectedLatitude: Double?,   // raw projected target from origin+heading+distance
    val projectedLongitude: Double?,

    // --- Camera model + canopy measurement (audit log) ---
    val focalLengthPixels: Float?,        // ARCore imageIntrinsics focal length, in capture pixels
    val imageWidthPixels: Int?,           // dimensions those pixels refer to
    val imageHeightPixels: Int?,
    // Crown size the operator framed on the captured photo: pixelSpan * distance / focalPx per axis.
    // The averaged value is what drove the polygon; both raw axes are kept so the averaging
    // convention can be revisited (or crown asymmetry analysed) without re-collecting field data.
    val canopyDiameterMeters: Double?,              // mean of the two perpendicular diameters
    val canopyDiameterHorizontalMeters: Double?,
    val canopyDiameterVerticalMeters: Double?,

    // --- User-confirmed data ---
    val label: String,
    val polygonVertices: List<LatLng>, // final confirmed canopy outline; serialized via Converters

    // --- Species (decoupled from capture; set later from the session detail screen) ---
    // These reflect the MOST RECENTLY CONFIRMED candidate only. Every attempt that was ever submitted
    // — including ones the operator rejected or skipped — lives in [IdentificationAttemptEntity],
    // which is the audit trail; these five columns are just the current answer. All null means the
    // plant has not been identified yet, which is a normal steady state, not a missing value.
    //
    // Defaulted so the capture pipeline, which knows nothing about species, keeps constructing rows
    // exactly as it did before — identification is a separate, later action by design.
    val speciesScientificName: String? = null,
    val speciesCommonName: String? = null,
    val speciesGenus: String? = null,
    val speciesConfidence: Double? = null,   // 0..1, as reported by the identification service
    val speciesSource: String? = null,       // "plantnet" today; a column so a second source can exist

    // --- Derived ---
    // Spherical area of [polygonVertices], in square meters. Written at save time from the confirmed
    // outline, and backfilled at startup for rows that predate the calculation. Defaulted to null so
    // the DAO's targeted UPDATE, not the constructor, remains the only way it gets set after a save.
    val areaSquareMeters: Double? = null
)

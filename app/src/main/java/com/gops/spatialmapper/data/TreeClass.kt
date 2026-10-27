package com.gops.spatialmapper.data

/**
 * Manual growth-form classification for a recorded tree. Chosen by the operator on the map review
 * screen and editable later from the session detail screen. This phase only supports manual
 * selection; the next phase will add species-based refinement (BROADLEAF_EVERGREEN and
 * BROADLEAF_DECIDUOUS from an identification lookup).
 *
 * Stored in the database as each enum's [name] string, the same convention used for
 * [CompassReliability] and [DistanceMethod]. Unknown values in the database fall back to
 * [UNCLASSIFIED] rather than crashing.
 */
enum class TreeClass {
    PALM,
    BROADLEAF_EVERGREEN,
    BROADLEAF_DECIDUOUS,
    BROADLEAF_UNCONFIRMED,
    BAMBOO,
    OTHER,
    UNCLASSIFIED
}

/**
 * Source of the tree classification — which phase/method produced it.
 *
 * Stored in the database as each enum's [name] string. Values are:
 * - UNSET: tree has no class yet (initial state).
 * - MANUAL: operator chose the class via the chip selector.
 * - DERIVED: a species lookup refined the class (reserved for the next phase).
 */
enum class ClassSource {
    UNSET,
    MANUAL,
    DERIVED
}

/**
 * Parses a stored [TreeClass.name], falling back to [TreeClass.UNCLASSIFIED] for null, blank or
 * unknown values — the same defensive rule as [Converters.stringToTreeClass], for the columns that
 * are stored as plain strings rather than through the converter.
 */
fun parseTreeClass(name: String?): TreeClass =
    name?.let { runCatching { TreeClass.valueOf(it) }.getOrNull() } ?: TreeClass.UNCLASSIFIED

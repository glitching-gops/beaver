package com.gops.spatialmapper.identify

import org.json.JSONArray
import org.json.JSONObject

/**
 * One ranked suggestion from an identification run.
 *
 * Deliberately flat and small: this is both what the candidate list renders and what gets frozen into
 * an [com.gops.spatialmapper.data.IdentificationAttemptEntity]'s `candidatesJson`. The full Pl@ntNet
 * response carries related-image URLs, GBIF/POWO ids, family-level fallbacks and a model version
 * string — none of which the app shows, and all of which would bloat every stored attempt.
 *
 * @param scientificName e.g. "Mangifera indica" (without the authorship suffix — the authorship is
 *   correct botanically but reads as noise in a field UI).
 * @param commonName the first English common name Pl@ntNet returns, or null if it has none. Plenty of
 *   species genuinely have no common name; that is not an error.
 * @param genus parsed from the response's own genus object rather than by splitting the scientific
 *   name on a space, which breaks on hybrids ("× Fatshedera lizei") and infraspecific names.
 * @param score Pl@ntNet's confidence in 0..1. Displayed as a percentage.
 */
data class SpeciesCandidate(
    val scientificName: String,
    val commonName: String?,
    val genus: String?,
    val score: Double
) {
    /** Confidence as a whole-number percentage, for display. */
    val confidencePercent: Int get() = (score * 100.0).toInt().coerceIn(0, 100)
}

/**
 * Serializes candidates for the `candidatesJson` column.
 *
 * Hand-rolled with `org.json` (part of the Android platform) rather than Moshi/kotlinx-serialization:
 * this is four scalar fields and the project has an established preference for not taking a dependency
 * where a few lines of platform API will do — see the Coil note in HistoryScreen for why extra Kotlin
 * artifacts are a real risk on this toolchain.
 */
fun encodeCandidates(candidates: List<SpeciesCandidate>): String {
    val array = JSONArray()
    candidates.forEach { candidate ->
        array.put(
            JSONObject().apply {
                put("scientificName", candidate.scientificName)
                // JSONObject.put(String, null) removes the key, which is exactly what we want for an
                // absent common name — decode reads it back as null via optString/has.
                candidate.commonName?.let { put("commonName", it) }
                candidate.genus?.let { put("genus", it) }
                put("score", candidate.score)
            }
        )
    }
    return array.toString()
}

/**
 * Inverse of [encodeCandidates]. Defensive in the same spirit as [com.gops.spatialmapper.data.Converters]:
 * a malformed or truncated blob degrades to whatever entries still parse instead of throwing inside
 * the history list.
 */
fun decodeCandidates(json: String?): List<SpeciesCandidate> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val name = obj.optString("scientificName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        SpeciesCandidate(
            scientificName = name,
            commonName = obj.optString("commonName").takeIf { it.isNotBlank() },
            genus = obj.optString("genus").takeIf { it.isNotBlank() },
            score = obj.optDouble("score", 0.0).let { if (it.isFinite()) it else 0.0 }
        )
    }
}

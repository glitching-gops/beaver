package com.gops.spatialmapper.identify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing is the part of the Pl@ntNet client that can be wrong silently.
 *
 * A network failure is loud; picking the wrong JSON field is not — it produces a plausible-looking
 * candidate list with a missing common name or a genus that is subtly wrong, which then gets written
 * into a permanent field record. These tests pin the response shape against a body trimmed from a real
 * `/v2/identify` response, so a field rename upstream fails here rather than in a survey.
 */
class PlantNetParsingTest {

    /**
     * Trimmed from a genuine response: three results, the first with a common name, the second
     * without one (a real and common case — plenty of species have no vernacular name), and the third
     * with an empty commonNames array rather than a missing key.
     */
    private val realisticResponse = """
        {
          "query": { "project": "k-indian-subcontinent", "images": ["hash"] },
          "language": "en",
          "preferedReferential": "k-indian-subcontinent",
          "bestMatch": "Mangifera indica L.",
          "results": [
            {
              "score": 0.87421,
              "species": {
                "scientificNameWithoutAuthor": "Mangifera indica",
                "scientificNameAuthorship": "L.",
                "genus": {
                  "scientificNameWithoutAuthor": "Mangifera",
                  "scientificNameAuthorship": "",
                  "scientificName": "Mangifera"
                },
                "family": {
                  "scientificNameWithoutAuthor": "Anacardiaceae",
                  "scientificName": "Anacardiaceae"
                },
                "commonNames": ["Mango", "Indian mango"],
                "scientificName": "Mangifera indica L."
              },
              "gbif": { "id": "3190638" },
              "powo": { "id": "76652-1" }
            },
            {
              "score": 0.0714,
              "species": {
                "scientificNameWithoutAuthor": "Mangifera sylvatica",
                "scientificNameAuthorship": "Roxb.",
                "genus": {
                  "scientificNameWithoutAuthor": "Mangifera",
                  "scientificName": "Mangifera"
                },
                "family": { "scientificNameWithoutAuthor": "Anacardiaceae" },
                "scientificName": "Mangifera sylvatica Roxb."
              }
            },
            {
              "score": 0.0102,
              "species": {
                "scientificNameWithoutAuthor": "Bouea oppositifolia",
                "genus": { "scientificNameWithoutAuthor": "Bouea" },
                "commonNames": [],
                "scientificName": "Bouea oppositifolia (Roxb.) Meisn."
              }
            }
          ],
          "version": "2025-01-17 (7.3)",
          "remainingIdentificationRequests": 498
        }
    """.trimIndent()

    @Test
    fun `parses every result in rank order`() {
        val candidates = parsePlantNetResponse(realisticResponse)

        assertEquals(3, candidates.size)
        assertEquals("Mangifera indica", candidates[0].scientificName)
        assertEquals("Mangifera sylvatica", candidates[1].scientificName)
        assertEquals("Bouea oppositifolia", candidates[2].scientificName)
        // Pl@ntNet returns results already ranked; the parser must not reorder them.
        assertTrue(candidates[0].score > candidates[1].score)
        assertTrue(candidates[1].score > candidates[2].score)
    }

    @Test
    fun `prefers the author-free scientific name`() {
        // "Mangifera indica L." is also present in the payload. Picking it would put botanical
        // authorship into every field record and every candidate row.
        val candidates = parsePlantNetResponse(realisticResponse)
        assertEquals("Mangifera indica", candidates[0].scientificName)
    }

    @Test
    fun `reads the first common name and tolerates absent ones`() {
        val candidates = parsePlantNetResponse(realisticResponse)
        assertEquals("Mango", candidates[0].commonName)
        assertNull("a missing commonNames key must not throw", candidates[1].commonName)
        assertNull("an empty commonNames array is not a name", candidates[2].commonName)
    }

    @Test
    fun `reads genus from the genus object`() {
        val candidates = parsePlantNetResponse(realisticResponse)
        assertEquals("Mangifera", candidates[0].genus)
        assertEquals("Bouea", candidates[2].genus)
    }

    @Test
    fun `keeps the raw score and exposes it as a percentage`() {
        val candidates = parsePlantNetResponse(realisticResponse)
        assertEquals(0.87421, candidates[0].score, 1e-6)
        assertEquals(87, candidates[0].confidencePercent)
        // Truncation, not rounding: 7.14% must never be shown as 7% when it is really 7.14 — but more
        // importantly a 0.999 score must not display as 100%, which reads as certainty.
        assertEquals(7, candidates[1].confidencePercent)
    }

    @Test
    fun `an empty results array yields no candidates`() {
        assertEquals(emptyList<SpeciesCandidate>(), parsePlantNetResponse("""{"results":[]}"""))
    }

    @Test
    fun `malformed or unexpected bodies degrade to empty rather than throwing`() {
        assertEquals(emptyList<SpeciesCandidate>(), parsePlantNetResponse(""))
        assertEquals(emptyList<SpeciesCandidate>(), parsePlantNetResponse("not json at all"))
        assertEquals(emptyList<SpeciesCandidate>(), parsePlantNetResponse("""{"error":"unauthorized"}"""))
        assertEquals(emptyList<SpeciesCandidate>(), parsePlantNetResponse("""{"results":"wrong type"}"""))
    }

    @Test
    fun `a result with no usable name is dropped rather than becoming a blank row`() {
        val body = """
            {"results":[
              {"score":0.5,"species":{"genus":{"scientificNameWithoutAuthor":"Ficus"}}},
              {"score":0.4,"species":{"scientificNameWithoutAuthor":"Ficus religiosa"}}
            ]}
        """.trimIndent()
        val candidates = parsePlantNetResponse(body)
        assertEquals(1, candidates.size)
        assertEquals("Ficus religiosa", candidates[0].scientificName)
    }

    @Test
    fun `falls back to the full scientific name when the author-free field is missing`() {
        val body = """{"results":[{"score":0.9,"species":{"scientificName":"Neem tree L."}}]}"""
        assertEquals("Neem tree L.", parsePlantNetResponse(body).single().scientificName)
    }

    // --- Storage round trip ---------------------------------------------------------------------

    @Test
    fun `candidates survive a round trip through the stored json blob`() {
        val original = parsePlantNetResponse(realisticResponse)
        val restored = decodeCandidates(encodeCandidates(original))

        assertEquals(original.size, restored.size)
        original.zip(restored).forEach { (before, after) ->
            assertEquals(before.scientificName, after.scientificName)
            assertEquals(before.commonName, after.commonName)
            assertEquals(before.genus, after.genus)
            assertEquals(before.score, after.score, 1e-9)
        }
    }

    @Test
    fun `decoding a null or corrupt blob yields an empty list`() {
        assertEquals(emptyList<SpeciesCandidate>(), decodeCandidates(null))
        assertEquals(emptyList<SpeciesCandidate>(), decodeCandidates(""))
        assertEquals(emptyList<SpeciesCandidate>(), decodeCandidates("[{\"scientificName\":"))
    }

    @Test
    fun `confidence percent is clamped to a sane range`() {
        val absurd = SpeciesCandidate("Test species", null, null, score = 12.0)
        assertEquals(100, absurd.confidencePercent)
        val negative = SpeciesCandidate("Test species", null, null, score = -1.0)
        assertEquals(0, negative.confidencePercent)
    }
}

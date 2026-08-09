package com.gops.spatialmapper.identify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.gops.spatialmapper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val TAG = "SpatialMapperPlantNet"

/**
 * The Pl@ntNet flora ("project") the app identifies against.
 *
 * `k-indian-subcontinent` is Pl@ntNet's Indian Subcontinent regional flora (~7.5k native species).
 * Constraining the referential matters a lot for accuracy: against the world flora, a common Indian
 * street tree competes with every visually similar species on the planet, and the top-5 fills up with
 * neotropical lookalikes. Change this one constant to re-target the app to another region.
 */
const val PLANTNET_PROJECT = "k-indian-subcontinent"

/** How many ranked candidates to ask for. The UI shows five; there is no reason to download more. */
private const val CANDIDATE_COUNT = 5

private const val PLANTNET_BASE_URL = "https://my-api.plantnet.org/v2/identify"

/**
 * Longest side, in pixels, that the close-up is downscaled to before upload.
 *
 * Pl@ntNet accepts up to 50 MB, so this is not an API limit — it is a field-usability one. A raw phone
 * JPEG is several megabytes and this app is used outdoors on mobile data; 1600 px is comfortably above
 * what the classifier needs and turns a minute-long upload into a couple of seconds. The full-size
 * file stays on disk untouched as the attempt's audit photo.
 */
private const val UPLOAD_MAX_SIDE_PIXELS = 1600

private const val UPLOAD_JPEG_QUALITY = 85

private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 45_000

/** What went wrong, mapped to something an operator standing in a field can act on. */
enum class IdentificationErrorKind {
    /** No usable network. Expected, not exceptional — identification is a "whenever convenient" action. */
    NO_CONNECTIVITY,

    /** Pl@ntNet rejected the image or found nothing in this flora (HTTP 404). */
    NO_MATCH,

    /** The photo was unusable — unreadable, empty, or refused as not-an-image (HTTP 400/413). */
    INVALID_IMAGE,

    /** Daily quota exhausted (HTTP 429). */
    RATE_LIMITED,

    /** Key missing, malformed, or rejected (HTTP 401/403, or no key configured at all). */
    AUTH,

    /** Pl@ntNet itself is unhappy (HTTP 5xx). */
    SERVER,

    UNKNOWN
}

sealed interface IdentificationResult {
    data class Success(val candidates: List<SpeciesCandidate>) : IdentificationResult
    data class Failure(val kind: IdentificationErrorKind, val message: String) : IdentificationResult
}

/**
 * True when the device currently has a validated internet-capable network.
 *
 * Only a hint: it can be true and the request can still fail (captive portal, dead backhaul), which is
 * why [identifyPlant] also maps [UnknownHostException]/[IOException] to [IdentificationErrorKind.NO_CONNECTIVITY].
 * The point of checking up front is to fail in a quarter of a second with the honest message rather
 * than after a 15-second connect timeout.
 */
fun hasInternetConnection(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true // Can't tell — let the request itself decide rather than blocking on a guess.
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * The configured Pl@ntNet key, or null if the project is still on the committed placeholder.
 *
 * Injected by the Secrets Gradle Plugin from `PLANTNET_API_KEY` in the git-ignored local.properties —
 * the same mechanism as the Maps key, and for the same reason: no key is ever hardcoded in a committed
 * file. `local.defaults.properties` supplies the placeholder so the project still builds without one.
 */
fun plantNetApiKey(): String? =
    BuildConfig.PLANTNET_API_KEY
        .takeIf { it.isNotBlank() && it != "YOUR_PLANTNET_KEY_HERE" }

/**
 * Posts [photoFile] to Pl@ntNet and returns the ranked candidates.
 *
 * NO HTTP LIBRARY BY CHOICE. This is one multipart POST and one JSON parse. Retrofit + OkHttp +
 * a converter would be three artifacts compiled with a newer Kotlin than AGP 9's built-in 2.2.10
 * compiler can read metadata for — the exact trap that ruled out Coil earlier in this project — plus
 * R8 keep rules for the response models. `HttpURLConnection` and `org.json` are platform APIs with
 * neither problem, and the whole client is under 150 lines.
 *
 * `organs=auto` lets Pl@ntNet infer whether it's looking at a leaf, flower, fruit or bark. That is
 * deliberate: the operator is told they may shoot a plucked sample or the plant in place, and asking
 * them to also classify the organ would add a step for something the model does well on its own.
 *
 * Runs on [Dispatchers.IO]; safe to call straight from a composable's coroutine scope.
 */
suspend fun identifyPlant(
    context: Context,
    photoFile: File,
    apiKey: String?
): IdentificationResult = withContext(Dispatchers.IO) {
    if (apiKey.isNullOrBlank()) {
        return@withContext IdentificationResult.Failure(
            IdentificationErrorKind.AUTH,
            "No Pl@ntNet API key configured. Add PLANTNET_API_KEY to local.properties and rebuild."
        )
    }
    if (!hasInternetConnection(context)) {
        return@withContext IdentificationResult.Failure(
            IdentificationErrorKind.NO_CONNECTIVITY,
            "No internet connection — try again later."
        )
    }

    val imageBytes = compressForUpload(photoFile)
        ?: return@withContext IdentificationResult.Failure(
            IdentificationErrorKind.INVALID_IMAGE,
            "That photo couldn't be read — take another one."
        )

    val url = URL(
        "$PLANTNET_BASE_URL/$PLANTNET_PROJECT" +
            "?api-key=$apiKey&lang=en&nb-results=$CANDIDATE_COUNT"
    )

    var connection: HttpURLConnection? = null
    try {
        connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$MULTIPART_BOUNDARY")
            setRequestProperty("Accept", "application/json")
        }
        connection.writeMultipartBody(imageBytes, photoFile.name)

        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_OK) {
            val body = connection.inputStream.use { it.readAllText() }
            val candidates = parsePlantNetResponse(body)
            if (candidates.isEmpty()) {
                IdentificationResult.Failure(
                    IdentificationErrorKind.NO_MATCH,
                    "Pl@ntNet couldn't match this photo to any species in the Indian Subcontinent " +
                        "flora. Try a closer shot of a leaf, flower, or fruit."
                )
            } else {
                IdentificationResult.Success(candidates)
            }
        } else {
            val detail = connection.errorStream?.use { it.readAllText() }
            Log.w(TAG, "Pl@ntNet returned HTTP $status: ${detail?.take(400)}")
            IdentificationResult.Failure(errorKindFor(status), messageFor(status))
        }
    } catch (e: UnknownHostException) {
        Log.w(TAG, "Pl@ntNet host unreachable", e)
        IdentificationResult.Failure(
            IdentificationErrorKind.NO_CONNECTIVITY,
            "No internet connection — try again later."
        )
    } catch (e: SSLException) {
        Log.w(TAG, "TLS failure talking to Pl@ntNet", e)
        IdentificationResult.Failure(
            IdentificationErrorKind.NO_CONNECTIVITY,
            "Couldn't establish a secure connection — try again later."
        )
    } catch (e: IOException) {
        // Covers socket timeouts, which on a weak field signal are far more common than a clean
        // "no network" — so they get the same honest message rather than a scary stack-trace string.
        Log.w(TAG, "Pl@ntNet request failed", e)
        IdentificationResult.Failure(
            IdentificationErrorKind.NO_CONNECTIVITY,
            "Couldn't reach Pl@ntNet — the connection dropped or timed out. Try again later."
        )
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected failure identifying plant", e)
        IdentificationResult.Failure(
            IdentificationErrorKind.UNKNOWN,
            "Identification failed unexpectedly. Try again."
        )
    } finally {
        connection?.disconnect()
    }
}

/**
 * Extracts the ranked candidates from a Pl@ntNet `/v2/identify` response body.
 *
 * Pure and Android-free so it can be unit-tested against recorded response bodies — the parsing is
 * where a silent field mismatch would hide, and it is the one part of this client that can be checked
 * without a network. Unknown/missing optional fields degrade to null rather than throwing; a result
 * with no usable scientific name is dropped.
 */
fun parsePlantNetResponse(body: String): List<SpeciesCandidate> {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    val results = root.optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { index ->
        val result = results.optJSONObject(index) ?: return@mapNotNull null
        val species = result.optJSONObject("species") ?: return@mapNotNull null

        // scientificNameWithoutAuthor is the field to prefer: "scientificName" appends the botanical
        // authorship ("Mangifera indica L."), which is correct but reads as clutter in a field list.
        val name = species.optString("scientificNameWithoutAuthor")
            .takeIf { it.isNotBlank() }
            ?: species.optString("scientificName").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null

        // Genus comes from its own object, not from splitting the name — splitting breaks on hybrid
        // ("× Fatshedera lizei") and infraspecific names.
        val genus = species.optJSONObject("genus")
            ?.optString("scientificNameWithoutAuthor")
            ?.takeIf { it.isNotBlank() }

        val commonNames = species.optJSONArray("commonNames")
        val commonName = (0 until (commonNames?.length() ?: 0))
            .asSequence()
            .mapNotNull { commonNames?.optString(it) }
            .firstOrNull { it.isNotBlank() }

        val score = result.optDouble("score", 0.0).let { if (it.isFinite()) it else 0.0 }

        SpeciesCandidate(
            scientificName = name,
            commonName = commonName,
            genus = genus,
            score = score
        )
    }
}

private fun errorKindFor(status: Int): IdentificationErrorKind = when (status) {
    HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> IdentificationErrorKind.AUTH
    HttpURLConnection.HTTP_NOT_FOUND -> IdentificationErrorKind.NO_MATCH
    HttpURLConnection.HTTP_BAD_REQUEST,
    HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
    HttpURLConnection.HTTP_UNSUPPORTED_TYPE -> IdentificationErrorKind.INVALID_IMAGE
    429 -> IdentificationErrorKind.RATE_LIMITED
    in 500..599 -> IdentificationErrorKind.SERVER
    else -> IdentificationErrorKind.UNKNOWN
}

private fun messageFor(status: Int): String = when (errorKindFor(status)) {
    IdentificationErrorKind.AUTH ->
        "Pl@ntNet rejected the API key. Check PLANTNET_API_KEY in local.properties."
    IdentificationErrorKind.NO_MATCH ->
        "Pl@ntNet couldn't match this photo to any species in the Indian Subcontinent flora. " +
            "Try a closer shot of a leaf, flower, or fruit."
    IdentificationErrorKind.INVALID_IMAGE ->
        "Pl@ntNet couldn't use that image. Try a sharper, closer photo."
    IdentificationErrorKind.RATE_LIMITED ->
        "Pl@ntNet's daily request limit has been reached. Try again tomorrow, or use a different key."
    IdentificationErrorKind.SERVER ->
        "Pl@ntNet is having trouble right now (server error $status). Try again later."
    else -> "Identification failed (HTTP $status). Try again."
}

// --- Multipart plumbing -------------------------------------------------------------------------

private const val MULTIPART_BOUNDARY = "----SpatialMapperBoundary7Nk3xQ"
private const val CRLF = "\r\n"

/**
 * Writes the two-part body Pl@ntNet expects: the JPEG under `images` and the organ hint under
 * `organs`. Field order is not significant, but the counts must match — one `organs` value per image.
 */
private fun HttpURLConnection.writeMultipartBody(imageBytes: ByteArray, fileName: String) {
    DataOutputStream(BufferedOutputStream(outputStream)).use { out ->
        out.writeBytes("--$MULTIPART_BOUNDARY$CRLF")
        out.writeBytes("Content-Disposition: form-data; name=\"organs\"$CRLF$CRLF")
        out.writeBytes("auto$CRLF")

        out.writeBytes("--$MULTIPART_BOUNDARY$CRLF")
        out.writeBytes(
            "Content-Disposition: form-data; name=\"images\"; filename=\"$fileName\"$CRLF"
        )
        out.writeBytes("Content-Type: image/jpeg$CRLF$CRLF")
        out.write(imageBytes)
        out.writeBytes(CRLF)

        out.writeBytes("--$MULTIPART_BOUNDARY--$CRLF")
        out.flush()
    }
}

private fun InputStream.readAllText(): String = bufferedReader().use { it.readText() }

/**
 * Decodes [file] downsampled so its longest side is roughly [UPLOAD_MAX_SIDE_PIXELS], then re-encodes
 * it as JPEG in memory. Returns null when the file is missing or not a decodable image.
 *
 * Uses `inSampleSize` (powers of two) rather than a scaled `createScaledBitmap`, so the full-size
 * bitmap is never allocated — the same technique the history thumbnails use, and the reason a 12 MP
 * capture doesn't risk an OOM here.
 */
private fun compressForUpload(file: File): ByteArray? {
    if (!file.exists() || file.length() == 0L) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sampleSize * 2) >= UPLOAD_MAX_SIDE_PIXELS) sampleSize *= 2

        val bitmap: Bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null

        ByteArrayOutputStream().use { buffer ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, UPLOAD_JPEG_QUALITY, buffer)
            bitmap.recycle()
            buffer.toByteArray()
        }
    }.onFailure { Log.w(TAG, "Could not prepare ${file.name} for upload", it) }.getOrNull()
}

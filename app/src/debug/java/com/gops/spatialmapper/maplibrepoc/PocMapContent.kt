package com.gops.spatialmapper.maplibrepoc

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.maps.android.SphericalUtil
import com.gops.spatialmapper.map.generateCanopyPolygon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import com.google.android.gms.maps.model.LatLng as GmsLatLng

/**
 * Everything the proof of concept draws: an offline Sentinel-2 basemap, one marker, one canopy ring,
 * and the bounds that frame them.
 *
 * THROWAWAY. Stage A proved the engine renders raster tiles and that the app's geometry ports.
 * Stage B swaps Stage A's online OpenStreetMap tiles for a genuinely offline satellite basemap,
 * a local MBTiles file built by `tools/basemap/make_sentinel2_mbtiles.py`, to prove the whole
 * offline pipeline end to end. There is no online source left in this file, and no http(s) URL
 * anywhere in the style.
 *
 * THE TYPE COLLISION, up front, because it is the single most visible porting cost: MapLibre and the
 * Maps SDK both ship a class called `LatLng`. This file imports MapLibre's plainly and aliases
 * Google's to [GmsLatLng]. A real migration would do the reverse in most files, or introduce one
 * project-owned coordinate type — but note that nothing about the alias is *hard*, and see
 * [canopyRingAsGeoJson] for why the geometry underneath it needs no changes at all.
 */

// ---------------------------------------------------------------------------------------------
// Offline basemap
// ---------------------------------------------------------------------------------------------

/**
 * The archive the PoC renders. Must match the default output name of
 * `tools/basemap/make_sentinel2_mbtiles.py`, so the file can be pushed without renaming.
 *
 * MBTiles rather than PMTiles: both file sources are compiled into MapLibre Android 13.6.1, but
 * MBTiles takes a plain `mbtiles:///absolute/path` URL and looks tiles up in SQLite by (z, x, y),
 * and its metadata can be read here with the platform's own SQLite (see [readBasemapInfo]) to put
 * the scene and the attribution year on screen. The script's docstring has the full comparison.
 */
const val BASEMAP_FILE_NAME = "sentinel2_basemap.mbtiles"

/** The script writes 256 px tiles. MapLibre's raster default is 512, so this must be explicit. */
private const val BASEMAP_TILE_SIZE = 256

/**
 * What the PoC found where the basemap should be. Drives both the style (with or without a raster
 * layer) and the on-screen message, so a missing file is always *said*, never a silent blank map.
 */
sealed interface BasemapStatus {
    /** Absolute on-device path of the expected archive. */
    val path: String

    data class Ready(override val path: String, val info: BasemapInfo) : BasemapStatus
    data class Missing(override val path: String) : BasemapStatus
    data class Unusable(override val path: String, val reason: String) : BasemapStatus
}

/** What the archive's MBTiles `metadata` table says about itself. */
data class BasemapInfo(
    /** The legally required Copernicus attribution, year included. Shown permanently on the map. */
    val attribution: String,
    val sceneId: String?,
    val acquired: String?,
    val minZoom: String?,
    val maxZoom: String?,
    val tileCount: Long
)

/**
 * Looks for [BASEMAP_FILE_NAME] in the app's own external files directory, which on a phone is
 * `/sdcard/Android/data/com.gops.spatialmapper/files/`.
 *
 * That directory was chosen because it is the one place both sides can reach with NO storage
 * permission: `adb push` can write to it, and the app can read its own directory there
 * unconditionally. No READ_EXTERNAL_STORAGE / READ_MEDIA_* / MANAGE_EXTERNAL_STORAGE is involved,
 * and the debug manifest does not change.
 *
 * `getExternalFilesDir` also CREATES the directory, which is why the PoC should be opened once before
 * the first push: adb will not create `Android/data/<package>/files/` for you. Uninstalling the
 * app deletes the directory and the archive with it; a normal reinstall/Run keeps both.
 *
 * Every failure becomes a [BasemapStatus] for the screen to explain. Nothing here throws.
 */
fun locateBasemap(context: Context): BasemapStatus {
    val dir = context.getExternalFilesDir(null)
        ?: return BasemapStatus.Unusable(
            adbPushDestination(context),
            "App-specific external storage is not available on this device right now."
        )
    val file = File(dir, BASEMAP_FILE_NAME)
    val path = file.absolutePath
    if (!file.exists()) return BasemapStatus.Missing(path)
    if (!file.canRead()) return BasemapStatus.Unusable(path, "The file exists but the app cannot read it.")
    if (!hasSqliteHeader(file)) {
        return BasemapStatus.Unusable(
            path,
            "This is not an MBTiles (SQLite) file, or the copy is incomplete. Push it again."
        )
    }
    return try {
        BasemapStatus.Ready(path, readBasemapInfo(file))
    } catch (error: Exception) {
        BasemapStatus.Unusable(path, "Could not read the MBTiles metadata: ${error.message}")
    }
}

/** Where to `adb push` to. The /sdcard spelling of the same directory [locateBasemap] reads. */
fun adbPushDestination(context: Context): String =
    "/sdcard/Android/data/${context.packageName}/files/$BASEMAP_FILE_NAME"

/**
 * MapLibre's MBTiles file source takes `mbtiles://` followed by an ABSOLUTE path, hence three
 * slashes in practice. Relative paths are rejected ("MBTilesFileSource only supports absolute path
 * urls"). The source percent-decodes the path; the package path contains nothing that needs encoding.
 */
private fun mbtilesUrl(absolutePath: String): String = "mbtiles://$absolutePath"

private val SQLITE_MAGIC = "SQLite format 3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

/** Every SQLite file starts with this 16-byte string; a truncated or wrong file usually does not. */
private fun hasSqliteHeader(file: File): Boolean {
    if (file.length() < 100) return false // the SQLite header alone is 100 bytes
    val header = ByteArray(SQLITE_MAGIC.size)
    file.inputStream().use { if (it.read(header) != header.size) return false }
    return header.contentEquals(SQLITE_MAGIC)
}

/**
 * Reads the MBTiles `metadata` table with android.database.sqlite, the platform's own SQLite, so
 * no dependency and no parser. Read-only and without localized collators, so opening it never
 * writes a journal or an `android_metadata` table into the archive.
 *
 * The attribution comes from the file rather than being hard-coded here, because its YEAR must be
 * the acquisition year of the imagery, and only the file knows which scene it holds.
 */
private fun readBasemapInfo(file: File): BasemapInfo {
    val values = HashMap<String, String>()
    var tileCount = 0L
    SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    ).use { db ->
        db.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
            while (cursor.moveToNext()) {
                values[cursor.getString(0)] = cursor.getString(1).orEmpty()
            }
        }
        tileCount = DatabaseUtils.queryNumEntries(db, "tiles")
    }
    require(tileCount > 0) { "the archive contains no tiles" }

    val acquired = values["sentinel2_acquired"]
    val attribution = values["attribution"]?.takeIf { it.isNotBlank() }
        ?: "Contains modified Copernicus Sentinel data ${acquired?.take(4) ?: "[year unknown]"}"
    return BasemapInfo(
        attribution = attribution,
        sceneId = values["sentinel2_scene_id"],
        acquired = acquired,
        minZoom = values["minzoom"],
        maxZoom = values["maxzoom"],
        tileCount = tileCount
    )
}

// ---------------------------------------------------------------------------------------------
// Style, source and layer ids
// ---------------------------------------------------------------------------------------------

private const val SOURCE_BASEMAP = "poc-basemap"
private const val SOURCE_CANOPY = "poc-canopy"
private const val SOURCE_MARKER = "poc-marker"
private const val LAYER_BACKGROUND = "poc-background"
private const val LAYER_BASEMAP = "poc-basemap-layer"
private const val LAYER_CANOPY_FILL = "poc-canopy-fill"
private const val LAYER_CANOPY_OUTLINE = "poc-canopy-outline"
private const val LAYER_MARKER = "poc-marker-layer"
private const val IMAGE_MARKER = "poc-marker-icon"

/**
 * The minimum valid MapLibre style. Everything else is added programmatically on top.
 *
 * Deliberately not a hosted style URL: the real screens will add and remove layers at runtime (a
 * canopy per session, a scout trail, a survey overlay), so the PoC should exercise the same
 * build-it-in-code path rather than the load-a-URL path that would tell us nothing.
 *
 * No `glyphs` or `sprite` entry, and that matters more in Stage B than it did in Stage A: either
 * would be a URL MapLibre fetches. The symbol layer draws an icon from a Bitmap and no text, so
 * neither is needed, and the style contains no network reference at all.
 */
private const val EMPTY_STYLE_JSON = """{"version":8,"name":"SpatialMapper PoC","sources":{},"layers":[]}"""

/**
 * Neutral dark ground under the imagery. The script clips the archive to its bbox (transparent
 * outside), so this is what shows past the imagery edge, and it is the whole map when there is no
 * basemap, which keeps "no basemap" visually distinct from "basemap still loading".
 */
private const val BACKGROUND_ARGB = 0xFF2B3036.toInt()

// ---------------------------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------------------------

/**
 * The centre of the Stage B test bbox, the script's example `--bbox 13.1175 77.7996 13.1375
 * 77.8196`, so the marker and canopy sit ON the imagery and overlay alignment can actually be judged.
 *
 * Stage A used the Bengaluru city-centre coordinate the unit tests share (12.9716, 77.5946). That is
 * ~30 km from this field site, so it would have put the geometry over the empty background, which
 * proves nothing. The lon/lat-order smoke test survives the move: with the axes swapped this point
 * would land near Svalbard, nowhere near the imagery.
 */
val POC_MARKER_POSITION = GmsLatLng(13.1275, 77.8096)

/** Canopy centre, ~120 m from the marker, so fit-to-bounds has two separated things to frame. */
private val POC_CANOPY_CENTER = SphericalUtil.computeOffset(POC_MARKER_POSITION, 120.0, 60.0)

/** Large enough to be unmistakable at street zoom; [MAX_CANOPY_RADIUS_METERS] is 40 m. */
private const val POC_CANOPY_RADIUS_METERS = 40.0

/** Fixed so the silhouette is identical on every launch and a rendering change is obvious. */
private const val POC_CANOPY_SEED = 1_756_900_000_000L

/**
 * THE POINT OF THE EXERCISE: the ring comes from the app's real [generateCanopyPolygon], imported
 * from `src/main`, called with no modification whatsoever.
 *
 * That function places vertices with `SphericalUtil.computeOffset`, so it is doing spherical
 * trigonometry on doubles and returning a list of coordinate pairs. It touches no rendering API. The
 * only thing tying it to Google Maps is the *container type* of those pairs — `GmsLatLng`, a value
 * holder with a `latitude` and a `longitude` — which is why porting it costs the one `map` call
 * below and nothing else.
 */
val pocCanopyRing: List<GmsLatLng> = generateCanopyPolygon(
    center = POC_CANOPY_CENTER,
    baseRadiusMeters = POC_CANOPY_RADIUS_METERS,
    seed = POC_CANOPY_SEED
)

// ---------------------------------------------------------------------------------------------
// Conversion
// ---------------------------------------------------------------------------------------------

/**
 * Google Maps coordinate -> MapLibre coordinate. Both take (latitude, longitude) in that order, so
 * this one is safe.
 */
fun GmsLatLng.toMapLibre(): LatLng = LatLng(latitude, longitude)

/**
 * Google Maps coordinate -> GeoJSON position. This one is NOT safe, and is the reason it lives in
 * exactly one place: [Point.fromLngLat] takes LONGITUDE FIRST. Same trap the exporter already has a
 * dedicated unit test for; getting it backwards yields a file that parses and plots in the wrong
 * hemisphere, or here, a marker nowhere near the imagery.
 */
private fun GmsLatLng.toGeoJsonPoint(): Point = Point.fromLngLat(longitude, latitude)

/**
 * The canopy ring as a GeoJSON Polygon.
 *
 * [generateCanopyPolygon] returns an OPEN ring — 24 vertices, first != last — because that is what
 * the Maps SDK's Polygon wants. GeoJSON requires a linear ring to be explicitly closed, so the first
 * position is appended. Exactly the same adjustment `SessionExport.closedRing` already makes for the
 * export.
 */
fun canopyRingAsGeoJson(ring: List<GmsLatLng> = pocCanopyRing): Polygon {
    val positions = ring.map { it.toGeoJsonPoint() }
    val closed = positions + positions.first()
    return Polygon.fromLngLats(listOf(closed))
}

// ---------------------------------------------------------------------------------------------
// Style assembly
// ---------------------------------------------------------------------------------------------

/**
 * Builds the complete PoC style, bottom to top: background, offline basemap (only when [basemap] is
 * [BasemapStatus.Ready]), canopy fill, canopy outline, marker.
 *
 * Without a basemap the geometry still draws, so marker, canopy, fit-to-bounds and zoom keep
 * working while the screen explains what is missing.
 *
 * Layer order is insertion order, which is the MapLibre equivalent of the `zIndex` the current Google
 * Maps screens set per-object.
 */
fun buildPocStyle(markerIcon: Bitmap, basemap: BasemapStatus): Style.Builder {
    val builder = Style.Builder()
        .fromJson(EMPTY_STYLE_JSON)
        .withImage(IMAGE_MARKER, markerIcon)
        .withLayer(
            BackgroundLayer(LAYER_BACKGROUND).withProperties(PropertyFactory.backgroundColor(BACKGROUND_ARGB))
        )

    if (basemap is BasemapStatus.Ready) {
        // MapLibre reads the archive's own metadata table as TileJSON: bounds, min/max zoom, format
        // and the attribution string, which also feeds MapLibre's built-in attribution control.
        // Past the archive's maxzoom (z14, native 10 m) MapLibre over-zooms the z14 tiles itself.
        builder
            .withSource(RasterSource(SOURCE_BASEMAP, mbtilesUrl(basemap.path), BASEMAP_TILE_SIZE))
            .withLayer(RasterLayer(LAYER_BASEMAP, SOURCE_BASEMAP))
    }

    val canopy = GeoJsonSource(SOURCE_CANOPY, canopyRingAsGeoJson())
    val marker = GeoJsonSource(
        SOURCE_MARKER,
        Feature.fromGeometry(POC_MARKER_POSITION.toGeoJsonPoint())
    )

    return builder
        .withSources(canopy, marker)
        .withLayer(
            FillLayer(LAYER_CANOPY_FILL, SOURCE_CANOPY).withProperties(
                // Same colours the app already uses for a canopy, so the PoC looks like the thing it
                // is standing in for. MapLibre takes an ARGB int here just like the Maps SDK does.
                PropertyFactory.fillColor(CANOPY_FILL_ARGB),
                PropertyFactory.fillOpacity(0.45f)
            )
        )
        .withLayer(
            LineLayer(LAYER_CANOPY_OUTLINE, SOURCE_CANOPY).withProperties(
                PropertyFactory.lineColor(CANOPY_STROKE_ARGB),
                PropertyFactory.lineWidth(3f)
            )
        )
        .withLayer(
            SymbolLayer(LAYER_MARKER, SOURCE_MARKER).withProperties(
                PropertyFactory.iconImage(IMAGE_MARKER),
                // Without allowOverlap a symbol can be silently collision-hidden, which would look
                // exactly like "the marker failed to render" — a false negative this stage cannot
                // afford.
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER)
            )
        )
}

/** Opaque ints rather than the app's Compose [androidx.compose.ui.graphics.Color] values, because
 *  MapLibre's PropertyFactory takes a plain ARGB int. Kept numerically identical to CanopyStyle. */
private const val CANOPY_FILL_ARGB = 0xFF4CAF50.toInt()
private const val CANOPY_STROKE_ARGB = 0xFF2E7D32.toInt()

// ---------------------------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------------------------

/**
 * Bounds containing the marker and every canopy vertex — the MapLibre analogue of the
 * `LatLngBounds.builder()` auto-fit the Global Map tab already performs.
 */
fun pocBounds(): LatLngBounds {
    val builder = LatLngBounds.Builder()
    builder.include(POC_MARKER_POSITION.toMapLibre())
    pocCanopyRing.forEach { builder.include(it.toMapLibre()) }
    return builder.build()
}

// ---------------------------------------------------------------------------------------------
// Marker icon
// ---------------------------------------------------------------------------------------------

/**
 * The marker bitmap: a red disc inside a white ring, drawn the same way the app's existing icon
 * factories draw theirs.
 *
 * PORTING NOTE, which is the reason this is hand-drawn rather than a vector: `scoutPositionIcon()`
 * and `removedTreeIcon()` in src/main are already pure Canvas drawing that ends with a single
 * `BitmapDescriptorFactory.fromBitmap(bitmap)` call. MapLibre wants the raw [Bitmap]. So migrating
 * those factories means deleting their last line and changing the return type — the drawing code
 * itself is untouched.
 */
fun pocMarkerBitmap(): Bitmap {
    val sizePx = 56
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f

    val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 2f, shadow)

    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 6f, ring)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(211, 47, 47)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - 13f, fill)

    return bitmap
}

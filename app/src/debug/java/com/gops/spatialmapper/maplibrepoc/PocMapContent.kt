package com.gops.spatialmapper.maplibrepoc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.maps.android.SphericalUtil
import com.gops.spatialmapper.map.generateCanopyPolygon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import com.google.android.gms.maps.model.LatLng as GmsLatLng

/**
 * Everything the Stage A proof of concept draws: a raster basemap, one marker, one canopy ring, and
 * the bounds that frame them.
 *
 * THROWAWAY. Nothing here is a design for the real migration — it exists to answer four questions
 * before any real screen is touched: does the engine render raster tiles, does a marker draw, does
 * the app's existing geometry code produce something MapLibre can consume, and do the camera
 * operations the current screens rely on have equivalents.
 *
 * THE TYPE COLLISION, up front, because it is the single most visible porting cost: MapLibre and the
 * Maps SDK both ship a class called `LatLng`. This file imports MapLibre's plainly and aliases
 * Google's to [GmsLatLng]. A real migration would do the reverse in most files, or introduce one
 * project-owned coordinate type — but note that nothing about the alias is *hard*, and see
 * [canopyRingAsGeoJson] for why the geometry underneath it needs no changes at all.
 */

// ---------------------------------------------------------------------------------------------
// Temporary tile source
// ---------------------------------------------------------------------------------------------

/**
 * OpenStreetMap standard raster tiles. TEMPORARY, for this proof of concept only.
 *
 * Chosen because it needs no API key or account, and because the OSMF Tile Usage Policy — read at
 * https://operations.osmfoundation.org/policies/tiles/ — explicitly contemplates native apps rather
 * than banning them, provided a short list of obligations is met. Each one, and how this file meets
 * it:
 *
 *  - "Use exactly https://tile.openstreetmap.org/{z}/{x}/{y}.png": that URL, HTTPS, no subdomains.
 *  - "Send a valid HTTP User-Agent that clearly identifies your application ... Do not use a library
 *    default User-Agent. Traffic that uses these defaults will be blocked." MapLibre already sends
 *    `com.gops.spatialmapper/<versionName> (<versionCode>) MapLibre Android/13.6.1 (...) Android/<sdk>
 *    (<abi>)` — verified by decompiling HttpIdentifier/HttpRequestImpl in the 13.6.1 AAR, not
 *    assumed. That names the app, so no OkHttp interceptor is needed here. If the SDK is ever
 *    swapped, re-check this first: it is the requirement that gets traffic blocked.
 *  - "Provide visible licence attribution": [OSM_ATTRIBUTION] is set on the TileSet below (which
 *    feeds MapLibre's own attribution control) AND rendered as always-visible text by the screen.
 *  - "Cache tiles locally according to HTTP caching headers": MapLibre's ambient cache does this by
 *    default and nothing here disables it.
 *
 * WHAT THIS SOURCE CANNOT DO, and it matters for the next stage: the same policy forbids bulk
 * download and prefetching outright. Offline packaging — the entire point of the migration — is
 * therefore off the table for OSM's tile servers. Stage B needs a source whose licence permits
 * caching and redistribution, and that is a harder problem than the engine work. This is also a
 * street map, not imagery; proving raster tiles render is engine-identical either way, but the real
 * basemap has to be satellite.
 */
const val OSM_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

/** Required by the policy, and shown to the operator rather than buried behind a control. */
const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

/** OSM standard tiles are 256 px and published to z19. */
private const val OSM_TILE_SIZE = 256
private const val OSM_MIN_ZOOM = 0f
private const val OSM_MAX_ZOOM = 19f

// ---------------------------------------------------------------------------------------------
// Style, source and layer ids
// ---------------------------------------------------------------------------------------------

private const val SOURCE_BASEMAP = "poc-basemap"
private const val SOURCE_CANOPY = "poc-canopy"
private const val SOURCE_MARKER = "poc-marker"
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
 * No `glyphs` entry is needed because the symbol layer draws an icon and no text; adding text later
 * requires a glyphs endpoint, which is its own offline problem.
 */
private const val EMPTY_STYLE_JSON = """{"version":8,"name":"SpatialMapper PoC","sources":{},"layers":[]}"""

// ---------------------------------------------------------------------------------------------
// Test fixtures
// ---------------------------------------------------------------------------------------------

/**
 * The coordinate every unit test in this project already uses (Bengaluru). Reused deliberately: if
 * the marker lands somewhere else on screen, the lon/lat ordering is wrong, and that is the classic
 * failure this whole file is meant to smoke out early.
 */
val POC_MARKER_POSITION = GmsLatLng(12.9716, 77.5946)

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
 *
 * Concretely, a real migration has two options and neither is expensive: keep `play-services-maps`
 * on the classpath purely for the `LatLng` value type, or replace it with a project-owned data class
 * and let the compiler find the call sites.
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
 * hemisphere, or here, a marker in the Indian Ocean.
 */
private fun GmsLatLng.toGeoJsonPoint(): Point = Point.fromLngLat(longitude, latitude)

/**
 * The canopy ring as a GeoJSON Polygon.
 *
 * [generateCanopyPolygon] returns an OPEN ring — 24 vertices, first != last — because that is what
 * the Maps SDK's Polygon wants. GeoJSON requires a linear ring to be explicitly closed, so the first
 * position is appended. Exactly the same adjustment `SessionExport.closedRing` already makes for the
 * export, which is a second small piece of evidence that the geometry layer is engine-agnostic and
 * only its edges need attention.
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
 * Builds the complete PoC style: raster basemap at the bottom, canopy fill, canopy outline, marker
 * on top.
 *
 * Layer order is insertion order, which is the MapLibre equivalent of the `zIndex` the current Google
 * Maps screens set per-object. Worth noting for the migration: MapLibre has no per-feature z-index —
 * ordering is a property of the layer stack, so the existing zIndex constants become "which order do
 * I add these layers in", which is if anything easier to reason about.
 */
fun buildPocStyle(markerIcon: Bitmap): Style.Builder {
    val basemap = RasterSource(
        SOURCE_BASEMAP,
        TileSet(TILEJSON_VERSION, OSM_TILE_URL).apply {
            // Feeds MapLibre's built-in attribution control. The screen also draws it as plain text,
            // because the policy says attribution must not be hidden behind a control.
            attribution = OSM_ATTRIBUTION
            minZoom = OSM_MIN_ZOOM
            maxZoom = OSM_MAX_ZOOM
        },
        OSM_TILE_SIZE
    )

    val canopy = GeoJsonSource(SOURCE_CANOPY, canopyRingAsGeoJson())
    val marker = GeoJsonSource(
        SOURCE_MARKER,
        Feature.fromGeometry(POC_MARKER_POSITION.toGeoJsonPoint())
    )

    return Style.Builder()
        .fromJson(EMPTY_STYLE_JSON)
        .withImage(IMAGE_MARKER, markerIcon)
        .withSources(basemap, canopy, marker)
        .withLayer(RasterLayer(LAYER_BASEMAP, SOURCE_BASEMAP))
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

/** TileJSON spec version the TileSet declares. 2.2.0 is what MapLibre's own samples use. */
private const val TILEJSON_VERSION = "2.2.0"

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
 *
 * Note how close the API is: `LatLngBounds.Builder().include(...).build()` on both engines, and
 * `CameraUpdateFactory.newLatLngBounds(bounds, paddingPx)` on both. The fit-to-bounds behaviour
 * ports essentially verbatim.
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

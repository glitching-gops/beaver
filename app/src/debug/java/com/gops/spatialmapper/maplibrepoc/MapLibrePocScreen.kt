package com.gops.spatialmapper.maplibrepoc

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil

private const val TAG = "SpatialMapperMapLibrePoc"

/** Padding, in pixels, around the fit-to-bounds box. Matches the 56.dp the Global Map tab uses. */
private const val FIT_PADDING_PX = 140

/** Opening zoom, roughly the "a few blocks" framing the PoC content sits in. */
private const val INITIAL_ZOOM = 16.5

/**
 * Stage A proof of concept: does MapLibre render, and do the app's existing pieces port to it?
 *
 * NOT WIRED INTO THE APP. This screen is reachable only from [MapLibrePocActivity], which exists only
 * in the debug build. Nothing in `src/main` references it, and deleting `src/debug/` removes the whole
 * stage.
 *
 * =============================================================================================
 * LIFECYCLE WIRING — the part most likely to be subtly wrong, so it is spelled out.
 * =============================================================================================
 *
 * [MapView] is a View-based SDK component that owns a native renderer, a GL/Vulkan surface and a
 * network stack. It does not observe any lifecycle itself; the host must forward onCreate, onStart,
 * onResume, onPause, onStop, onDestroy and onLowMemory by hand. Getting this wrong does not fail
 * loudly at build time — it leaks a renderer, or crashes on the second visit to the screen.
 *
 * Four specific hazards, and how each is handled:
 *
 * 1. onCreate EXACTLY ONCE, FIRST. [MapView.onCreate] initialises the native map; calling it twice
 *    is undefined and calling anything else before it crashes. It is called here inside `remember`,
 *    at construction, so it is bound to the MapView's existence rather than to a lifecycle event
 *    that can repeat. The observer below therefore deliberately ignores ON_CREATE.
 *
 * 2. CATCH-UP ON ENTRY. This composable is normally entered while the host is already RESUMED. That
 *    is fine and requires no manual priming: LifecycleRegistry.addObserver syncs a newly-added
 *    observer up to the current state, replaying ON_CREATE -> ON_START -> ON_RESUME in order. The
 *    naive alternative — only handling future events — leaves a map that never gets onStart and
 *    renders nothing, which reads as "MapLibre is broken" rather than "the wiring is wrong".
 *
 * 3. THE DOUBLE-DESTROY. This is the real trap. On host destruction the observer fires ON_DESTROY
 *    (so the MapView is destroyed) and THEN the composition is disposed, so a naive
 *    `onDispose { mapView.onDestroy() }` destroys it a second time. But when the composable merely
 *    leaves composition while the host lives on, onDispose is the ONLY teardown that will ever
 *    happen — so it cannot simply be omitted either. [destroyed] resolves it: whichever path gets
 *    there first performs the teardown, the other becomes a no-op.
 *
 * 4. STEPPING DOWN CLEANLY. If disposal happens while the host is still RESUMED, going straight to
 *    onDestroy skips onPause/onStop. MapLibre tolerates that, but the renderer is being torn down
 *    from a running state. onDispose walks the states it is actually in, guarded by the host's
 *    current state so it never calls onPause on a map that was never resumed.
 *
 * onLowMemory has no Lifecycle event at all, so it arrives via a [ComponentCallbacks2] registered on
 * the Context and unregistered alongside everything else. Doing it here rather than overriding the
 * Activity keeps the screen self-contained — it can be hosted anywhere without the host knowing it
 * contains a map, which is what the eventual migration will need.
 *
 * onSaveInstanceState is deliberately NOT forwarded. Threading an Activity Bundle down into a
 * composable is awkward, and the thing it actually protects — the camera — is preserved here with
 * [rememberSaveable] instead. That survives both configuration change and process death, and is the
 * pattern the real screens should use, since they already hoist their camera state.
 */
@Composable
fun MapLibrePocScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera, preserved across rotation and process death. The PoC Activity is deliberately NOT
    // orientation-locked (unlike the rest of the app) so this is exercisable on a real device.
    var cameraLat by rememberSaveable { mutableDoubleStateOf(POC_MARKER_POSITION.latitude) }
    var cameraLon by rememberSaveable { mutableDoubleStateOf(POC_MARKER_POSITION.longitude) }
    var cameraZoom by rememberSaveable { mutableDoubleStateOf(INITIAL_ZOOM) }

    var status by remember { mutableStateOf("Creating map…") }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    val mapView = remember {
        // Idempotent and synchronized; safe to call per-composition. Must precede MapView().
        MapLibre.getInstance(context)

        // Tile HTTP tracing, on for the PoC because a blank map has several very different causes
        // and they are indistinguishable by eye: tiles 403'd for a bad User-Agent, tiles never
        // requested because the style failed, or a linkage error from the OkHttp version conflict
        // (arsceneview -> fuel drags in okhttp 5.0.0-alpha.14, while MapLibre compiled against
        // 4.12.0). With these on, logcat says which. Filter: `adb logcat -s Mbgl-HttpRequest`.
        HttpRequestUtil.setLogEnabled(true)
        HttpRequestUtil.setPrintRequestUrlOnFailure(true)

        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        // See hazard 3 above. Not @Volatile: every path that touches it — lifecycle dispatch and
        // composition disposal — runs on the main thread.
        var destroyed = false

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // ON_CREATE is intentionally absent: onCreate already ran at construction.
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> if (!destroyed) {
                    destroyed = true
                    mapView.onDestroy()
                }
                else -> Unit
            }
        }

        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() {
                Log.i(TAG, "onLowMemory -> MapView.onLowMemory()")
                mapView.onLowMemory()
            }
            override fun onTrimMemory(level: Int) = Unit
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        context.registerComponentCallbacks(memoryCallbacks)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memoryCallbacks)
            if (!destroyed) {
                destroyed = true
                // Hazard 4: step down through whatever states we are actually in.
                val state = lifecycleOwner.lifecycle.currentState
                if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        PocHeader(status = status)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                // The MapView is remembered above, so this hands back the same instance rather than
                // constructing one per composition — AndroidView must never own a view whose
                // lifecycle something else is already driving.
                factory = { mapView },
                update = { /* Everything is driven imperatively via the map callback below. */ }
            )

            // Attribution as plain, always-visible text. MapLibre's own attribution control is also
            // enabled (it reads the TileSet attribution), but the OSM policy says attribution must
            // not be hidden behind a control, and MapLibre's is an "i" button that opens a dialog.
            Text(
                text = OSM_ATTRIBUTION,
                fontSize = 11.sp,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }

        PocControls(
            onFitBounds = {
                val map = mapLibreMap ?: return@PocControls
                // runCatching mirrors the Global Map tab's defence: newLatLngBounds needs a measured
                // map and throws if the view still has zero size.
                runCatching {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(pocBounds(), FIT_PADDING_PX),
                        600
                    )
                }.onFailure {
                    Log.w(TAG, "fit-to-bounds rejected; centring instead", it)
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            POC_MARKER_POSITION.toMapLibre(),
                            INITIAL_ZOOM
                        )
                    )
                }
            },
            onZoomIn = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn()) },
            onZoomOut = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut()) },
            onMarker = {
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(POC_MARKER_POSITION.toMapLibre(), 18.0)
                )
            }
        )
    }

    // getMapAsync fires once the native map is ready. Keyed on the MapView so it is requested exactly
    // once per map instance, not on every recomposition.
    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            mapLibreMap = map
            status = "Map ready — loading style…"
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = false
            // Portrait-locked elsewhere in the app for sensor reasons; irrelevant here, but a
            // rotating map makes it harder to tell whether a bug is the camera or the gesture.
            map.uiSettings.isRotateGesturesEnabled = false

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(cameraLat, cameraLon))
                .zoom(cameraZoom)
                .build()

            map.setStyle(buildPocStyle(pocMarkerBitmap())) {
                status = "Style loaded — ${pocCanopyRing.size}-vertex ring + 1 marker"
                Log.i(TAG, "Style loaded with ${it.sources.size} sources / ${it.layers.size} layers")
            }

            // Persist the camera so rotation and process death do not reset the view.
            map.addOnCameraIdleListener {
                val position = map.cameraPosition
                cameraLat = position.target?.latitude ?: cameraLat
                cameraLon = position.target?.longitude ?: cameraLon
                cameraZoom = position.zoom
            }
        }
        onDispose { mapLibreMap = null }
    }
}

@Composable
private fun PocHeader(status: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "MapLibre proof of concept — Stage A",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Color.Black
            )
            Text(
                text = "Throwaway screen. Temporary OpenStreetMap raster tiles, not the final " +
                    "basemap. Not reachable from the app.",
                fontSize = 12.sp,
                color = Color.DarkGray
            )
            Spacer(Modifier.height(4.dp))
            Text(text = status, fontSize = 12.sp, color = Color(0xFF1565C0))
        }
    }
}

@Composable
private fun PocControls(
    onFitBounds: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onMarker: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Button(onClick = onFitBounds, modifier = Modifier.fillMaxWidth()) {
            Text("Fit to bounds (marker + canopy)")
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = onZoomOut, modifier = Modifier.weight(1f)) { Text("Zoom −") }
            OutlinedButton(onClick = onZoomIn, modifier = Modifier.weight(1f)) { Text("Zoom +") }
            OutlinedButton(onClick = onMarker, modifier = Modifier.weight(1f)) { Text("Marker") }
        }
    }
}

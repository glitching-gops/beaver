package com.gops.spatialmapper.maplibrepoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.gops.spatialmapper.ui.theme.SpatialMapperTheme

/**
 * The temporary way in to the Stage A proof of concept.
 *
 * A SEPARATE LAUNCHER ACTIVITY, declared only in `src/debug/AndroidManifest.xml`, which means:
 *
 *  - Debug builds show a second home-screen icon, "MapLibre PoC", next to the real app. No adb, no
 *    hidden gesture, no developer menu to build and later remove.
 *  - [com.gops.spatialmapper.MainActivity] and the tab structure are untouched — there is no code
 *    path from the app into this screen, which is exactly what "do not touch any existing map
 *    screens" asks for.
 *  - Release builds have neither the activity nor the icon nor the MapLibre dependency, because both
 *    the manifest entry and the library are debug-only.
 *
 * It is also launchable directly, which is handy for logcat runs:
 *   adb shell am start -n com.gops.spatialmapper/.maplibrepoc.MapLibrePocActivity
 *
 * DELIBERATELY NOT PORTRAIT-LOCKED. Every other activity in this app pins portrait because the AR
 * capture path and the compass fusion assume the phone is upright. None of that applies here, and
 * leaving orientation free is what makes it possible to test the thing most likely to be broken:
 * whether the MapView survives a real configuration change. See the lifecycle notes on
 * [MapLibrePocScreen].
 */
class MapLibrePocActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpatialMapperTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MapLibrePocScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

# SpatialMapper

An Android app for locating, measuring, and identifying individual trees and vegetation that are
missing from — or wrong in — outdated satellite imagery. The operator stands at a distance, points
the phone at a tree, and takes one photo. From that single frame the app derives the tree's real
GPS coordinate, an approximate canopy diameter, and a species identification, using nothing but the
phone's own camera, GPS, and orientation sensors. There is no survey equipment, no ground control,
and — deliberately — no dependency on Street View, Visual Positioning Service, or any other
coverage-limited backend, so it works anywhere a phone can get a GPS fix.

Built as an academic / portfolio project. Kotlin, Jetpack Compose, minSdk 34.

---

## Features

- **Single-photo capture with synchronized telemetry.** Each shot records GPS position and accuracy,
  compass heading (corrected to true north via `GeomagneticField` declination), device pitch, and the
  camera's focal length and frame dimensions, all sampled at the moment the shutter fires.
- **Distance measurement with a graceful fallback.** Uses the **ARCore Depth API** where the device
  supports it, reading metric depth at the center of the frame. On devices without ARCore or without
  Depth, it falls back to flat-ground trigonometry (`distance = eye height / tan(depression angle)`)
  derived from device pitch. The device's capability is probed once when the camera opens, and which
  method produced a given measurement is stored with the record.
- **Geodesic projection to a real coordinate.** Origin + true-north bearing + distance are projected
  onto the WGS-84 spheroid to get the target's latitude and longitude, with a round-trip consistency
  check that catches unit and lat/lon-order mistakes.
- **Satellite map review with operator-editable canopy framing.** After capture, the operator drags
  an ellipse over the tree crown in the photo; that pixel geometry is converted to metres through the
  pinhole model and the measured distance. The resulting polygon is then shown on a satellite map,
  where the operator can nudge the position before saving. What gets stored is the polygon the
  operator confirmed — not a shape regenerated later from a centre and radius.
- **Species identification via Pl@ntNet.** Decoupled from capture and available on demand from any
  saved record: shoot a close-up of a leaf, flower, fruit, or bark, submit it to the Pl@ntNet API
  against the Indian Subcontinent flora, and pick from ranked candidates. Every attempt is preserved,
  including the ones you reject or skip.
- **Session history with a full audit trail.** Every saved record keeps its photo, raw telemetry,
  which distance method was used, the confirmed canopy polygon, and the complete identification
  history — so a number on screen can always be traced back to how it was produced.
- **Global map view.** All saved trees drawn as canopy polygons on one satellite map, with tap-through
  to the underlying record.

## How it works

The capture pipeline is a linear sequence of five steps, with the operator confirming the result at
each stage where a machine estimate could plausibly be wrong.

```
  Telemetry capture  ->  Distance  ->  Projection  ->  Canopy framing  ->  Map review  ->  Save
   photo + GPS +        ARCore depth   geodesic       drag an ellipse     confirm on      Room
   heading + pitch      or ground-     offset along   over the crown,     satellite
   + intrinsics         plane geometry true-north     px -> metres        imagery
                                       bearing
```

1. **Telemetry capture.** CameraX takes the photo; the fused location provider supplies position and
   accuracy; the rotation-vector sensor supplies azimuth and pitch. Azimuth is corrected from magnetic
   to true north, and sensor accuracy is recorded alongside it so a low-confidence compass reading is
   visible later rather than silently trusted. Camera intrinsics (focal length in pixels, frame size)
   are read from Camera2 characteristics for the pixel-to-metre conversion downstream.

2. **Distance.** ARCore Depth is queried at the center pixel, which corresponds to the on-screen
   crosshair, so no view-to-sensor mapping is needed. Without Depth, the ground-plane fallback assumes
   the tree's base sits on level ground at the camera's own footing and solves for horizontal distance
   from the depression angle. That assumption degrades on slopes; the fallback declines to answer at
   all near the horizon, where the estimate diverges.

3. **Projection.** `origin + bearing + distance` is offset along a great circle to produce the
   target's coordinate. The bearing convention is preserved end to end: the sensor stack already
   produces degrees clockwise from true north, which is exactly what the spherical maths expects, so
   the value flows through with no conversion.

4. **Canopy framing.** Rather than segmenting the crown automatically, the operator drags an ellipse
   over the tree in the captured photo. Its horizontal and vertical extents are converted to metres
   through the pinhole relation `metres = pixels x distance / focalLengthPixels`. This is a
   deliberate choice: an operator who can see the tree resolves crown boundaries in cluttered foliage
   far more reliably than an on-device model, and — unlike a model's output — the result is
   inspectable at the moment it is produced.

5. **Map review and save.** The framed crown is drawn as a polygon on satellite imagery, positioned at
   the projected coordinate. The operator can nudge it against visible landmarks before committing.
   The confirmed vertex list is what persists to the database, and it is what every later screen draws.

**Species identification runs outside this pipeline entirely.** A tree can be located and measured on
one visit and identified days later, from the record's detail screen — the close-up capture attaches
to the existing record and adds no new telemetry. Submissions append to an
`identification_attempts` table; the record's species columns hold only the most recently confirmed
answer, so a wrong confirmation can be revisited without the earlier evidence having been destroyed.

## Tech stack

| Area | Choice | Notes |
| --- | --- | --- |
| Language / UI | Kotlin, Jetpack Compose, Material 3 | Single-activity. Navigation is a hand-rolled tab enum with hoisted screen state, not `navigation-compose` — the capture pipeline passes non-`Parcelable` telemetry types between steps in-process. |
| Camera | CameraX (`core`, `camera2`, `lifecycle`, `view`) | Two separate feeds: the measurement capture, and a close-up feed for identification with explicit tap-to-focus and `CAPTURE_MODE_MAXIMIZE_QUALITY`. |
| Depth / AR | ARCore Depth API (`com.google.ar:core`), SceneView | Declared **optional** in the manifest so the app installs and runs on non-ARCore devices. |
| Location | Google Play Services Fused Location | Position plus a real accuracy figure, which is stored rather than discarded. |
| Geodesy | `android-maps-utils` `SphericalUtil` | Great-circle offset and distance on WGS-84. |
| Maps | Maps SDK for Android via `maps-compose` | Satellite imagery for review and the global map. Pinned to 8.2.0; 8.3.x requires `compileSdk 37`. |
| Persistence | Room 2.8.4 with KSP | Two tables: `sessions` and `identification_attempts`. Photos are stored as file paths, not BLOBs. |
| Species ID | Pl@ntNet v2 API, `k-indian-subcontinent` referential | Called over plain `HttpURLConnection` + `org.json`; no HTTP client library is used — see below. |
| Build | AGP 9.2.1 (built-in Kotlin 2.2.10), Gradle 9.4.1 | `compileSdk 36.1`, `targetSdk 36`, `minSdk 34`. |

### On the global-operability constraint

The obvious way to place a tree accurately is to ask Google's Visual Positioning Service, or to fit
the photo against Street View panoramas. Both were rejected up front: their coverage is dense in
cities and effectively absent in exactly the rural and peri-urban areas where satellite basemaps are
most stale — which is the whole reason this app exists. Everything in the measurement path therefore
runs on sensors the phone carries anyway. That costs accuracy relative to a VPS fix, and the app is
honest about it: GPS accuracy, compass reliability, and which distance method was used are all stored
and shown alongside the result.

The same constraint shapes the identification path in reverse. Pl@ntNet is a network call, so it is
deliberately not on the capture path — being offline in the field is expected, and identification is
a "whenever convenient" action that can happen later over any connection.

### On the absence of an HTTP library

The Pl@ntNet integration is one multipart POST and one JSON parse, written directly against
`HttpURLConnection`. Retrofit plus OkHttp plus a converter would add three artifacts to the
Kotlin-metadata compatibility surface — AGP 9's built-in Kotlin compiler reads class metadata only up
to 2.3.0, which already ruled out one dependency in this project — plus R8 keep rules for the
response models, to replace about 150 lines. The response parser is a pure function with no Android
dependencies, unit-tested against a recorded response body.

## Setup

### Prerequisites

- An Android Studio version new enough to support **AGP 9.2.1**, with the Android SDK for **API 36**
- **JDK 21** — `gradle/gradle-daemon-jvm.properties` pins the daemon toolchain to 21 and Gradle will
  auto-provision it if it is not already installed
- An **arm64-v8a** physical device running **Android 14 (API 34)** or newer

### 1. Clone

```bash
git clone https://github.com/glitching-gops/beaver.git
cd beaver
```

### 2. Configure API keys

Both keys go in `local.properties`, which is git-ignored and must stay that way. The project ships a
committed `local.defaults.properties` holding only placeholders, so it **configures and builds
without any key at all** — the map renders blank tiles and identification reports that no key is
configured, but nothing crashes. The Secrets Gradle Plugin reads `local.properties` first and falls
back to that file.

**Google Maps SDK for Android**

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create (or select) a
   project.
2. Enable **Maps SDK for Android** under *APIs & Services → Library*.
3. *APIs & Services → Credentials → Create credentials → API key*.
4. Restrict the key — *Application restrictions → Android apps*, adding package name
   `com.gops.spatialmapper` with your debug keystore's SHA-1 (`./gradlew signingReport`), and
   *API restrictions → Maps SDK for Android*. An unrestricted key in a public repo's setup path is
   the classic way to get one abused.

**Pl@ntNet**

1. Sign up at [my.plantnet.org](https://my.plantnet.org/) (free).
2. Go to the **API** tab in your account and generate a key. The free tier allows **500
   identifications per day**.

**Put them in `local.properties`** (create the file if Android Studio has not already):

```properties
sdk.dir=/path/to/your/Android/Sdk
MAPS_API_KEY=AIza...your-maps-key
PLANTNET_API_KEY=2b10...your-plantnet-key
```

Never edit `local.defaults.properties` to hold a real key — it is committed.

The Maps key reaches the app as a manifest placeholder; the Pl@ntNet key is read from Kotlin as
`BuildConfig.PLANTNET_API_KEY`, which is why `buildFeatures { buildConfig = true }` is set in
`app/build.gradle.kts`. Without that flag the generated constant silently does not exist.

### 3. Build and run

```bash
./gradlew assembleDebug        # build
./gradlew installDebug         # install to a connected device
./gradlew testDebugUnitTest    # run the unit tests
```

Or open the project in Android Studio and run the `app` configuration.

**arm64-v8a only.** `app/build.gradle.kts` sets `abiFilters += "arm64-v8a"`, so debug builds **will
not install on the standard x86_64 emulator**. This is intentional — the measurement path needs a
real camera, compass, and GPS, none of which an emulator provides meaningfully. Add `"x86_64"` to
that list if you want to run the non-sensor screens (history, maps, identification) on an emulator.

### 4. Permissions

Camera and precise location are requested at runtime on first launch. Internet and network-state are
install-time permissions and need no prompt. Both keys are optional for the app to *start*: without
Maps you get blank tiles, without Pl@ntNet you get an explicit "no key configured" message on the
identification screen.

## Current status

The full pipeline is implemented and building: capture with synchronized telemetry, ARCore-or-fallback
distance, geodesic projection, canopy framing, satellite map review, Room persistence with history and
delete, species identification with preserved attempt history, and the global map. The project builds
clean and the pure-logic layers — projection, canopy geometry, Pl@ntNet response parsing, proximity
checks — are covered by 67 unit tests.

Two things are still open. Measurement accuracy has not yet been validated against ground truth, so
the distance and canopy-diameter figures should be treated as approximate until they have been checked
against known distances on real trees; in particular the pitch-to-depression convention in the
ground-plane fallback is the kind of sign relationship that wants confirming on hardware rather than
on paper. A handful of edge cases — sensor and camera behaviour across different devices, and the
sloped-ground assumption in the fallback — also remain to be checked in the field.

## License

MIT. See [LICENSE](LICENSE).

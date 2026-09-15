# SpatialMapper — project conventions

Kotlin + Jetpack Compose Android app for field-mapping tree canopies (AR capture, map review,
species ID, survey tracks). Single module: `app/`. Package `com.gops.spatialmapper`.

## Standing rule: end every task with the commit/push commands

Every task response ends with the PowerShell commands to commit and push the changes just made,
without being asked. Print them; do not run them unless explicitly told to.

```powershell
git status
git add .
git commit -m "<one line summarising THIS task's changes>"
git push
```

- Before `git add .`, **read the `git status` output** for anything that must not be committed:
  `local.properties` (holds the real API keys), model files (`*.onnx`, `*.tflite`, `*.pt`, …),
  keystores (`*.jks`, `*.keystore`), `.env`, `google-services.json`, any credentials, or capture
  photos (`SpatialMapper_*.jpg` carry GPS EXIF). If one shows up, stop and fix `.gitignore` first.
- The commit message is specific to the task (e.g. `Add v6->v7 migration for survey notes`), not
  generic (`update files`).
- Branch is `main`, remote `origin`.

## Toolchain constraints (don't rediscover these)

- **AGP 9 with built-in Kotlin, KGP 2.2.10.** The compiler reads Kotlin metadata only up to 2.3.0,
  so any library built with a newer Kotlin fails to compile against. Check a dependency's Kotlin
  version before adding it. Plain-Java libraries are safe.
  - This already ruled out **Coil 3.5.x**.
  - It is also why `identify/PlantNetClient.kt` is a **hand-rolled `HttpURLConnection` + `org.json`
    client**, not Retrofit/OkHttp. Keep it that way.
- **KSP 2.3.10, never kapt.** kapt (and KSP1) don't work with AGP 9 built-in Kotlin. Room's
  compiler runs through `ksp(...)`.
- **ABI: `arm64-v8a` only** (`abiFilters` in `app/build.gradle.kts`). Debug builds will not install
  on x86_64 emulators, so test on a real device.
- **minSdk 34, targetSdk 36** (compileSdk 36, minor 1).
- **Portrait-locked** (`MainActivity`, because of AR capture and compass fusion). The only exception
  is the debug-only `MapLibrePocActivity`, which is deliberately left free to rotate.
- **maps-compose pinned at 8.2.0.** 8.3.x requires compileSdk 37.
- Room 2.8.4 (stable 2.x). Room 3 is alpha and a different artifact (`androidx.room3`).
- MapLibre 13.6.1 is `debugImplementation` only, for the PoC in `app/src/debug/`. Don't let tooling
  bump it to a `-pre` build, and nothing in `src/main` may reference it.

## Room: migrations

- **Before writing any migration, open `data/AppDatabase.kt` and read the current
  `@Database(version = …)` value.** Do not assume the next number from memory, docs, or earlier
  conversation. This has been gotten wrong twice, and a migration with the wrong `(from, to)` breaks
  every existing install.
- **Policy: from v5 onward, migrations are additive `Migration` objects only.** Each schema change
  bumps `version` by one and appends a `Migration(current, current + 1)` to `ALL_MIGRATIONS` in
  `data/Migrations.kt`. Never edit an existing migration.
- **`fallbackToDestructiveMigration` (any variant) is not acceptable.** Real field data now exists,
  and an unhandled version should crash loudly, not wipe the data.
- Migration SQL must match Room's generated `createSql` exactly. Copy it from the exported schema.
- `app/schemas/…/N.json` is append-only: never delete or regenerate a released version's file.
  Add a case to `androidTest/.../MigrationTest.kt`; `tools/verify_migration.py` is the offline check.

## Architecture conventions

- **Navigation is a `when`-driven `AppTab` enum with hoisted state in `MainActivity.kt`.** It is
  deliberately not NavHost/navigation-compose: the capture pipeline types (`CaptureTelemetry`,
  `CameraIntrinsicsSnapshot`, `CanopyMeasurement`) aren't Parcelable. Sub-flow state is hoisted
  there so switching tabs doesn't lose work.
- **The bottom tab bar hides during modal sub-flows** (framing, map review, identification) through
  the single `inModalFlow` flag. A new modal flow joins that flag instead of adding a second
  mechanism.
- **API keys live in `local.properties`** (git-ignored) via the Secrets Gradle Plugin, with
  placeholders in the committed `local.defaults.properties`. A new key goes in both. Keys reach code
  as `BuildConfig.*` (`buildConfig = true`) or as manifest placeholders.
- **Canopy and scout colours are centralised** in `map/CanopyStyle.kt` and `map/ScoutStyle.kt`
  (colours, widths, z-indices, icon factories). Use those and don't hard-code new values in screens.
  The MapLibre PoC mirrors them as ARGB ints, so keep the two numerically identical.

## graphify

A knowledge graph of the codebase (the graphify CLI, installed via `uv tool`) lives in
`graphify-out/`. It is **git-ignored**: it's a derived, machine-local artifact, and some of its files
hold absolute local paths.

Rules:
- For codebase questions, first run `graphify query "<question>"` when `graphify-out/graph.json`
  exists. Use `graphify path "<A>" "<B>"` for relationships, `graphify explain "<concept>"` for one
  node, and `graphify affected "<X>"` for impact before a refactor. `graphify god-nodes` lists the
  hubs.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review, or when
  query/path/explain don't surface enough.
- **After modifying code, run `graphify update .`** to keep the graph current. It's AST-only, with
  no LLM and no API cost. After deleting or moving code it may refuse to shrink the graph; then use
  `graphify update . --force`.
- For changes to docs/markdown (non-code), use `/graphify . --update` (semantic re-extraction).
- Optional: `graphify hook install` adds a post-commit hook that rebuilds the graph for changed
  code files on each commit. It is **not installed** right now.
- Note: `graphify install --project --platform claude` rewrites this `## graphify` section with its
  stock text. (The skill docs mention `graphify claude install`, but the installed CLI has no such
  subcommand.)

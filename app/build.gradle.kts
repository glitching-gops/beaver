plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.secrets.gradle)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gops.spatialmapper"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.gops.spatialmapper"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Originally added to strip ~111 MB of ONNX Runtime native libs across four ABIs. ORT is
            // gone, so the size argument no longer applies — what remains is ARCore's own native
            // libs, which are comparatively small.
            //
            // DELIBERATE TRADEOFF, kept from the previous revision: this makes debug builds
            // uninstallable on the standard x86_64 emulator. Still defensible (ARCore depth, the
            // compass, and GPS all need real sensors), but with ORT removed it now buys much less.
            // Add "x86_64" here if emulator runs of the framing screen would be useful.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Needed so the Secrets Gradle Plugin can surface local.properties entries as BuildConfig
        // constants. The Maps key only ever needed the manifest placeholder, but the Pl@ntNet key is
        // read from Kotlin at call time, so it has to reach the code — see BuildConfig.PLANTNET_API_KEY
        // in identify/PlantNetClient.kt. AGP 8+ requires this flag explicitly; without it the
        // generated field silently doesn't exist.
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.play.services.location)
    implementation(libs.arsceneview)
    implementation(libs.ar.core)
    implementation(libs.maps.utils)
    implementation(libs.maps.compose)
    // Phase 10: local persistence. Room's annotation processor runs through KSP (not kapt, which is
    // incompatible with AGP 9 built-in Kotlin). room-ktx supplies the coroutine/Flow DAO support.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    // Real org.json for local unit tests — see the note in libs.versions.toml. Not shipped in the APK.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Secrets Gradle Plugin: injects the Maps API key into the manifest's ${MAPS_API_KEY} placeholder
// WITHOUT hardcoding it in a committed file.
//   - Real key: put MAPS_API_KEY=<your key> in local.properties (git-ignored; the plugin reads this
//     by default). See the Phase 6 notes for how to generate one.
//   - local.defaults.properties (committed) supplies a harmless placeholder so the project still
//     configures/builds on a machine that hasn't added a key yet — the map just renders blank tiles.
secrets {
    // local.properties is the default source; this only sets the committed fallback.
    defaultPropertiesFileName = "local.defaults.properties"
}
// Scripted journeys through Prism on a connected phone or emulator (1.7.4):
// - BaselineProfileGenerator writes Prism's baseline profile: `./gradlew :app:generateReleaseBaselineProfile`
//   (Android 13+ or a rooted device; the emulator image with Google APIs is fine). The profile lands in
//   app/src/release/generated/baselineProfiles and ships in the APK.
// - JankBenchmark times frames on the same journeys, with and without the profile:
//   `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
// Test-only: nothing here goes into the APK. Apache-2.0 (AndroidX).
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.prism.music.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Prism is measured on emulators here (no spare phone): the numbers compare runs with each other, not with phones.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}

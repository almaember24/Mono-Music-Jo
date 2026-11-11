plugins {
    id("com.android.application") version "9.4.1" apply false
    // :baselineprofile (1.7.4): scripted journeys through Prism that write its baseline profile and time its frames.
    id("com.android.test") version "9.4.1" apply false
    id("androidx.baselineprofile") version "1.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.room") version "2.8.5" apply false
}

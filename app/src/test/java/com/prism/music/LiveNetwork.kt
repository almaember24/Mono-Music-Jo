package com.prism.music

/**
 * JUnit category for unit tests that reach real services (YouTube, lyric sites, Deezer, iTunes...). Their answers change
 * without Prism changing, so normal runs leave them out and a red normal run is always a real problem.
 *
 * Mark the class with `@Category(LiveNetwork::class)`. Gradle (app/build.gradle.kts):
 * - `:app:testDebugUnitTest` leaves them out,
 * - `-Plive` runs everything, `-PliveOnly` runs only them,
 * - `-PnoNetwork` points the test JVM at a dead proxy, so a test that reaches the network without this mark fails.
 */
interface LiveNetwork

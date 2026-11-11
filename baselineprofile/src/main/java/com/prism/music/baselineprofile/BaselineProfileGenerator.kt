package com.prism.music.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.baselineprofile.Journeys.library
import com.prism.music.baselineprofile.Journeys.openAnAlbumFromResults
import com.prism.music.baselineprofile.Journeys.openAPlaylist
import com.prism.music.baselineprofile.Journeys.pastOnboarding
import com.prism.music.baselineprofile.Journeys.playAndOpenPlayer
import com.prism.music.baselineprofile.Journeys.scrollHome
import com.prism.music.baselineprofile.Journeys.search
import com.prism.music.baselineprofile.Journeys.settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes Prism's baseline profile: the classes and methods the [Journeys] run, which Android then compiles when Prism
 * is installed or updated instead of interpreting them until it gets round to it (often overnight). Without it, every
 * update started Prism slow again (1.7.4, GitHub #100). Run with `./gradlew :app:generateReleaseBaselineProfile`.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    // Five passes at most, done when two in a row agree: each is a few minutes on an emulator.
    fun generate() = rule.collect(packageName = PACKAGE, maxIterations = 5, stableIterations = 2, includeInStartupProfile = true) {
        startActivityAndWait()
        pastOnboarding()
        scrollHome()
        openAPlaylist()
        search("daft punk")
        openAnAlbumFromResults()
        playAndOpenPlayer()
        library()
        settings()
    }
}

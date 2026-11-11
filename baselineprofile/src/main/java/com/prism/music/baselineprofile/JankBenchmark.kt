package com.prism.music.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.baselineprofile.Journeys.pastOnboarding
import com.prism.music.baselineprofile.Journeys.playAndOpenPlayer
import com.prism.music.baselineprofile.Journeys.scrollHome
import com.prism.music.baselineprofile.Journeys.search
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame times on Prism's busiest screens, compiled as right after an install or update without a baseline profile
 * ([CompilationMode.None]) and with one ([BaselineProfileMode.Require]). FrameTimingMetric reports each frame's CPU
 * time (frameDurationCpuMs) and how far it ran past its deadline (frameOverrunMs, above 0 = a dropped frame).
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`; results in baselineprofile/build/outputs.
 */
@RunWith(AndroidJUnit4::class)
class JankBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun homeScrollNoProfile() = homeScroll(CompilationMode.None())
    @Test fun homeScrollWithProfile() = homeScroll(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun searchAndPlayerNoProfile() = searchAndPlayer(CompilationMode.None())
    @Test fun searchAndPlayerWithProfile() = searchAndPlayer(CompilationMode.Partial(BaselineProfileMode.Require))
    @Test fun startupNoProfile() = startup(CompilationMode.None())
    @Test fun startupWithProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun homeScroll(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        iterations = 3,
        startupMode = StartupMode.COLD,
        setupBlock = { startActivityAndWait(); pastOnboarding() },
    ) { scrollHome() }

    private fun searchAndPlayer(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        iterations = 3,
        startupMode = StartupMode.COLD,
        setupBlock = { startActivityAndWait(); pastOnboarding() },
    ) {
        search("daft punk")
        playAndOpenPlayer()
    }

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 5,
        startupMode = StartupMode.COLD,
    ) { startActivityAndWait() }
}

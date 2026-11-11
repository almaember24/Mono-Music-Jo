package com.prism.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Lyrics page's examples come to rest. In 1.7.9's Android 9 check they played on for as long as the page was open,
 * and since Prism's sweep lays its line out every frame, accessibility was told of a change every 200 ms: the screen
 * never went quiet, so neither a screen reader nor the check's own screen reading could settle there.
 *
 * No Compose test rule here, on purpose: with one, Compose holds the app's never-ending animations still, and the
 * looping examples passed.
 */
@RunWith(AndroidJUnit4::class)
class LyricsExamplesRestTest {
    @get:Rule val shots = ScreenshotOnFailure()

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    @Test fun lyricsExamplesComeToRest() {
        // The suite runs with Android's animations off (animationsDisabled), and the examples are still then anyway.
        val before = device.executeShellCommand("settings get global animator_duration_scale").trim()
        device.executeShellCommand("settings put global animator_duration_scale 1")
        try {
            Fixture.ready()
            Fixture.launch()
            device.wait(Until.findObject(By.desc("Settings")), 15_000).click()
            open("Customize Prism")
            open("Lyrics")
            device.wait(Until.hasObject(By.text("Classic karaoke")), 10_000)
            // Three loops of 4 s, then still: quiet (a whole second without a change) well within 25 s.
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(1_000, 25_000)
        } finally {
            device.executeShellCommand("settings put global animator_duration_scale ${before.ifEmpty { "1" }.replace("null", "1")}")
        }
    }

    private fun open(text: String) {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text))
        device.wait(Until.findObject(By.text(text)), 10_000).click()
    }
}

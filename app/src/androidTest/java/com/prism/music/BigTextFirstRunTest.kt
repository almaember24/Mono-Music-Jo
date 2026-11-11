package com.prism.music

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.7.9.3 (owner's report, several listeners): with large text the welcome screen's list pushed its buttons off the
 * bottom, and a new listener couldn't get past it. With the phone's largest font size and a large display size, every
 * first-run screen must offer its way on, on screen.
 */
@RunWith(AndroidJUnit4::class)
class BigTextFirstRunTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(cmd: String) = automation.executeShellCommand(cmd).close()

    @After fun normalSizes() {
        shell("settings put system font_scale 1.0")
        shell("wm density reset")
    }

    private fun shot(name: String) {
        // Kept through the media store's Downloads, which Android 9 doesn't have: there the test checks without a picture.
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val bmp = automation.takeScreenshot() ?: return
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "bigtext-$name-${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/prism-shots")
        }
        val uri = Fixture.context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        Fixture.context.contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun theWelcomeScreensButtonsAreOnScreenWithTheLargestText() {
        // The largest font size Android offers (2.0 from Android 14; settings take it on 13 too) and a larger display size.
        shell("settings put system font_scale 2.0")
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.densityDpi
        shell("wm density ${(density * 1.25f).toInt()}")
        Thread.sleep(1500)
        Fixture.launch()
        val skip = tr(R.string.skip_for_now)
        compose.waitUntil(20_000) { compose.onAllNodes(hasText(skip) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle(); Thread.sleep(800)
        shot("welcome")
        // On screen without the listener having to find it (before, it was below the bottom, unreachable).
        compose.onAllNodes(hasText(tr(R.string.make_a_prism_account)) and hasClickAction()).onFirst().assertIsDisplayed()
        // And their words fit inside them (fixed heights cut "Make a Prism Account" to "Make a Prism").
        for (label in listOf(tr(R.string.make_a_prism_account), tr(R.string.sign_in_with_google))) {
            val button = compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().first().boundsInRoot
            val text = compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot }.filter { it != button }.minByOrNull { it.height } ?: continue
            org.junit.Assert.assertTrue("\"$label\" fits its button ($text in $button)", text.top >= button.top - 1 && text.bottom <= button.bottom + 1)
        }
        compose.onAllNodes(hasText(skip) and hasClickAction()).onFirst().assertIsDisplayed().performClick()
        compose.waitForIdle(); Thread.sleep(1500)
        shot("after-skip")
    }
}

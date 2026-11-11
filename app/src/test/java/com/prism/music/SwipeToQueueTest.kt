package com.prism.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.components.SwipeToQueue
import com.prism.music.ui.theme.PrismTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #248 (1.7.9.2): the swipe songs had, now also for a release's row on New from your artists. Right: play next;
 * left: end of the queue; a short swipe: nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp", application = android.app.Application::class)
class SwipeToQueueTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rightPlaysNextLeftQueuesShortDoesNothing() {
        val got = mutableListOf<Boolean>()
        compose.setContent {
            PrismTheme(AppSettings(), artworkColor = null) {
                SwipeToQueue({ got += it }) { Box(Modifier.fillMaxWidth().height(64.dp).testTag("row")) }
            }
        }
        compose.onNodeWithTag("row").performTouchInput { swipeRight(startX = 10f, endX = width * 0.9f) }
        compose.waitForIdle()
        compose.onNodeWithTag("row").performTouchInput { swipeLeft(startX = width * 0.9f, endX = 10f) }
        compose.waitForIdle()
        compose.onNodeWithTag("row").performTouchInput { swipeRight(startX = 10f, endX = 40f) }
        compose.waitForIdle()
        assertEquals(listOf(true, false), got)
    }
}

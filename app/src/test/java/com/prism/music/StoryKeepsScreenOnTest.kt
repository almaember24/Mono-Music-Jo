package com.prism.music

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.ui.screens.StoryDialog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #412: the phone went to sleep in the middle of a Replay story (its slides run on a timer, nobody touches the screen).
 * The story's window keeps the screen on while it's open, and lets go once it's closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoryKeepsScreenOnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theStoryKeepsTheScreenOnWhileItsOpen() {
        var open by mutableStateOf(true)
        var view: View? = null
        compose.setContent {
            if (open) StoryDialog(onDismiss = { open = false }) {
                view = LocalView.current
                Text("A story")
            }
        }
        compose.waitForIdle()
        val storyView = view
        assertNotNull("the story is showing", storyView)
        assertTrue("the screen stays on while the story plays", storyView!!.keepScreenOn)

        open = false
        compose.waitForIdle()
        assertFalse("closed, the phone can sleep again", storyView.keepScreenOn)
    }
}

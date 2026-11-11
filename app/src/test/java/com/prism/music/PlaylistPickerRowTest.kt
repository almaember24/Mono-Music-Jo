package com.prism.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.PickerRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #401: Add to playlist glitched on a fast swipe. One part of it: the ✓ marks ("Already has this song") are worked out
 * after the sheet opens, and a row that had no line under its name grew a second one, so rows changed height while the
 * list was flying past. Every row keeps room for two lines now, so a late mark never moves anything. Checked with large
 * text (the text then decides a row's height, not its cover).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaylistPickerRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aLateMarkDoesntChangeARowsHeight() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.6f)) {
                MaterialTheme {
                    Column(Modifier.width(360.dp)) {
                        Box(Modifier.testTag("bare")) { PickerRow(null, "Road trip", null, false, null, "m1") {} }
                        Box(Modifier.testTag("marked")) { PickerRow(null, "Road trip", "Already has this song", true, "Already in Road trip", "m2") {} }
                        Box(Modifier.testTag("counted")) { PickerRow(null, "Road trip", "12 songs", false, null, "m3") {} }
                    }
                }
            }
        }
        val bare = compose.onNodeWithTag("bare").fetchSemanticsNode().size.height
        val marked = compose.onNodeWithTag("marked").fetchSemanticsNode().size.height
        val counted = compose.onNodeWithTag("counted").fetchSemanticsNode().size.height
        assertEquals("a row without a line is as tall as one whose mark came in", marked, bare)
        assertEquals(counted, marked)
    }
}

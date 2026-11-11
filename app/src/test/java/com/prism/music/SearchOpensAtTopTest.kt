package com.prism.music

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.KeepAtTopUntilScrolled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #279: Search opened scrolled to the bottom on the first visit after launch. Its sections load in one by one above
 * "Arrange this page", the only row there at first, and a lazy list keeps the row it showed first in view. The landing
 * now stays at its top until the listener scrolls it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchOpensAtTopTest {
    @get:Rule val compose = createComposeRule()

    private val sections = mutableIntStateOf(0)
    private lateinit var list: LazyListState

    /** A page like Search's landing: [sections] rows coming in above the "arrange" row at its end. */
    private fun page(keepAtTop: Boolean) {
        compose.setContent {
            list = rememberLazyListState()
            if (keepAtTop) KeepAtTopUntilScrolled(list)
            LazyColumn(Modifier.size(360.dp, 640.dp).testTag("page"), state = list) {
                items((0 until sections.intValue).toList(), key = { "section$it" }) { Text("Section $it", Modifier.fillMaxWidth().height(120.dp)) }
                item(key = "arrange") { Text("Arrange this page") }
            }
        }
        compose.waitForIdle()
    }

    @Test fun withoutTheFixTheLandingOpensAtItsEnd() {
        page(keepAtTop = false)
        sections.intValue = 20
        compose.waitForIdle()
        assertNotEquals("the list kept the arrange row in view (the bug)", 0, list.firstVisibleItemIndex)
    }

    @Test fun theLandingStaysAtItsTopAsSectionsComeIn() {
        page(keepAtTop = true)
        sections.intValue = 3
        compose.waitForIdle()
        sections.intValue = 20
        compose.waitForIdle()
        assertEquals("the page opens at its top", 0, list.firstVisibleItemIndex)
        // Scrolled by the listener, it's left where they put it as more comes in.
        compose.onNodeWithTag("page").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertNotEquals(0, list.firstVisibleItemIndex)
        sections.intValue = 24
        compose.waitForIdle()
        assertNotEquals("not sent back to the top once scrolled", 0, list.firstVisibleItemIndex)
    }
}

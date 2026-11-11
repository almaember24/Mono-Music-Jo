package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.TvFixture.awaitFocus
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.ui.tv.TAG_NAV
import com.prism.music.ui.tv.TAG_PLACE
import com.prism.music.ui.tv.TvSection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism TV's shell (1.8.0 slice 1): it opens from the launcher with focus on Home, the remote reaches every
 * top-level place, Back goes Home and then leaves Prism, and the phone's screen hands itself over to the TV's.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvShellTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun setUp() {
        TvFixture.assumeTv()
        Fixture.ready()
    }

    private fun placeShown(s: TvSection) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag("$TAG_PLACE/${s.name}"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun opensOnHomeWithFocus() {
        TvFixture.launch()
        placeShown(TvSection.HOME)
        // Home's first item (its Search button while Home is empty, as here with no network).
        compose.awaitFocus(10_000)
    }

    /** Left until the side navigation has focus (it opens), on [section]'s item. */
    private fun toNav(section: TvSection = TvSection.HOME) {
        compose.awaitFocus(10_000)
        for (i in 0 until 6) {
            if (compose.onAllNodes(hasTestTag("$TAG_NAV/${section.name}") and androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
            TvFixture.left(); Thread.sleep(300)
        }
        compose.awaitFocusOn(hasTestTag("$TAG_NAV/${section.name}"))
    }

    @Test fun theRemoteReachesEveryPlace() {
        TvFixture.launch()
        toNav()
        for (s in TvSection.entries.drop(1)) {
            TvFixture.down()
            compose.awaitFocusOn(hasTestTag("$TAG_NAV/${s.name}"))
            TvFixture.ok()
            placeShown(s)
            compose.awaitFocus()
            // The place's own first item may have taken focus: back to the side navigation for the next one.
            toNav(s)
        }
    }

    @Test fun backGoesHomeThenLeaves() {
        val scenario = TvFixture.launch()
        toNav()
        repeat(3) { TvFixture.down() }
        compose.awaitFocusOn(hasTestTag("$TAG_NAV/${TvSection.SETTINGS.name}"))
        TvFixture.ok()
        placeShown(TvSection.SETTINGS)
        TvFixture.back()
        placeShown(TvSection.HOME)
        compose.awaitFocus()
        TvFixture.back()
        val left = (1..50).any { Thread.sleep(100); scenario.state == Lifecycle.State.DESTROYED }
        assertEquals("Back on Home leaves Prism", true, left)
    }

    @Test fun thePhoneScreenOpensTheTvs() {
        val phone = ActivityScenario.launch(PrismActivity::class.java)
        placeShown(TvSection.HOME)
        compose.awaitFocus(10_000)
        assertEquals(Lifecycle.State.DESTROYED, phone.state)
    }

    /** The review (1.8.0): a link opened on a TV (it comes to the phone's screen first) opens its page there. */
    @Test fun aLinkOpensItsPageOnTheTv() {
        val link = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://music.youtube.com/channel/UCprismTvTest"), Fixture.context, PrismActivity::class.java)
        ActivityScenario.launch<PrismActivity>(link)
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(com.prism.music.ui.tv.TAG_ARTIST), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.awaitFocus(10_000)
    }
}

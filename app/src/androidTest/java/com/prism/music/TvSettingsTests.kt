package com.prism.music

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.prism.music.TvFixture.awaitFocus
import com.prism.music.TvFixture.awaitFocusOn
import com.prism.music.ui.tv.TAG_HOME
import com.prism.music.ui.tv.TAG_LYRICS_STYLE
import com.prism.music.ui.tv.TAG_NAV
import com.prism.music.ui.tv.TAG_SETTINGS
import com.prism.music.ui.tv.TAG_WELCOME
import com.prism.music.ui.tv.TvSection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prism TV, slice 8: the TV's settings with the remote (a switch flips, a setting's ⓘ opens its explanation), the first
 * run's welcome, and the update banner on Home.
 */
@TvTest
@RunWith(AndroidJUnit4::class)
class TvSettingsTests {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Before fun setUp() { TvFixture.assumeTv() }

    @After fun cleanUp() {
        RecordedNetwork.answers.remove("https://api.github.com/repos/Yigder/Prism/releases/latest")
        Fixture.container.settings.setNormalize(true)
    }

    private val inNav = androidx.compose.ui.test.SemanticsMatcher("in the side navigation") { n ->
        n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("$TAG_NAV/") == true
    }

    private fun navFocused() = compose.onAllNodes(inNav and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun goTo(section: TvSection) {
        // On a place (past the welcome, which goes once it's done), or on the side navigation: only then do the keys
        // below go where they're meant to (on the slow TV, Home's first focus came after all the presses).
        compose.waitUntil(15_000) {
            val tag = compose.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
            tag != null && !tag.startsWith(TAG_WELCOME)
        }
        // Into the side navigation, and still there once the place has settled (its first focus, which can come a moment
        // later, would take it back).
        for (attempt in 0 until 3) {
            for (i in 0 until 8) {
                if (navFocused()) break
                TvFixture.left()
                // Waited on through the rule, so the screen moves meanwhile.
                runCatching { compose.waitUntil(1_500) { navFocused() } }
            }
            compose.waitForIdle()
            if (navFocused()) break
        }
        // The navigation opens on the place shown: up to its top (Home), then down to [section].
        repeat(TvSection.entries.size) { TvFixture.up(); compose.waitForIdle() }
        repeat(section.ordinal) { TvFixture.down(); compose.waitForIdle() }
        compose.awaitFocusOn(hasTestTag("$TAG_NAV/${section.name}"))
        TvFixture.ok()
    }

    @Test fun aSwitchFlipsAndItsExplanationOpensWithTheRemote() {
        Fixture.ready()
        TvFixture.launch()
        goTo(TvSection.SETTINGS)
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
        // Down to Sound (moving along the groups shows each), Right into it, down to "Same loudness".
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/1"))
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/2"))
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"))
        TvFixture.down()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/1"))
        val before = Fixture.container.settings.current.normalize
        TvFixture.ok()
        compose.waitUntil(5_000) { Fixture.container.settings.current.normalize != before }
        // Its ⓘ, one step Right: the explanation, closed by Back, focus back on the ⓘ.
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/info/1"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/infoclose"), 10_000)
        TvFixture.back()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("$TAG_SETTINGS/infoclose"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        compose.awaitFocus()
    }

    /**
     * The owner (1.8.0): the lyrics style on the TV, with a live example of each. Settings → Lyrics → Lyrics style opens
     * on the style in use, the focused one's example shows, OK picks it (the phone's own setting) and the panel closes.
     */
    @Test fun aLyricsStyleIsPickedWithItsExample() {
        Fixture.ready()
        val settings = Fixture.container.settings
        // Held still (Reduce motion): the example otherwise moves every frame, and the test waits for a still screen.
        settings.setReduceMotion(true)
        compose.waitUntil(5_000) { settings.current.reduceMotion }
        try {
            TvFixture.launch()
            goTo(TvSection.SETTINGS)
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
            // Account, Language, Sound, Lyrics; Right into it, down to Lyrics style (under the size).
            for (g in 1..3) { TvFixture.down(); compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/$g")) }
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"))
            TvFixture.down()
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/1"))
            TvFixture.ok()
            compose.awaitFocusOn(hasTestTag("$TAG_LYRICS_STYLE/prism"), 10_000)
            assertTrue(exists("$TAG_LYRICS_STYLE/example/prism"))
            // Down to Glow (Prism, Classic karaoke, Bold card, Glow): its example shows.
            repeat(3) { TvFixture.down(); Thread.sleep(200) }
            compose.awaitFocusOn(hasTestTag("$TAG_LYRICS_STYLE/glow"))
            compose.waitUntil(5_000) { exists("$TAG_LYRICS_STYLE/example/glow") }
            TvFixture.ok()
            compose.waitUntil(5_000) { settings.current.lyricsLook.style == com.prism.music.data.prefs.LyricsStyle.GLOW }
            compose.waitUntil(5_000) { !exists(TAG_LYRICS_STYLE) }
            assertTrue("Glow's own glow", settings.current.lyricsLook.haloNow)
        } finally {
            settings.setLyricsStartFrom(com.prism.music.data.prefs.LyricsStyle.PRISM)
            settings.setReduceMotion(false)
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun theFirstRunWelcomesAndCarriesOnWithoutAnAccount() {
        // A fresh install: no Fixture.ready().
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_WELCOME/phone"), 20_000)
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_WELCOME/skip"))
        TvFixture.ok()
        compose.waitUntil(10_000) { Fixture.container.settings.current.onboarded }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(TAG_HOME), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.awaitFocus()
        assertEquals(false, Fixture.container.settings.current.isLoggedIn)
    }

    /**
     * The real TV's feedback (1.8.0): a TV that had Prism's phone app before (onboarded there) still gets the TV's
     * welcome once, signed out; and Settings' "Not signed in" signs in with the phone.
     */
    @Test fun aTvOnboardedOnThePhoneAppIsWelcomedOnceAndNotSignedInSignsIn() {
        Fixture.ready()
        kotlinx.coroutines.runBlocking {
            Fixture.container.settings.editNow { it[androidx.datastore.preferences.core.booleanPreferencesKey("tv_welcomed")] = false }
            kotlinx.coroutines.withTimeout(10_000) { while (Fixture.container.settings.current.tv.welcomed) kotlinx.coroutines.delay(20) }
        }
        assertTrue(Fixture.container.settings.current.onboarded)
        TvFixture.launch()
        compose.awaitFocusOn(hasTestTag("$TAG_WELCOME/phone"), 20_000)
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_WELCOME/skip"))
        TvFixture.ok()
        compose.waitUntil(10_000) { Fixture.container.settings.current.tv.welcomed }
        // Settings → Account: "Not signed in" opens the sign-in with the phone.
        goTo(TvSection.SETTINGS)
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"))
        TvFixture.ok()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(com.prism.music.ui.tv.TAG_PAIR), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // 1.8.1 (Reddit: "I had to stop and think about how to finish"): where on the phone, in its own words (a tap on
        // the line under the player's controls, then Remote beside this TV), and what to tap once paired.
        val tvName = Fixture.container.handoff.name
        for (step in listOf(tr(R.string.tv_pair_step_where, tvName), tr(R.string.tv_pair_step_sign_in))) {
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(step), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(tr(R.string.tv_pair_step_where, tvName).contains(tr(R.string.tv_remote)))
        assertTrue(tr(R.string.tv_pair_step_sign_in).contains(tr(R.string.tv_sign_in_here)))
    }

    /**
     * Reddit (1.8.1): "options on TV to change background opacity, especially in lyrics mode". Settings → Lyrics →
     * Background darkness: as it comes until picked; picked from the remote, it's kept.
     */
    @Test fun theBackgroundsDarknessIsPickedInLyricsSettings() {
        Fixture.ready()
        val settings = Fixture.container.settings
        assertEquals(0, settings.current.tv.backdropDim)
        try {
            TvFixture.launch()
            goTo(TvSection.SETTINGS)
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
            for (g in 1..3) { TvFixture.down(); compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/$g")) }
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"))
            // Size, style, then the background.
            repeat(2) { i -> TvFixture.down(); compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/${i + 1}")) }
            compose.onNode(androidx.compose.ui.test.hasText(tr(R.string.tv_backdrop_as_it_comes)), useUnmergedTree = true).assertExists()
            TvFixture.ok()
            // Lighter, As it comes, Darker…: down to Darker.
            compose.awaitFocusOn(hasTestTag("${com.prism.music.ui.tv.TAG_ACTIONS}/0"), 10_000)
            repeat(2) { i -> TvFixture.down(); compose.awaitFocusOn(hasTestTag("${com.prism.music.ui.tv.TAG_ACTIONS}/${i + 1}")) }
            TvFixture.ok()
            compose.waitUntil(5_000) { settings.current.tv.backdropDim == 1 }
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(tr(R.string.tv_backdrop_darker)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            settings.setTvBackdropDim(0)
        }
    }

    /**
     * The owner's second round (1.8.0): Home's rows are customized from the TV's Settings (Customize Home), in the
     * phone's own Home layout (so the phone agrees): a row hidden there is gone from Home.
     */
    @Test fun aHomeRowHiddenInSettingsIsGoneFromHome() {
        Fixture.ready()
        val c = Fixture.container
        val before = c.settings.current.homeLayout
        try {
            // A shelf on Home (fresh for this run, so nothing is asked of the network).
            val title = "Prism settings row"
            val key = com.prism.music.data.prefs.HomeSections.shelfKey(title)
            com.prism.music.ui.tv.TvHomeShelves.keep(null, listOf(com.prism.music.data.model.Shelf(title, items = Fixture.songs(2).map { com.prism.music.data.model.SongItem(it) })), System.currentTimeMillis())
            TvFixture.launch()
            compose.waitUntil(15_000) { compose.onAllNodes(androidx.compose.ui.test.hasText(title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            goTo(TvSection.SETTINGS)
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
            // Home: the seventh group; its Customize Home opens the editor.
            for (g in 1..6) { TvFixture.down(); compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/$g")) }
            TvFixture.right()
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"))
            TvFixture.ok()
            // The hero, then every row the TV can have, YouTube's shelves last: down to the shelf, OK hides it.
            compose.awaitFocusOn(hasTestTag("${com.prism.music.ui.tv.TAG_HOME_EDITOR}/row/${com.prism.music.ui.tv.TvHomeLayout.HERO}"), 10_000)
            val shelfRow = hasTestTag("${com.prism.music.ui.tv.TAG_HOME_EDITOR}/row/$key")
            for (i in 0 until 30) {
                if (compose.onAllNodes(shelfRow and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) break
                TvFixture.down(); Thread.sleep(200)
            }
            compose.awaitFocusOn(hasTestTag("${com.prism.music.ui.tv.TAG_HOME_EDITOR}/row/$key"))
            TvFixture.ok()
            compose.waitUntil(10_000) { com.prism.music.ui.tv.hiddenIn(c.settings.current.homeLayout, key) }
            TvFixture.back()
            compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"), 10_000)
            goTo(TvSection.HOME)
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(TAG_HOME), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(1_000)
            assertTrue("\"$title\" still on Home", compose.onAllNodes(androidx.compose.ui.test.hasText(title), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        } finally {
            c.settings.setHomeLayout(before)
        }
    }

    /** The review (1.8.0): signing out on a (shared) TV takes the account's library with it, as on the phone. */
    @Test fun signingOutTakesTheAccountsLibraryWithIt() {
        Fixture.ready()
        val c = Fixture.container
        kotlinx.coroutines.runBlocking {
            c.settings.fromBackup(mapOf("cookie" to "s:SAPISID=tv-test; SID=tv-test", "account_name" to "s:TV Listener"), settings = false, accounts = true)
        }
        TvFixture.launch()
        goTo(TvSection.SETTINGS)
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/group/0"), 10_000)
        // The account's library, as a sync would have kept it.
        c.library.playlists.value = listOf(com.prism.music.data.model.PlaylistItem("PLtv", "The listener's own list", "", null))
        // Account: signed in as, sign in with phone, phone as remote, sign out.
        TvFixture.right()
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/0"))
        repeat(3) { TvFixture.down(); Thread.sleep(250) }
        compose.awaitFocusOn(hasTestTag("$TAG_SETTINGS/setting/3"))
        TvFixture.ok()
        compose.awaitFocusOn(hasTestTag("${com.prism.music.ui.tv.TAG_ACTIONS}/0"), 10_000)
        TvFixture.ok()
        compose.waitUntil(10_000) { !c.settings.current.isLoggedIn }
        assertTrue(c.library.playlists.value.isEmpty())
    }

    @Test fun anUpdateShowsOnHomeAndTheRemoteReachesIt() {
        RecordedNetwork.answers["https://api.github.com/repos/Yigder/Prism/releases/latest"] =
            """{"tag_name":"v9.9.9","html_url":"https://github.com/Yigder/Prism/releases/tag/v9.9.9",
               "assets":[{"name":"Prism.apk","browser_download_url":"https://github.com/Yigder/Prism/releases/download/v9.9.9/Prism.apk"}]}"""
        Fixture.ready()
        kotlinx.coroutines.runBlocking { Fixture.container.updates.checkNow() }
        TvFixture.launch()
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("$TAG_HOME/update"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Home's first item: Search while it's empty, the first card once its rows come (focus follows when Search goes,
        // never left on the side navigation), the hero's "Now playing" if a song is left from another test. Up: Update.
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/search") or hasTestTag("$TAG_HOME/row0/0") or hasTestTag("$TAG_HOME/player"), 15_000)
        if (compose.onAllNodes(hasTestTag("$TAG_HOME/search") and androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            Thread.sleep(3_000) // the rows may still be on their way: let them come, so Up starts from where focus settles
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/search") or hasTestTag("$TAG_HOME/row0/0") or hasTestTag("$TAG_HOME/player"), 10_000)
        compose.waitForIdle()
        TvFixture.up()
        compose.awaitFocusOn(hasTestTag("$TAG_HOME/update"))
        assertTrue(Fixture.container.updates.available.value?.version == "9.9.9")
    }
}

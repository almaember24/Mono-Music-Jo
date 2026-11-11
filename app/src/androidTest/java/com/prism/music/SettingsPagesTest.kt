package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every Settings page opens and scrolls to its end without crashing (1.7.9's check: Downloads, storage & data crashed
 * as soon as its Smart Downloads estimate passed 1,000 songs, "1,363" read back as a number). Pages are lazy, so each
 * one is scrolled all the way down: a group below the fold is only drawn then.
 */
@RunWith(AndroidJUnit4::class)
class SettingsPagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private val device by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    /** Hub page → the pages inside it (opened from it). */
    private val pages = linkedMapOf(
        "Customize Prism" to listOf("Looks", "Color", "Text", "Icons & shapes", "Glass & backgrounds", "Tabs & mini player", "Home & Library", "Player", "Lyrics"),
        "Playback & sound" to emptyList(),
        "Lyrics & Sing" to emptyList(),
        "Controls & gestures" to emptyList(),
        "Recommendations & Replay" to emptyList(),
        "Downloads, storage & data" to emptyList(),
        "Devices & connections" to listOf("Speakers, TVs & browser", "Discord", "Last.fm"),
        "Language" to emptyList(),
        "Backup & about" to listOf("Open-source licenses"),
    )

    @Test fun everySettingsPageOpensAndScrolls() {
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        walk(pages)
    }

    /**
     * The same in Spanish (#255: 1.7.9's Icons & shapes closed Prism in every language but English, its examples asking
     * for drawings by their translated names, "Inicio"). Prism reads every word through [Strings.resources], so the
     * Spanish ones are handed to it before it starts; page titles come from the same place.
     */
    @Test fun everySettingsPageOpensInSpanish() = walkIn("es")

    /**
     * And in Arabic's words (longer ones, six plural forms). Not laid out right to left: Android applies a per-app
     * language by restarting the app, and here the app is the test's own process, so that part is checked by hand.
     */
    @Test fun everySettingsPageOpensInArabic() = walkIn("ar")

    /**
     * #208 (1.7.9.2): with "Slide between screens" on, pages slide in and Back slides them out: every page still
     * opens, and Back still lands on the page before (a slide left half-way would leave the hub unreachable).
     */
    @Test fun pagesSlideInAndBackSlidesThemOut() {
        Fixture.ready()
        kotlinx.coroutines.runBlocking { Fixture.container.settings.setPageSlide(true) }
        try {
            Fixture.launch()
            compose.openSettings()
            walk(linkedMapOf("Customize Prism" to listOf("Color", "Player"), "Controls & gestures" to emptyList()))
            compose.clickWhenFound(hasText("Downloads, storage & data") and hasClickAction(), 15_000)
        } finally {
            kotlinx.coroutines.runBlocking { Fixture.container.settings.setPageSlide(false) }
        }
    }

    private fun walkIn(tag: String) {
        val ctx = Fixture.context
        val config = android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
        Strings.resources = ctx.createConfigurationContext(config).resources
        try {
            Fixture.ready()
            Fixture.launch()
            compose.openSettings(tr(R.string.settings))
            walk(linkedMapOf(
                tr(R.string.customize_prism) to listOf(
                    R.string.looks, R.string.color_2, R.string.text, R.string.icons_shapes, R.string.glass_backgrounds,
                    R.string.tabs_mini_player, R.string.home_library, R.string.player, R.string.lyrics,
                ).map { tr(it) },
                tr(R.string.playback_sound) to emptyList(),
                tr(R.string.lyrics_sing) to emptyList(),
                tr(R.string.controls_gestures) to emptyList(),
                tr(R.string.recommendations_replay) to emptyList(),
                tr(R.string.downloads_storage_data) to emptyList(),
                tr(R.string.devices_connections) to listOf(tr(R.string.speakers_tvs_browser), tr(R.string.discord), "Last.fm"),
                tr(R.string.language) to emptyList(),
                tr(R.string.backup_about) to listOf(tr(R.string.open_source_licenses)),
            ))
        } finally {
            Strings.resources = null
        }
    }

    private fun walk(pages: Map<String, List<String>>) {
        for ((hub, inside) in pages) {
            open(hub)
            scrollToEnd()
            for (page in inside) {
                open(page)
                scrollToEnd()
                back()
            }
            // Back on the hub (kept where it was scrolled; the next page's row is scrolled to when opened).
            back()
        }
    }

    private fun open(title: String) {
        compose.clickWhenFound(hasText(title) and hasClickAction(), 15_000)
        compose.waitForIdle()
    }

    /** Swipes the page up until nothing new comes into view (at most 40 swipes). */
    private fun scrollToEnd() {
        var last = ""
        repeat(40) {
            val pageNodes = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
            if (pageNodes.isEmpty()) return
            compose.onAllNodes(hasScrollToNodeAction())[0].performTouchInput { swipeUp() }
            compose.waitForIdle()
            val now = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
                .joinToString("|") { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() ?: "" }
            if (now == last) return
            last = now
        }
    }

    private fun back() {
        device.pressBack()
        compose.waitForIdle()
    }
}

package com.prism.music

import com.prism.music.ui.screens.SettingsIndex
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings search (#73) only finds what [SettingsIndex] lists: every setting row and group on the settings pages has to
 * be there, so a new setting without a line in the index fails here. Since 1.7.9 the pages read their words from
 * strings.xml (`tr(R.string.…)`), so each row's words are looked up there (in English, as the tests run).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class SettingsIndexTest {
    private val sources = listOf("SettingsScreen.kt", "SoundSettings.kt", "BackupUi.kt", "ConnectSettings.kt", "ColorSettings.kt", "LookSettings.kt", "SpotifySettings.kt", "HistorySettings.kt")
        .map { File("src/main/java/com/prism/music/ui/screens/$it").readText() }

    /** strings.xml's English: name → text. */
    private val strings: Map<String, String> by lazy {
        Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File("src/main/res/values/strings.xml").readText())
            .associate { it.groupValues[1] to I18nConvert.xmlUnescape(it.groupValues[2]) }
    }

    private fun rows(calls: String) = sources.flatMap { src ->
        Regex("""\b(?:$calls)\(\s*tr\(R\.string\.(\w+)""").findAll(src).map { m ->
            // "Space to use: %1$s" is listed by its fixed start.
            strings.getValue(m.groupValues[1]).substringBefore("%1\$s").trim().removeSuffix(":")
        }.toList()
    }.filter { it.isNotBlank() }.distinct()

    @Test
    fun everyRowIsSearchable() {
        val rows = rows("Toggle|Item|SliderSetting|Label")
        assertTrue("the pages' rows were read (${rows.size})", rows.size > 100)
        val missing = rows.filter { row -> SettingsIndex.entries.none { it.title == row || row.startsWith(it.title) } }
        assertTrue("Not in SettingsIndex: $missing", missing.isEmpty())
    }

    @Test
    fun everyGroupIsSearchable() {
        val groups = rows("Group")
        assertTrue("the pages' groups were read (${groups.size})", groups.size > 20)
        val missing = groups.filter { g -> SettingsIndex.entries.none { it.group == g } }
        assertTrue("Groups not in SettingsIndex: $missing", missing.isEmpty())
    }

    /** A setting that's hidden says what to turn on first, and stops saying it once that's on. */
    @Test
    fun hiddenSettingsSayWhatToTurnOn() {
        fun entry(id: Int) = SettingsIndex.entries.first { it.title == com.prism.music.tr(id) }
        val plain = com.prism.music.data.prefs.AppSettings(frostedGlass = false)
        val frost = entry(R.string.glass_frost).needs
        assertTrue("Frost's hint", frost != null && !frost.on(plain))
        assertTrue(frost!!.on(plain.copy(frostedGlass = true)))
        assertTrue(frost.on(plain.copy(liquidGlass = true)))
        for (id in listOf(R.string.lock_screen_layout_label, R.string.lock_screen_show, R.string.lock_screen_paused_label, R.string.lock_screen_hide, R.string.lock_screen_allow, R.string.lock_screen_allow_step, R.string.lock_screen_app_info)) {
            // By its group too: other pages have a "Layout".
            val needs = SettingsIndex.entries.first { it.title == com.prism.music.tr(id) && it.group == com.prism.music.tr(R.string.lock_screen_title) }.needs
            assertTrue(com.prism.music.tr(id), needs != null && !needs.on(plain))
            assertTrue(com.prism.music.tr(id), needs!!.on(plain.copy(behavior = plain.behavior.copy(lockScreen = true))))
        }
        // The lock screen's own switch isn't hidden by anything.
        assertTrue(entry(R.string.lock_screen_title).needs == null)
    }

    /** Its search result opens Home & Library, group Home: the row has to be there, not only under Customize Home. */
    @Test
    fun quickPicksFromIsWhereItsResultOpens() {
        val e = SettingsIndex.entries.first { it.title == com.prism.music.tr(R.string.quick_picks_from) }
        assertTrue(e.section == com.prism.music.ui.screens.SettingsSection.HOME && e.group == com.prism.music.tr(R.string.home))
        val page = File("src/main/java/com/prism/music/ui/screens/SettingsScreen.kt").readText()
        val group = page.substringAfter("private fun HomeLibraryLook()").substringAfter("Group(tr(R.string.home))").substringBefore("Group(")
        assertTrue("Quick picks from isn't in that group", "R.string.quick_picks_from" in group)
    }

    @Test
    fun findsBySynonymAndTitle() {
        assertTrue(SettingsIndex.search("vibration").any { it.title == "Haptics" })
        assertTrue(SettingsIndex.search("crossfade").first().title.startsWith("Crossfade"))
        assertTrue(SettingsIndex.search("eq").any { it.title == "Equalizer" })
        assertTrue(SettingsIndex.search("zzzz").isEmpty())
    }
}

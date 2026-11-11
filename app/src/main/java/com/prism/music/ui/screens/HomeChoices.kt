package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.tr

/** The round buttons at the top of Home (1.8.0): Podcasts and Customize as one choice. */
enum class TopButtons(val label: String) {
    BOTH(tr(R.string.podcasts_and_customize)), PODCASTS(tr(R.string.podcasts)), CUSTOMIZE(tr(R.string.customize)), NONE(tr(R.string.none))
}

/** Home & Library's merged choices, over the settings Prism already keeps (homePodcastsButton, homeCustomizeTopButton). */
object HomeChoices {
    fun topButtons(s: AppSettings): TopButtons = when {
        s.homePodcastsButton && s.homeCustomizeTopButton -> TopButtons.BOTH
        s.homePodcastsButton -> TopButtons.PODCASTS
        s.homeCustomizeTopButton -> TopButtons.CUSTOMIZE
        else -> TopButtons.NONE
    }

    /** homePodcastsButton, homeCustomizeTopButton. */
    fun topButtonsValues(t: TopButtons): Pair<Boolean, Boolean> = when (t) {
        TopButtons.BOTH -> true to true
        TopButtons.PODCASTS -> true to false
        TopButtons.CUSTOMIZE -> false to true
        TopButtons.NONE -> false to false
    }

    fun setTopButtons(prefs: SettingsRepository, t: TopButtons) {
        val (podcasts, customize) = topButtonsValues(t)
        prefs.setHomePodcastsButton(podcasts)
        prefs.setHomeCustomizeTopButton(customize)
    }
}

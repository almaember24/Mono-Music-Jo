package com.prism.music.data.looks

import android.content.Context
import java.time.LocalDate

/**
 * Holiday Mode (1.8.0): the first time Prism is opened in a seasonal month (after setting up and the tour), it asks once
 * whether to put the season's Look on. Either answer, or closing the question, counts as asked: it doesn't come back for
 * that season that year; next year it asks once more. Kept in the "prompts" preferences with Prism's other one-time
 * questions (and in backups with them).
 */
object SeasonPrompt {
    private const val PREFS = "prompts"
    private const val ASKED = "season_asked"

    /** The key of [season]'s question in [date]'s year ("HARVEST:2026"). */
    fun key(season: Season, date: LocalDate): String = "${season.name}:${date.year}"

    /**
     * The season to ask about on [date], or null: none when it isn't a seasonal month, it's been asked this season
     * ([asked]), Prism isn't set up yet ([onboarded]), or the listener is already on the season's Look ([currentLook]).
     */
    fun due(date: LocalDate, asked: Set<String>, onboarded: Boolean, currentLook: String?): Season? {
        val season = Seasons.current(date) ?: return null
        if (!onboarded || key(season, date) in asked || currentLook == season.lookId) return null
        return season
    }

    fun asked(context: Context): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(ASKED, emptySet()).orEmpty()

    /** Records [season]'s question as asked for [date]'s year, whatever the answer. */
    fun markAsked(context: Context, season: Season, date: LocalDate) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(ASKED, asked(context) + key(season, date)).apply()
    }
}

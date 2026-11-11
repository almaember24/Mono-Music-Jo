package com.prism.music.data.innertube

import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PodcastItem
import com.prism.music.data.model.Shelf
import com.prism.music.data.model.SongItem
import kotlinx.serialization.json.JsonElement

/** A country YouTube Music has charts for ("ZZ" is its "Global"). [code] is what's sent back to ask for it. */
data class ChartCountry(val code: String, val name: String)

/** The charts of one country, and the countries YouTube offers. */
data class ChartsPage(val selected: ChartCountry?, val countries: List<ChartCountry>, val shelves: List<Shelf>)

/**
 * YouTube Music's Explore and Charts pages as they really come (checked live 2026-11-05, signed out; US, BR, GB, Global,
 * es-MX, ja). Which shelves there are changes with the country (the US has Genres and Top albums, Brazil only Video charts
 * and Top artists) and their names with the language, so shelves are told apart by what's in them, never by title, and
 * nothing here expects any one of them.
 */
object Explore {
    /** The moods and genres shelf's items; other "moods" on Explore are its buttons to pages of their own. */
    private const val MOOD_PAGE = "FEmusic_moods_and_genres_category"

    /** Explore's own buttons (New releases, Charts, Moods & genres): this page is what they lead to. */
    private fun isNavigation(s: Shelf) = s.items.all { it is MoodItem && it.id.startsWith("FEmusic_") && it.id != MOOD_PAGE }

    /** Podcast shows and episodes: Prism's Podcasts page has those. */
    private fun isPodcasts(s: Shelf) = s.items.any { it is PodcastItem || (it is SongItem && it.song.isEpisode) }

    /** What's on the page to show, in YouTube's order. */
    fun music(shelves: List<Shelf>): List<Shelf> = shelves.filter { it.items.isNotEmpty() && !isNavigation(it) && !isPodcasts(it) }

    fun isMoods(s: Shelf) = s.items.isNotEmpty() && s.items.all { it is MoodItem }

    /** Reads the charts answer: its shelves and, from the menu above them, the countries. */
    fun charts(res: JsonElement): ChartsPage {
        // The menu names each country's choice by a key, and what to send for it is in the choice that key stands for.
        val choices = res.findAll("musicFormBooleanChoice").mapNotNull { c -> c.str("id")?.let { it to c } }.toMap()
        val countries = res.findAll("musicMultiSelectMenuItemRenderer").mapNotNull { m ->
            val name = m.obj("title").text() ?: return@mapNotNull null
            val code = choices[m.str("formItemEntityKey")]?.str("opaqueToken") ?: return@mapNotNull null
            ChartCountry(code, name)
        }.distinctBy { it.code }
        val shown = res.findAll("musicSortFilterButtonRenderer").firstNotNullOfOrNull { it.obj("title").text() }
        val selectedCode = choices.values.firstOrNull { it.str("selected") == "true" }?.str("opaqueToken")
        val selected = countries.firstOrNull { it.code == selectedCode } ?: countries.firstOrNull { it.name == shown }
        return ChartsPage(selected, countries, music(Parser.shelves(res)))
    }
}

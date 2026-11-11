package com.prism.music.ui.tv

import com.prism.music.data.prefs.HomeSectionConfig
import com.prism.music.data.prefs.HomeSections

/*
 * The TV's Home and its layout (1.8.0; the owner: "You should be able to customize the homescreen", then "I do not see
 * things like Prism Music mixes or Quick picks etc. that match my phone app"). Every phone Home section a TV can show
 * is a row here, by the phone's own key ([TvHomeLayout.CATALOG], plus pinned items and rules, "pin:*" / "rule:*", and
 * YouTube's shelves), so one layout drives both:
 * - Which rows show: the phone's own Home layout ([com.prism.music.data.prefs.AppSettings.homeLayout]). A row it names
 *   shows or hides as it says (a row hidden on one is hidden on the other); a row it doesn't name shows only if the TV's
 *   Home always had it (the hero, Quick picks, Speed dial, Recently played, and YouTube's shelves while the phone adds
 *   them, `autoAddSections`). The TV's own now-playing hero is shown or hidden in the TV's layout
 *   ([com.prism.music.data.prefs.TvPrefs.homeLayout]).
 * - Their order: the TV's layout once the TV's editor has been used; before that the phone's order (a fresh TV mirrors
 *   the phone), with the rows the phone doesn't place after, in [TvHomeLayout.CATALOG]'s order, then YouTube's.
 * Rows with nothing in them (no plays yet for On repeat) leave themselves out.
 * Back to default: no TV layout, and the rows the TV's Home always had shown again.
 */

/** A TV row's key in the phone's Home layout (the TV's rows use the phone's keys; "yt:<title>" is the older form). */
fun phoneKeyOf(tvKey: String): String = when {
    tvKey.startsWith(HomeSections.YT_PREFIX) -> tvKey
    tvKey.startsWith("yt:") -> HomeSections.shelfKey(tvKey.removePrefix("yt:"))
    else -> tvKey
}

/** Whether [layout] hides the row [tvKey]. */
fun hiddenIn(layout: List<HomeSectionConfig>, tvKey: String): Boolean = layout.firstOrNull { it.key == phoneKeyOf(tvKey) }?.visible == false

/**
 * [rows] as [layout] arranges them: those it names, in its order, in the places those rows had; the others where they
 * were. Hidden rows are kept ([hiddenIn] tells them apart).
 */
fun <R> arrangeTvRows(rows: List<R>, layout: List<HomeSectionConfig>, key: (R) -> String): List<R> {
    val order = layout.withIndex().associate { (i, s) -> s.key to i }
    val named = rows.withIndex().filter { (_, r) -> phoneKeyOf(key(r)) in order }
    if (named.size < 2) return rows
    val sorted = named.map { it.value }.sortedBy { order.getValue(phoneKeyOf(key(it))) }
    val out = rows.toMutableList()
    named.forEachIndexed { n, (slot, _) -> out[slot] = sorted[n] }
    return out
}

/** [layout] with the row [tvKey] shown or hidden ([shown]); a row it doesn't name yet is added (after the others). */
fun layoutWithShown(layout: List<HomeSectionConfig>, tvKey: String, title: String, shown: Boolean): List<HomeSectionConfig> {
    val k = phoneKeyOf(tvKey)
    return if (layout.any { it.key == k }) layout.map { if (it.key == k) it.copy(visible = shown) else it }
    else layout + HomeSectionConfig(k, title, visible = shown)
}

/** [order] with the item at [from] moved [by] places (−1 up, +1 down), held within the list. */
fun <T> moved(order: List<T>, from: Int, by: Int): List<T> {
    if (from !in order.indices) return order
    val to = (from + by).coerceIn(0, order.lastIndex)
    if (to == from) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

/** The TV Home as laid out: its [rows] in order, and where the now-playing hero goes among them (-1: hidden). */
data class TvHomeArranged(val rows: List<TvHomeRow>, val heroAt: Int)

/**
 * The TV's own Home layout: a list of the phone's [HomeSectionConfig]s in the TV's order, with [HERO] for the
 * now-playing hero (its `visible` is the hero's; the other rows' are the phone layout's, see the file's note). A row the
 * layout doesn't know yet (a new shelf from YouTube) shows after the row it follows on Home.
 */
object TvHomeLayout {
    /** The TV's now-playing hero (the phone has no such section). */
    const val HERO = "tv:now_playing"

    /**
     * Every phone Home section the TV has a row for, in the order they come when the phone's layout doesn't place them.
     * Pinned items and rules ("pin:*", "rule:*") and YouTube's shelves come after. The phone's Moods & genres tiles open
     * their page on the TV too; nothing of the phone's Home is left out.
     */
    val CATALOG = listOf(
        HomeSections.REPLAY, HomeSections.GREETING, HomeSections.QUICK_PICKS, HomeSections.SEASONAL, HomeSections.SPEED_DIAL, HomeSections.MADE_FOR_YOU,
        HomeSections.NEW_RELEASES, HomeSections.ON_REPEAT, HomeSections.REPEAT_REWIND, HomeSections.FORGOTTEN, HomeSections.RECENT,
        HomeSections.LIKED, HomeSections.GENRES, HomeSections.MOODS, HomeSections.DOWNLOADS,
    )
    /** The rows Prism makes itself: the hero, then [CATALOG]. */
    val FIXED = listOf(HERO) + CATALOG
    /** Shown when the phone's layout doesn't name them: the rows the TV's Home always had. */
    val SHOWN_UNNAMED = setOf(HERO, KEY_PICKS, KEY_DIAL, KEY_RECENT)
    /** How many rows a layout keeps. */
    private const val MAX_KEPT = 80

    /** A section of the listener's own: a pinned album, playlist, artist or mix, or a rule over their listening. */
    fun isOwn(key: String) = key.startsWith(HomeSections.PIN_PREFIX) || key.startsWith("rule:")

    /** Whether [key] is one of the TV Home's kinds of row (not a phone-only section). */
    fun isTvRow(key: String) = key in FIXED || key.startsWith(HomeSections.YT_PREFIX) || isOwn(key)

    private fun heroShown(stored: List<HomeSectionConfig>) = stored.firstOrNull { it.key == HERO }?.visible != false

    /**
     * Whether the row [key] shows: the hero as the TV's [stored] layout says; the others as the [phone]'s layout says, and
     * when it doesn't name them, if the TV's Home always had them ([SHOWN_UNNAMED]; YouTube's shelves while the phone adds
     * them, [autoAdd]).
     */
    fun isShown(key: String, stored: List<HomeSectionConfig>, phone: List<HomeSectionConfig>, autoAdd: Boolean = true): Boolean {
        if (key == HERO) return heroShown(stored)
        phone.firstOrNull { it.key == phoneKeyOf(key) }?.let { return it.visible }
        return key in SHOWN_UNNAMED || (autoAdd && key.startsWith(HomeSections.YT_PREFIX))
    }

    /**
     * Home's own order of the rows there are ([rows], in [CATALOG]'s order then YouTube's, see [tvAllRows]): the hero,
     * then the ones the [phone]'s layout places in its order, then the rest as they came.
     */
    fun natural(rows: List<HomeSectionConfig>, phone: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val byKey = rows.associateBy { it.key }
        val hero = byKey[HERO] ?: HomeSectionConfig(HERO, "")
        return (listOf(hero) + phone.mapNotNull { byKey[phoneKeyOf(it.key)] } + rows).distinctBy { it.key }
    }

    /** [rows] (from [tvAllRows]) as the TV's [stored] layout and the [phone]'s Home layout lay them out. */
    fun arrange(stored: List<HomeSectionConfig>, rows: List<TvHomeRow>, phone: List<HomeSectionConfig> = emptyList(), autoAdd: Boolean = true): TvHomeArranged {
        val natural = natural(rows.map { HomeSectionConfig(it.key, it.title) }, phone)
        val keys = (if (stored.isEmpty()) natural else merged(stored, natural)).map { it.key }
        val byKey = rows.groupBy { it.key }
        val out = ArrayList<TvHomeRow>()
        var heroAt = -1
        for (k in keys) {
            if (!isShown(k, stored, phone, autoAdd)) continue
            if (k == HERO) heroAt = out.size else byKey[k]?.let { out += it }
        }
        return TvHomeArranged(out, heroAt)
    }

    /**
     * [stored] with each row of [natural] it doesn't have put in after the row before it in [natural], so a new shelf
     * lands where Home would have put it; rows it has take [natural]'s title (YouTube may recase it).
     */
    fun merged(stored: List<HomeSectionConfig>, natural: List<HomeSectionConfig>): List<HomeSectionConfig> {
        val titles = natural.associate { it.key to it.title }
        val out = stored.distinctBy { it.key }.map { s -> titles[s.key]?.takeIf { it.isNotBlank() }?.let { s.copy(title = it) } ?: s }.toMutableList()
        for ((i, n) in natural.withIndex()) {
            if (out.any { it.key == n.key }) continue
            val before = (i - 1 downTo 0).map { natural[it].key }.firstOrNull { k -> out.any { it.key == k } }
            out.add(if (before == null) 0 else out.indexOfFirst { it.key == before } + 1, n.copy(visible = true))
        }
        return out
    }

    /**
     * The editor's list: every row the TV can have ([available]: the whole [CATALOG], the phone's own sections, YouTube's
     * shelves now), in the order Home shows them, each shown or hidden as Home has it. Empty rows are listed too, so
     * they can be turned on before they have anything in them.
     */
    fun editable(
        stored: List<HomeSectionConfig>, available: List<HomeSectionConfig>, phone: List<HomeSectionConfig> = emptyList(), autoAdd: Boolean = true,
    ): List<HomeSectionConfig> {
        val natural = natural(available, phone)
        val there = natural.map { it.key }.toSet()
        val base = if (stored.isEmpty()) natural else merged(stored, natural).filter { it.key in there }
        return base.map { it.copy(visible = isShown(it.key, stored, phone, autoAdd)) }
    }

    /**
     * Every row the TV can have, for the editor: the hero, [CATALOG], the [phone]'s pinned items and rules (by their
     * names there), then [youtube]'s shelves. [title] names the hero and [CATALOG]'s rows.
     */
    fun available(youtube: List<HomeSectionConfig>, phone: List<HomeSectionConfig>, title: (String) -> String): List<HomeSectionConfig> =
        (FIXED.map { HomeSectionConfig(it, title(it)) } + phone.filter { isOwn(it.key) }.map { HomeSectionConfig(it.key, it.name ?: it.title) } + youtube)
            .distinctBy { it.key }

    /** What the TV's layout keeps for the editor's [list]. */
    fun saved(list: List<HomeSectionConfig>): List<HomeSectionConfig> = list.distinctBy { it.key }.take(MAX_KEPT)

    /** [list] with [key] shown if it was hidden, hidden if it was shown. */
    fun toggled(list: List<HomeSectionConfig>, key: String): List<HomeSectionConfig> =
        list.map { if (it.key == key) it.copy(visible = !it.visible) else it }

    /**
     * [list] with [key] carried past [by] of the rows Home shows right now ([onHome]; up is negative), so each press of
     * Up or Down moves it on Home: the rows Home doesn't show (hidden, empty, out of season) are passed on the way, and
     * it stops at either end.
     */
    fun movedPast(list: List<HomeSectionConfig>, key: String, by: Int, onHome: (String) -> Boolean): List<HomeSectionConfig> {
        val from = list.indexOfFirst { it.key == key }
        if (from < 0 || by == 0) return list
        val rest = list.toMutableList().apply { removeAt(from) }
        val step = if (by < 0) -1 else 1
        var left = kotlin.math.abs(by)
        // Where it goes among the others (an index into [rest]), one row passed at a time.
        var at = from
        while (left > 0) {
            val next = if (step < 0) at - 1 else at
            if (next !in rest.indices) break
            at = if (step < 0) next else next + 1
            if (onHome(rest[next].key)) left--
        }
        // Going up it lands right before the last row it passed; going down, right after it.
        rest.add(at.coerceIn(0, rest.size), list[from])
        return rest
    }

    /** [list] with [key] moved [by] places (up is negative), stopping at either end. */
    fun moved(list: List<HomeSectionConfig>, key: String, by: Int): List<HomeSectionConfig> =
        com.prism.music.ui.tv.moved(list, list.indexOfFirst { it.key == key }, by)

    /**
     * The [phone]'s layout with the rows the TV's Home always had shown again (Back to default): Quick picks, Speed dial,
     * Recently played and YouTube's shelves. The other sections stay as the phone has them.
     */
    fun allShown(phone: List<HomeSectionConfig>): List<HomeSectionConfig> =
        phone.map { if (!it.visible && (it.key in SHOWN_UNNAMED || it.key.startsWith(HomeSections.YT_PREFIX))) it.copy(visible = true) else it }

    /** How many of the TV's rows a layout hides (Settings' line under Customize Home). */
    fun hiddenCount(stored: List<HomeSectionConfig>, phone: List<HomeSectionConfig>): Int =
        (if (heroShown(stored)) 0 else 1) + phone.count { !it.visible && isTvRow(it.key) }
}

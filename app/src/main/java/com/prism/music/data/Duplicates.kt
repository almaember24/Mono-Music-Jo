package com.prism.music.data

import com.prism.music.data.model.Song
import kotlin.math.abs

/**
 * The same song twice in a list (1.8.3): the same id, or the same title and artist with lengths within 2 s (a video
 * and its song, two uploads of one recording). A live take, a remix, an acoustic or sped-up version is another song.
 */
object Duplicates {
    /** One song found more than once: the copy kept (a song before a video, else the first), and the extra ones. */
    data class Group(val keep: Song, val extras: List<Song>)

    /** Words in brackets that only say what kind of upload it is: the same recording either way. */
    private val UPLOAD_WORDS = Regex(
        """\s*[(\[]\s*(official\s*)?(music\s*)?(video|audio|lyric(s)?(\s*video)?|visuali[sz]er|mv|m/v|hd|hq|4k|explicit|clean)\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    /** Words that make it another version, wherever they are in the title. */
    private val VERSION_WORDS = Regex(
        """\b(live|remix|mix|acoustic|unplugged|instrumental|karaoke|cover|sped\s*up|slowed|nightcore|reverb|demo|edit|version|remaster(ed)?|8d|extended|piano|orchestral)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The artist without spaces or a channel's "VEVO" / " - Topic" ("TheKillersVEVO" is The Killers). */
    private fun artistKey(s: Song) = LibrarySearch.norm(s.primaryArtist.removeSuffix("VEVO").removeSuffix(" - Topic")).replace(" ", "")

    /** The title as the same recording would be named anywhere ("Artist - Title (Official Video)" → "title"). */
    internal fun titleKey(s: Song): String {
        var t = s.title.replace(UPLOAD_WORDS, " ")
        // A video's "Artist - Title".
        val dash = t.indexOf(" - ")
        if (dash > 0 && LibrarySearch.norm(t.substring(0, dash)).replace(" ", "") == artistKey(s)) t = t.substring(dash + 3)
        return LibrarySearch.norm(t)
    }

    private fun versions(s: Song) = VERSION_WORDS.findAll(s.title).map { it.value.lowercase().replace(Regex("\\s+"), "") }.toSortedSet()

    /** Whether [a] and [b] are the same song. */
    fun same(a: Song, b: Song): Boolean {
        if (a.id == b.id) return true
        if (a.durationSec <= 0 || b.durationSec <= 0 || abs(a.durationSec - b.durationSec) > 2) return false
        val ta = titleKey(a)
        return ta.isNotEmpty() && ta == titleKey(b) && artistKey(a) == artistKey(b) && versions(a) == versions(b)
    }

    /** The songs of [songs] found more than once, in the order they first come. */
    fun find(songs: List<Song>): List<Group> {
        val groups = ArrayList<MutableList<Song>>()
        // Most songs differ in title: only those with the same title and artist are compared with each other.
        val byKey = HashMap<String, MutableList<MutableList<Song>>>()
        val byId = HashMap<String, MutableList<Song>>()
        for (s in songs) {
            byId[s.id]?.let { it += s; continue }
            val key = titleKey(s) + "\u0000" + artistKey(s)
            val near = byKey.getOrPut(key) { ArrayList() }
            val g = near.firstOrNull { g -> same(g.first(), s) }
            if (g != null) { g += s; byId[s.id] = g; continue }
            val fresh = mutableListOf(s)
            near += fresh
            groups += fresh
            byId[s.id] = fresh
        }
        return groups.filter { it.size > 1 }.map { g ->
            val keep = g.firstOrNull { !it.isVideo } ?: g.first()
            // The kept one's own other copies (the same id) go too, all but one.
            val extras = ArrayList(g)
            extras.remove(keep)
            Group(keep, extras)
        }
    }
}

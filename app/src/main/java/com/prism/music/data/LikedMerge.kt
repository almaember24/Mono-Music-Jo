package com.prism.music.data

import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.model.Song

/**
 * GitHub #111: a song liked twice, once as the song and once as its music video ("Feel Good Inc." by Gorillaz, De La Soul
 * and its video by Gorillaz), shows once in the liked lists, as the song. Only the lists change: the video stays liked
 * (unlike the song and the video shows again), and nothing is sent to YouTube.
 *
 * A video goes when a liked song is known to be its counterpart ([pairs], song id → video id, as YouTube pairs them while
 * Prism plays), or when one has the same title (once the video's own words and brackets are gone) and an artist in common.
 */
object LikedMerge {
    /** [liked] without the videos whose song is also there, in the same order. */
    fun merge(liked: List<Song>, pairs: Map<String, String> = emptyMap()): List<Song> {
        val videos = liked.filter { it.isVideo }
        if (videos.isEmpty()) return liked
        val songs = liked.filter { !it.isVideo }
        val pairedVideos = songs.mapNotNull { pairs[it.id] }.toSet()
        val byTitle = songs.groupBy { titleKey(it.title) }
        val gone = videos.filter { v ->
            v.id in pairedVideos || videoKeys(v.title).any { k -> byTitle[k].orEmpty().any { s -> shareAnArtist(s, v) } }
        }.map { it.id }.toSet()
        return if (gone.isEmpty()) liked else liked.filter { it.id !in gone }
    }

    private val BRACKETS = Regex("\\(.*?\\)|\\[.*?]")
    private val FEAT = Regex("^(feat|ft|featuring|with)\\b.*", RegexOption.IGNORE_CASE)
    /** "Remastered 2009" is the same recording, freshened: its video is still its video. */
    private val REMASTER = Regex("\\bremaster")
    private val WORDS = Regex("\\b(${YouTubeMusic.VIDEO_WORDS.joinToString("|")})\\b")

    /**
     * A title as a song and its video share it: brackets that only describe the video ("(Official Video)", "[HD]") or
     * credit a guest ("(feat. Pharrell)") go, other words in brackets stay ("(Live at …)" is another recording), and
     * only letters and digits count.
     */
    fun titleKey(title: String): String = title.lowercase()
        .replace(BRACKETS) { m ->
            val inside = m.value.trim('(', ')', '[', ']', ' ')
            if (FEAT.matches(inside) || REMASTER.containsMatchIn(inside) || inside.split(Regex("\\s+")).all { it in YouTubeMusic.VIDEO_WORDS }) " " else m.value
        }
        .replace(Regex("\\s-\\s[^-]*remaster[^-]*$"), " ")
        .replace(Regex("\\s(feat|ft|featuring)\\.?\\s.*$"), " ")
        .replace(WORDS, " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** A video's title as it is, and (for "Artist - Title" uploads) what comes after the dash. */
    private fun videoKeys(title: String): Set<String> =
        setOfNotNull(titleKey(title), title.substringAfter(" - ", "").takeIf { it.isNotBlank() }?.let(::titleKey)).filter { it.isNotEmpty() }.toSet()

    private fun artistKey(name: String) = name.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun shareAnArtist(song: Song, video: Song): Boolean {
        val mine = song.artists.map { artistKey(it.name) }.filter { it.isNotEmpty() } + artistKey(song.primaryArtist)
        val theirs = video.artists.map { artistKey(it.name) }.filter { it.isNotEmpty() } + artistKey(video.primaryArtist)
        // VEVO channels name the artist with "VEVO" on the end; "Gorillaz" is in "Gorillaz, De La Soul".
        return mine.any { m -> m.isNotEmpty() && theirs.any { t -> t.isNotEmpty() && (t.removeSuffix("vevo") == m || m.contains(t.removeSuffix("vevo")) || t.contains(m)) } }
    }
}

package com.prism.music.data

import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.ArtistItem
import com.prism.music.data.model.BrowseItem
import com.prism.music.data.model.MoodItem
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem

/**
 * music.youtube.com links for what Prism shares. They open in YouTube Music (or Prism, which
 * handles them too) for whoever gets them.
 */
object ShareLinks {
    private const val BASE = "https://music.youtube.com"

    fun song(videoId: String) = "$BASE/watch?v=$videoId"
    fun album(browseId: String) = "$BASE/browse/$browseId"
    fun playlist(id: String) = "$BASE/playlist?list=${id.removePrefix("VL")}"
    fun artist(channelId: String) = "$BASE/channel/$channelId"

    /** The link for any card; null for Prism's own lists and for moods (pages of shelves). */
    fun of(item: BrowseItem): String? = when (item) {
        is SongItem -> song(item.id)
        is AlbumItem -> album(item.id)
        // Radios and mixes are made for whoever opens them, so there's nothing stable to send.
        is PlaylistItem -> if (item.isMix || item.id.startsWith("RD")) null else playlist(item.id)
        is ArtistItem -> artist(item.id)
        // A show's address on YouTube Music is its episodes' playlist.
        is com.prism.music.data.model.PodcastItem -> playlist(item.playlistId)
        is MoodItem -> null
    }

    /**
     * [url] (one of the links above) as a YouTube one, for listeners who'd rather send those (Reddit R9): a song as a
     * youtu.be link, a playlist or artist on youtube.com. An album has no page on YouTube, so it stays YouTube Music's.
     */
    fun asYouTube(url: String): String {
        if (!url.startsWith(BASE)) return url
        val rest = url.removePrefix(BASE)
        return when {
            rest.startsWith("/watch?v=") -> "https://youtu.be/" + rest.removePrefix("/watch?v=").substringBefore('&')
            rest.startsWith("/playlist?list=") || rest.startsWith("/channel/") -> "https://www.youtube.com$rest"
            else -> url
        }
    }

    /**
     * The first YouTube or YouTube Music link in [text] (what other apps put in a share), as the
     * music.youtube.com address Prism opens. youtu.be and youtube.com watch links count too.
     */
    fun findIn(text: String?): String? {
        val raw = Regex("https?://\\S+").findAll(text ?: return null).map { it.value.trimEnd('.', ',', ')', '"', '\'') }
            .firstOrNull { u -> listOf("youtube.com", "youtu.be").any { u.contains(it, ignoreCase = true) } } ?: return null
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { p ->
            val k = p.substringBefore('=', "")
            if (k.isBlank()) null else k to java.net.URLDecoder.decode(p.substringAfter('='), "UTF-8")
        }.toMap()
        val v = query["v"]?.takeIf { it.isNotBlank() }
        val list = query["list"]?.takeIf { it.isNotBlank() }
        return when {
            host == "youtu.be" -> path.firstOrNull()?.let(::song)
            host == "music.youtube.com" -> raw
            !host.endsWith("youtube.com") -> null
            v != null -> song(v)
            path.firstOrNull() == "shorts" && path.size > 1 -> song(path[1])
            list != null -> playlist(list)
            path.firstOrNull() == "channel" && path.size > 1 -> artist(path[1])
            else -> null
        }
    }
}

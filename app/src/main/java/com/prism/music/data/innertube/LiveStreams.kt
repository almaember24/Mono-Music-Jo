package com.prism.music.data.innertube

import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.serialization.json.JsonElement

/**
 * Live streams from youtube.com's own pages (YouTube Music lists no live filter and no live tab):
 * search with the "Live" filter, and a channel's Live tab. Only streams on air now are kept; past
 * broadcasts and scheduled ones are dropped.
 */
object LiveStreams {
    /** youtube.com search's "Live" filter. */
    const val SEARCH_PARAMS = "EgJAAQ=="
    /** A channel's Live tab. */
    const val CHANNEL_TAB_PARAMS = "EgdzdHJlYW1z8gYECgJ6AA=="

    /** Every stream on air in a search or channel response; [owner] names the channel when items don't. */
    fun parse(res: JsonElement?, owner: ArtistRef? = null): List<Song> {
        val fromRenderers = res.findAll("videoRenderer").mapNotNull { video(it, owner) }
        val fromLockups = res.findAll("lockupViewModel").mapNotNull { lockup(it, owner) }
        return (fromRenderers + fromLockups).distinctBy { it.id }
    }

    /** The token for the next page of results, if there is one. */
    fun continuation(res: JsonElement?): String? =
        res.findAll("continuationCommand").lastOrNull()?.str("token")

    private fun video(r: JsonElement, owner: ArtistRef?): Song? {
        val id = r.str("videoId") ?: return null
        if (r.obj("upcomingEventData") != null) return null
        val badges = r.arr("badges").orEmpty().mapNotNull { it.str("metadataBadgeRenderer", "style") }
        val overlays = r.arr("thumbnailOverlays").orEmpty().mapNotNull { it.str("thumbnailOverlayTimeStatusRenderer", "style") }
        val onAir = "BADGE_STYLE_TYPE_LIVE_NOW" in badges || "LIVE" in overlays ||
            (r.obj("lengthText") == null && r.obj("viewCountText").text()?.contains("watching", true) == true)
        if (!onAir) return null
        val title = r.obj("title").text()?.takeIf { it.isNotBlank() } ?: return null
        val run = (r.obj("ownerText") ?: r.obj("longBylineText") ?: r.obj("shortBylineText")).runs().firstOrNull()
        val artist = run?.str("text")?.let { ArtistRef(it, run.str("navigationEndpoint", "browseEndpoint", "browseId")) } ?: owner
        return song(id, title, artist)
    }

    /** The newer layout some pages use: a lockup with a LIVE badge on its thumbnail. */
    private fun lockup(r: JsonElement, owner: ArtistRef?): Song? {
        if (r.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = r.str("contentId") ?: return null
        val badges = r.obj("contentImage").findAll("thumbnailBadgeViewModel")
        val live = badges.any { b -> b.str("badgeStyle")?.contains("LIVE") == true || b.str("text").equals("LIVE", true) }
        if (!live) return null
        val meta = r.obj("metadata", "lockupMetadataViewModel")
        val title = meta.str("title", "content")?.takeIf { it.isNotBlank() } ?: return null
        // The first metadata part is the channel's name (absent on the channel's own pages).
        val name = meta.findFirst("metadataParts").at(0).str("text", "content")?.takeIf { it.isNotBlank() }
        val artist = if (name == null || name == owner?.name) owner else ArtistRef(name)
        return song(id, title, artist)
    }

    private fun song(id: String, title: String, artist: ArtistRef?) = Song(
        id = id,
        title = title,
        artists = listOfNotNull(artist),
        thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        isVideo = true,
        isLive = true,
    )
}

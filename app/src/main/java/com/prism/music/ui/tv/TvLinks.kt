package com.prism.music.ui.tv

import android.net.Uri
import com.prism.music.data.model.AlbumItem
import com.prism.music.data.model.PlaylistItem

/**
 * A link opened on the TV (a music.youtube.com link, a YouTube link shared to Prism, the media notification's
 * prism://player): what it opens there, as the phone's links do ([com.prism.music.MainActivity]).
 */
sealed interface TvLink {
    /** A song: it plays, and the player opens. */
    data class Song(val id: String) : TvLink
    /** A page: an album, a playlist, an artist, or the player. */
    data class Page(val route: TvRoute) : TvLink
}

/** What [uri] opens on the TV, or null for a link the TV has no place for. */
fun tvLinkFor(uri: Uri): TvLink? {
    val v = uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }
    val list = uri.getQueryParameter("list")?.takeIf { it.isNotBlank() }
    val path = uri.pathSegments.orEmpty()
    return when {
        uri.scheme == "prism" && uri.host == "player" -> TvLink.Page(TvRoute.Player)
        uri.scheme == "prism" -> null
        uri.host == "youtu.be" && path.isNotEmpty() -> TvLink.Song(path[0])
        v != null -> TvLink.Song(v)
        list != null -> TvLink.Page(TvRoute.Collection(PlaylistItem(list, "", "", null)))
        path.firstOrNull() == "browse" && path.size > 1 && path[1].startsWith("MPRE") -> TvLink.Page(TvRoute.Collection(AlbumItem(path[1], "", "", null)))
        path.firstOrNull() == "channel" && path.size > 1 -> TvLink.Page(TvRoute.Artist(path[1]))
        else -> null
    }
}

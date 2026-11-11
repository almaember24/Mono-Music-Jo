package com.prism.music.playback

import com.prism.music.data.model.Song

/**
 * The album a playing song came without (#312: songs from Speed dial had no album to go to), from YouTube's queue for
 * it, whose first item is the song itself as YouTube Music's player lists it.
 */
object AlbumFill {
    /** Worth asking for: a song from YouTube Music with no album (videos, episodes, live streams and files have none). */
    fun wanted(song: Song): Boolean =
        song.album?.id == null && !song.isVideo && !song.isEpisode && !song.isLive && !song.isLocal && !song.isUpload

    /** [song] with the album [found] lists for it, or null when it lists none (or not this song). */
    fun withAlbum(song: Song, found: List<Song>): Song? {
        val album = found.firstOrNull { it.id == song.id }?.album?.takeIf { it.id != null && it.title.isNotBlank() } ?: return null
        return song.copy(album = album)
    }
}

/**
 * A music video playing as its own sound (no song of its length was found to play in its place) shows its song's cover
 * once that's found (Reddit 1.7.9); #328: its song's album too, when the video has none, so the player's title and
 * "Go to album" lead to it. Its title, artists and length stay the video's: that's what plays.
 */
object VideoCover {
    fun withSong(video: Song, song: Song, cover: String): Song = video.copy(
        thumbnail = cover,
        album = video.album?.takeIf { it.id != null } ?: song.album?.takeIf { it.id != null && it.title.isNotBlank() } ?: video.album,
    )
}

package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.Parser
import com.prism.music.data.model.NextResult
import com.prism.music.data.model.Song
import com.prism.music.data.model.SongItem
import com.prism.music.playback.PlayerConnection
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #383 (1.8.3): a playlist from Home played many videos with their own sound under "Songs, not videos: Everywhere", some
 * with the podcast controls. YouTube files those songs' videos as podcast episodes (no show, the singer linked): they're
 * read as videos now. Signed in, the playlist's own song ↔ video pairing is asked once at its start.
 */
class SongsEverywhere383Test {
    private fun row(pageTypeOfSecond: String?, secondText: String) = InnerTube.json.parseToJsonElement(
        """
        {"flexColumns":[
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Iraga Iraga","navigationEndpoint":{"browseEndpoint":{"browseId":"MPEDuMY2gEsWXdw","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"}}}}}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$secondText"${pageTypeOfSecond?.let { ""","navigationEndpoint":{"browseEndpoint":{"browseId":"UCx","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$it"}}}}""" } ?: ""}}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Jul 20, 2018"}]}}}],
         "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"4:24"}]}}}],
         "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":{"watchEndpoint":{"videoId":"uMY2gEsWXdw","playlistId":"PLx","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}}}}}},
         "playlistItemData":{"videoId":"uMY2gEsWXdw"}}
        """.trimIndent(),
    ) as JsonObject

    @Test fun aSongsVideoFiledAsAnEpisodeIsReadAsAVideoWithItsArtist() {
        val song = (Parser.responsive(row("MUSIC_PAGE_TYPE_ARTIST", "Mohana Bhogaraju")) as SongItem).song
        assertFalse("not an episode (no episode controls)", song.isEpisode)
        assertTrue("a video, so Songs, not videos can swap it", song.isVideo)
        assertEquals("Mohana Bhogaraju", song.primaryArtist)
        assertEquals(264, song.durationSec)
    }

    @Test fun theSingerUnlinkedToo() {
        val song = (Parser.responsive(row(null, "Mukesh")) as SongItem).song
        assertFalse(song.isEpisode)
        assertEquals("Mukesh", song.primaryArtist)
    }

    @Test fun aRealEpisodeNamingItsShowStaysOne() {
        val song = (Parser.responsive(row("MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE", "The Show")) as SongItem).song
        assertTrue(song.isEpisode)
        assertEquals("The Show", song.primaryArtist)
    }

    @Test fun thePlaylistsPairingGivesEachVideosSong() {
        val v1 = Song("v1", "A", isVideo = true); val s1 = Song("s1", "A")
        val v2 = Song("v2", "B", isVideo = true)
        val r = NextResult(listOf(v1, v2, s1), null, null, mapOf("v1" to "s1"), mapOf("v1" to s1, "s1" to v1))
        assertEquals(mapOf("v1" to s1), r.videoSongs())
    }

    @Test fun pairingIsAskedOnlyForAPlaylistSignedInWithEverywhere() {
        val pl = QueueSource(QueueKind.PLAYLIST, "Telugu", originId = "VLPLEwl")
        assertEquals("PLEwl", PlayerConnection.playlistToPair(everywhere = true, pl, signedIn = true))
        assertNull(PlayerConnection.playlistToPair(everywhere = false, pl, signedIn = true))
        assertNull(PlayerConnection.playlistToPair(everywhere = true, pl, signedIn = false))
        assertNull(PlayerConnection.playlistToPair(everywhere = true, pl.copy(kind = QueueKind.ALBUM), signedIn = true))
    }
}

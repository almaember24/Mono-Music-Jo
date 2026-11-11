package com.prism.music

import com.prism.music.data.innertube.str
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.1, from Reddit: "Couldn't add to library" on an album (Constant-Insect-863, 1.7.9.1, Miki Matsubara's "WINK"), and
 * podcasts' "New episodes" opening empty though it showed a count and a length (thedarkknight_sl).
 */
class AlbumLibraryAndEpisodesTest {
    /** An album page whose canonical address and whose library button name different playlists. */
    private val albumPage = """{"contents":{"x":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"WINK"}]},
        "straplineTextOne":{"runs":[{"text":"Miki Matsubara"}]},
        "buttons":[{"toggleButtonRenderer":{"isToggled":false,
            "defaultServiceEndpoint":{"likeEndpoint":{"status":"LIKE","target":{"playlistId":"OLAK5uy_button"}}},
            "toggledServiceEndpoint":{"likeEndpoint":{"status":"INDIFFERENT","target":{"playlistId":"OLAK5uy_button"}}}}}]}},
        "y":{"musicShelfRenderer":{"contents":[${PlaylistEditsTest.row("t1")}]}}},
        "microformat":{"microformatDataRenderer":{"urlCanonical":"https://music.youtube.com/playlist?list=OLAK5uy_canonical&feature=share"}}}"""

    @Test fun anAlbumIsSavedByWhatItsLibraryButtonSaves() = runBlocking {
        val yt = FakeYouTube { endpoint, _ -> if (endpoint == "browse") 200 to albumPage else 200 to "{}" }
        val page = yt.ytm.album("MPREb_wink")
        assertEquals("OLAK5uy_button", page.libraryId)
        // A query after the list's id isn't part of it.
        assertEquals("OLAK5uy_canonical", page.playlistId)
    }

    @Test fun anAlbumCardWithoutAPlaylistIdIsSavedByItsPage() = runBlocking {
        // A card on Home or in Search carries only the album's browse id; sent as a playlist id, YouTube refused it.
        val yt = FakeYouTube { endpoint, _ -> if (endpoint == "browse") 200 to albumPage else 200 to "{}" }
        yt.ytm.setLibrarySaved("MPREb_wink", true)
        val like = yt.sentTo("like/like").single()
        assertEquals("OLAK5uy_button", like.str("target", "playlistId"))
    }

    @Test fun newEpisodesListsItsEpisodes() = runBlocking {
        val episode = { id: String ->
            """{"musicMultiRowListItemRenderer":{"title":{"runs":[{"text":"Episode $id"}]},
                "onTap":{"watchEndpoint":{"videoId":"$id"}},
                "secondTitle":{"runs":[{"text":"The Show","navigationEndpoint":{"browseEndpoint":{"browseId":"MPSPshow","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"}}}}}]},
                "subtitle":{"runs":[{"text":"Nov 1"}]}}}"""
        }
        val page = """{"contents":{"x":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"New episodes"}]},
            "secondSubtitle":{"runs":[{"text":"2 episodes • 1 hour"}]}}},
            "y":{"musicShelfRenderer":{"contents":[${episode("e1")},${episode("e2")}]}}}}"""
        val yt = FakeYouTube { endpoint, body -> if (endpoint == "browse" && body.str("browseId") == "VLRDPN") 200 to page else 404 to "{}" }
        val list = yt.ytm.playlist(com.prism.music.data.innertube.YouTubeMusic.NEW_EPISODES)
        assertEquals(listOf("e1", "e2"), list.songs.map { it.id })
        assertTrue(list.songs.all { it.isEpisode })
        assertTrue(com.prism.music.data.innertube.YouTubeMusic.isEpisodeList("RDPN"))
    }
}

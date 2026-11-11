package com.prism.music

import com.prism.music.data.innertube.Parser
import com.prism.music.data.model.PlaylistItem
import com.prism.music.data.model.SongItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's station cards ("Station • Bring Me The Horizon, Oasis…") start a radio; song cards stay songs. */
class StationParseTest {
    private fun card(subtitle: String, endpoint: String): JsonObject = Json.parseToJsonElement(
        """{"title":{"runs":[{"text":"Listen together"}]},"subtitle":{"runs":[{"text":"$subtitle"}]},
           "navigationEndpoint":{"watchEndpoint":$endpoint}}""",
    ) as JsonObject

    @Test
    fun stationIsARadioWithItsSeed() {
        val item = Parser.twoRow(card("Station • Bring Me The Horizon, Oasis", """{"videoId":"abc123","playlistId":"RDEMxyz","params":"wAEB"}"""))
        assertTrue(item is PlaylistItem)
        item as PlaylistItem
        assertTrue(item.isMix)
        assertEquals("RDEMxyz", item.id)
        assertEquals("abc123", item.seedVideoId)
        assertEquals("wAEB", item.params)
    }

    @Test
    fun songWithItsOwnRadioIsStillASong() {
        val item = Parser.twoRow(card("Song • Oasis", """{"videoId":"abc123","playlistId":"RDAMVMabc123"}"""))
        assertTrue(item is SongItem)
        val plain = Parser.twoRow(card("Oasis", """{"videoId":"abc123"}"""))
        assertTrue(plain is SongItem)
        val albumTrack = Parser.twoRow(card("Song • Oasis", """{"videoId":"abc123","playlistId":"OLAK5uy_abc"}"""))
        assertTrue(albumTrack is SongItem)
    }

    /** #266: a Speed dial song card whose play starts another kind of radio is still the song, not a station. */
    @Test
    fun aCardThatSaysSongIsASongWhateverRadioItStarts() {
        assertTrue(Parser.twoRow(card("Song • Oasis", """{"videoId":"abc123","playlistId":"RDEMxyz"}""")) is SongItem)
        assertTrue(Parser.twoRow(card("Video • Oasis", """{"videoId":"abc123","playlistId":"RDCLAKxyz"}""")) is SongItem)
        // A radio card without a word for its kind is still taken for a station.
        assertTrue(Parser.twoRow(card("Oasis, Blur", """{"videoId":"abc123","playlistId":"RDEMxyz"}""")) is PlaylistItem)
    }
}

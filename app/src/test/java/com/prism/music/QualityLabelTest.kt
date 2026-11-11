package com.prism.music

import com.prism.music.data.ModelStore
import com.prism.music.playback.NerdStats
import com.prism.music.playback.fileFormatName
import com.prism.music.ui.player.qualityText
import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's quality badge and the size wording (bug check 16, 17). */
class QualityLabelTest {
    @Test fun localWavNamesItsFormatAndResolution() {
        // A WAV on the phone reached the badge as "AUDIO/RAW 705 kbps".
        val wav = NerdStats(source = "On this phone · WAV", codec = "audio/raw", streamBitrateKbps = 1411, sampleRate = 44_100, bitDepth = 16)
        assertEquals("WAV · 16-bit · 44.1 kHz", qualityText(wav))
    }

    @Test fun losslessFilesAndStreams() {
        assertEquals("FLAC · 96 kHz", qualityText(NerdStats(source = "Lossless · FLAC on this phone", codec = "flac", sampleRate = 96_000)))
        assertEquals("FLAC · 48 kHz", qualityText(NerdStats(source = "On this phone · FLAC", codec = "flac", sampleRate = 48_000)))
        assertEquals("Opus 160 kbps", qualityText(NerdStats(source = "Streaming", codec = "opus", streamBitrateKbps = 160)))
        assertEquals("MP3 320 kbps", qualityText(NerdStats(source = "On this phone · MP3", codec = "audio/mpeg", streamBitrateKbps = 320)))
        assertEquals("Downloaded", qualityText(NerdStats(source = "Downloaded", codec = "opus")))
    }

    @Test fun fileFormatsByMime() {
        assertEquals("WAV", fileFormatName("audio/x-wav"))
        assertEquals("WAV", fileFormatName("audio/wav"))
        assertEquals("MP3", fileFormatName("audio/mpeg"))
        assertEquals("FLAC", fileFormatName("audio/flac"))
        assertEquals("AIFF", fileFormatName("audio/x-aiff"))
    }

    @Test fun singSizeSaysDownloadAndRoom() {
        assertEquals(
            "49 MB download, 73 MB on the phone",
            ModelStore.Model.sizeText(ModelStore.Model.SING_MODEL, ModelStore.Model.SING_RUNTIME_ARM64),
        )
    }
}

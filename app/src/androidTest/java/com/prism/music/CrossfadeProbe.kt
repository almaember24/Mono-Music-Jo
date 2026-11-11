package com.prism.music

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.data.innertube.SearchFilter
import com.prism.music.data.model.SongItem
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #364's probe, not part of the suite (live, slow, and it only logs): real songs over the real network, a crossfade after
 * each, read back from logcat (PrismMix, PrismDiag with `setprop log.tag.PrismDiag DEBUG`). Songs are unscanned when the
 * blend is planned, as the first time a listener plays them. Media volume must be 0 on the device.
 */
@RunWith(AndroidJUnit4::class)
@Live
class CrossfadeProbe {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val player get() = Fixture.container.player
    private val settings get() = Fixture.container.settings

    private fun blends(
        auto: Boolean,
        blends: Int,
        beats: Boolean = false,
        queries: List<String> = listOf("Blinding Lights The Weeknd", "Dreams Fleetwood Mac", "Levitating Dua Lipa", "Mr. Brightside The Killers", "Take on Me a-ha", "Billie Jean Michael Jackson", "Espresso Sabrina Carpenter", "Beat It Michael Jackson"),
    ) {
        Fixture.ready()
        runBlocking {
            settings.setMix(true); settings.setMixAuto(auto); settings.setMixSeconds(8); settings.setMixBeatMatch(beats)
            settings.setAudioDiagnostics(true)
        }
        Fixture.launch()
        val c = Fixture.container
        val songs = runBlocking {
            queries.mapNotNull { q -> runCatching { c.ytm.search(q, SearchFilter.SONGS).items.filterIsInstance<SongItem>().firstOrNull()?.song }.getOrNull() }.distinctBy { it.id }
        }
        android.util.Log.i("PrismMix", "probe: ${songs.size} songs, auto $auto")
        instrumentation.runOnMainSync { player.playQueue(songs, 0, QueueSource(QueueKind.OTHER, "Crossfade probe")) }
        fun <T> onMain(read: () -> T): T { var out: T? = null; instrumentation.runOnMainSync { out = read() }; @Suppress("UNCHECKED_CAST") return out as T }
        repeat(blends) { n ->
            val from = System.currentTimeMillis()
            compose.waitUntil(60_000) { onMain { player.isPlaying.value && player.duration > 30_000 } }
            val id = onMain { player.currentSong.value?.id }
            val dur = onMain { player.duration }
            // A few seconds to take in the start, as a listener would have long before the end; then near the end.
            Thread.sleep(3_000)
            instrumentation.runOnMainSync { player.seekTo(dur - 30_000) }
            android.util.Log.i("PrismMix", "probe: blend $n after $id, $dur ms long, loaded in ${System.currentTimeMillis() - from} ms")
            compose.waitUntil(60_000) { onMain { player.currentSong.value?.id != id } }
            // Through the blend.
            Thread.sleep(16_000)
        }
        instrumentation.runOnMainSync { player.stop() }
        runBlocking { settings.setMix(false) }
    }

    @Test fun fixedLength() = blends(auto = false, blends = 5)

    @Test fun automaticLength() = blends(auto = true, blends = 3)

    /** Automatic length and "Match tempo and beats": the next song stretched, and nudged during the blend. */
    @Test fun onTheBeat() = blends(auto = true, blends = 4, beats = true)

    /** Songs [fixedLength] hasn't left in the song cache, for a run on a slow connection (the cached ones load at once). */
    @Test fun otherSongs() = blends(auto = false, blends = 4, queries = listOf("Bohemian Rhapsody Queen", "Hotel California Eagles", "Smells Like Teen Spirit Nirvana", "Rolling in the Deep Adele", "Shape of You Ed Sheeran", "Uptown Funk Bruno Mars"))
}

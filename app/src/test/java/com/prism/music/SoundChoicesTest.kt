package com.prism.music

import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AutoplaySource
import com.prism.music.data.prefs.GlassKind
import com.prism.music.ui.screens.Crossfade
import com.prism.music.ui.screens.GlassChoice
import com.prism.music.ui.screens.LookChoices
import com.prism.music.ui.screens.ScreenChange
import com.prism.music.ui.screens.QueueEnd
import com.prism.music.ui.screens.SoundChoices
import com.prism.music.ui.screens.TrimSilence
import com.prism.music.ui.screens.VideoPolicy
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.0 (#210): Playback & sound's merged choices are faces over the settings Prism already keeps. Every old combination
 * of those settings shows as the right choice (nobody's setup changes on the update), and every choice written reads
 * back as itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SoundChoicesTest {
    private val base = AppSettings()

    /**
     * A merged choice is two or three writes; quick taps one after another land in the order they were made (the
     * repository writes one at a time). Everywhere then Play once read back as Radios when the writes interleaved.
     */
    @Test fun quickChoicesLandInOrder() {
        val repo = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        val was = kotlinx.coroutines.runBlocking { repo.flow.first() }
        try {
            repeat(20) {
                SoundChoices.setVideoPolicy(repo, VideoPolicy.EVERYWHERE)
                SoundChoices.setVideoPolicy(repo, VideoPolicy.PLAY)
                SoundChoices.setTrimSilence(repo, TrimSilence.ALL)
                SoundChoices.setTrimSilence(repo, TrimSilence.PODCASTS)
            }
            val settled = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(10_000) {
                    repo.flow.first { SoundChoices.videoPolicy(it) == VideoPolicy.PLAY && SoundChoices.trimSilence(it) == TrimSilence.PODCASTS }
                }
            }
            // Nothing written after it: still there a moment later.
            Thread.sleep(300)
            val now = kotlinx.coroutines.runBlocking { repo.flow.first() }
            assertEquals(VideoPolicy.PLAY, SoundChoices.videoPolicy(now))
            assertEquals(TrimSilence.PODCASTS, SoundChoices.trimSilence(now))
            assertEquals(SoundChoices.videoPolicy(settled), SoundChoices.videoPolicy(now))
        } finally {
            SoundChoices.setVideoPolicy(repo, SoundChoices.videoPolicy(was))
            SoundChoices.setTrimSilence(repo, SoundChoices.trimSilence(was))
            Thread.sleep(300)
        }
    }

    @Test fun whenTheQueueEndsFollowsAutoplayAndItsSource() {
        assertEquals(QueueEnd.STOP, SoundChoices.queueEnd(base.copy(autoplay = false, autoplaySource = AutoplaySource.YOURS)))
        assertEquals(QueueEnd.YOURS, SoundChoices.queueEnd(base.copy(autoplay = true, autoplaySource = AutoplaySource.YOURS)))
        assertEquals(QueueEnd.MIXED, SoundChoices.queueEnd(base.copy(autoplay = true, autoplaySource = AutoplaySource.MIXED)))
        assertEquals(QueueEnd.YOUTUBE, SoundChoices.queueEnd(base.copy(autoplay = true, autoplaySource = AutoplaySource.YOUTUBE)))
        for (q in QueueEnd.entries) {
            val (on, source) = SoundChoices.queueEndValues(q)
            val after = base.copy(autoplay = on, autoplaySource = source ?: base.autoplaySource)
            assertEquals(q, SoundChoices.queueEnd(after))
        }
        // Stop keeps the source, so turning autoplay back on gives the old one.
        assertEquals(null, SoundChoices.queueEndValues(QueueEnd.STOP).second)
    }

    @Test fun musicVideosFollowSongsNotVideosAndEverywhere() {
        assertEquals(VideoPolicy.PLAY, SoundChoices.videoPolicy(base.copy(radioSongsOnly = false, songsEverywhere = false)))
        // An old "Everywhere" left on under a switched-off "Songs, not videos" did nothing: it still reads as Play them.
        assertEquals(VideoPolicy.PLAY, SoundChoices.videoPolicy(base.copy(radioSongsOnly = false, songsEverywhere = true)))
        assertEquals(VideoPolicy.RADIOS, SoundChoices.videoPolicy(base.copy(radioSongsOnly = true, songsEverywhere = false)))
        assertEquals(VideoPolicy.EVERYWHERE, SoundChoices.videoPolicy(base.copy(radioSongsOnly = true, songsEverywhere = true)))
        for (v in VideoPolicy.entries) {
            val (radios, everywhere) = SoundChoices.videoPolicyValues(v)
            assertEquals(v, SoundChoices.videoPolicy(base.copy(radioSongsOnly = radios, songsEverywhere = everywhere)))
        }
    }

    @Test fun crossfadeFollowsMixAndAutomaticLength() {
        assertEquals(Crossfade.OFF, SoundChoices.crossfade(base.copy(mix = false, mixAuto = true)))
        assertEquals(Crossfade.OFF, SoundChoices.crossfade(base.copy(mix = false, mixAuto = false)))
        assertEquals(Crossfade.AUTO, SoundChoices.crossfade(base.copy(mix = true, mixAuto = true)))
        assertEquals(Crossfade.FIXED, SoundChoices.crossfade(base.copy(mix = true, mixAuto = false)))
        for (x in Crossfade.entries) {
            val (on, auto) = SoundChoices.crossfadeValues(x)
            assertEquals(x, SoundChoices.crossfade(base.copy(mix = on, mixAuto = auto ?: base.mixAuto)))
        }
        // Off keeps automatic/fixed, so turning crossfade back on gives the old way.
        assertEquals(null, SoundChoices.crossfadeValues(Crossfade.OFF).second)
    }

    @Test fun homeTopButtonsFollowBothSwitches() {
        fun s(p: Boolean, cz: Boolean) = base.copy(homePodcastsButton = p, homeCustomizeTopButton = cz)
        assertEquals(com.prism.music.ui.screens.TopButtons.BOTH, com.prism.music.ui.screens.HomeChoices.topButtons(s(true, true)))
        assertEquals(com.prism.music.ui.screens.TopButtons.PODCASTS, com.prism.music.ui.screens.HomeChoices.topButtons(s(true, false)))
        assertEquals(com.prism.music.ui.screens.TopButtons.CUSTOMIZE, com.prism.music.ui.screens.HomeChoices.topButtons(s(false, true)))
        assertEquals(com.prism.music.ui.screens.TopButtons.NONE, com.prism.music.ui.screens.HomeChoices.topButtons(s(false, false)))
        for (t in com.prism.music.ui.screens.TopButtons.entries) {
            val (p, cz) = com.prism.music.ui.screens.HomeChoices.topButtonsValues(t)
            assertEquals(t, com.prism.music.ui.screens.HomeChoices.topButtons(s(p, cz)))
        }
    }

    @Test fun glassReadsAsGlassPathDoes() {
        fun s(liquid: Boolean, kind: GlassKind, frosted: Boolean) = base.copy(liquidGlass = liquid, glassKind = kind, frostedGlass = frosted)
        assertEquals(GlassChoice.OFF, LookChoices.glass(s(false, GlassKind.REGULAR, false)))
        assertEquals(GlassChoice.FROSTED, LookChoices.glass(s(false, GlassKind.CLEAR, true)))
        // Liquid wins over frosted, as glassPath does.
        assertEquals(GlassChoice.LIQUID_REGULAR, LookChoices.glass(s(true, GlassKind.REGULAR, true)))
        assertEquals(GlassChoice.LIQUID_CLEAR, LookChoices.glass(s(true, GlassKind.CLEAR, false)))
        for (g in GlassChoice.entries) {
            val v = LookChoices.glassValues(g)
            assertEquals(g, LookChoices.glass(s(v.liquid, v.kind ?: GlassKind.REGULAR, v.frosted ?: false)))
        }
    }

    @Test fun changingScreensFollowsFadesAndSlide() {
        fun s(fades: Boolean, slide: Boolean) = base.copy(behavior = base.behavior.copy(screenFades = fades, pageSlide = slide))
        assertEquals(ScreenChange.NONE, LookChoices.screenChange(s(false, false)))
        // Slide never showed with fades off: still None.
        assertEquals(ScreenChange.NONE, LookChoices.screenChange(s(false, true)))
        assertEquals(ScreenChange.FADE, LookChoices.screenChange(s(true, false)))
        assertEquals(ScreenChange.SLIDE, LookChoices.screenChange(s(true, true)))
        for (c in ScreenChange.entries) {
            val (fades, slide) = LookChoices.screenChangeValues(c)
            assertEquals(c, LookChoices.screenChange(s(fades, slide)))
        }
    }

    @Test fun trimSilenceFollowsSongsAndPodcasts() {
        fun s(songs: Boolean, episodes: Boolean) = base.copy(skipSilence = songs, sound = base.sound.copy(skipSilenceEpisodes = episodes))
        assertEquals(TrimSilence.OFF, SoundChoices.trimSilence(s(false, false)))
        assertEquals(TrimSilence.PODCASTS, SoundChoices.trimSilence(s(false, true)))
        assertEquals(TrimSilence.ALL, SoundChoices.trimSilence(s(true, true)))
        // #325: songs on with podcasts off is what it says.
        assertEquals(TrimSilence.SONGS, SoundChoices.trimSilence(s(true, false)))
        for (t in TrimSilence.entries) {
            val (songs, episodes) = SoundChoices.trimSilenceValues(t)
            assertEquals(t, SoundChoices.trimSilence(s(songs, episodes)))
        }
    }

    /** #325: "Songs only" leaves an episode's pauses alone; "Podcasts only" and "Songs and podcasts" shorten them, and never a song's. */
    @Test fun onlyPodcastsPicksShortenAnEpisodesPauses() {
        val pauses = com.prism.music.playback.EdgeSilenceProcessor
        for (t in TrimSilence.entries) {
            val (_, episodes) = SoundChoices.trimSilenceValues(t)
            assertEquals(t.name, t == TrimSilence.PODCASTS || t == TrimSilence.ALL, pauses.pausesSkipped(isEpisode = true, episodesOn = episodes))
            assertEquals(t.name, false, pauses.pausesSkipped(isEpisode = false, episodesOn = episodes))
        }
    }

    /** #325: the choice nobody changed is still Off, and "Songs only" is stored as songs on, podcasts off. */
    @Test fun songsOnlyIsStoredAsItReads() {
        assertEquals(TrimSilence.OFF, SoundChoices.trimSilence(base))
        val repo = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        SoundChoices.setTrimSilence(repo, TrimSilence.SONGS)
        val songs = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { repo.flow.first { SoundChoices.trimSilence(it) == TrimSilence.SONGS } } }
        assertEquals(true, songs.skipSilence)
        assertEquals(false, songs.sound.skipSilenceEpisodes)
        SoundChoices.setTrimSilence(repo, TrimSilence.OFF)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { repo.flow.first { SoundChoices.trimSilence(it) == TrimSilence.OFF } } }
    }

    /** #351: a fixed crossfade goes up to 30 s and is kept, and its tail holds the whole of it. */
    @Test fun aCrossfadeCanRunToThirtySeconds() {
        assertEquals(30, com.prism.music.data.prefs.MAX_CROSSFADE_SECONDS)
        assertEquals(6, base.mixSeconds)
        val tailHolds = com.prism.music.playback.MixEngine.TAIL_BUFFER_MS
        org.junit.Assert.assertTrue(com.prism.music.playback.MixEngine.tailNeedsMs(30_000) <= tailHolds)
        val repo = com.prism.music.data.prefs.SettingsRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        )
        val was = kotlinx.coroutines.runBlocking { repo.flow.first() }.mixSeconds
        fun readBack(wanted: Int) = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { repo.flow.first { it.mixSeconds == wanted } } }.mixSeconds
        repo.setMixSeconds(30); assertEquals(30, readBack(30))
        repo.setMixSeconds(45); assertEquals(30, readBack(30))
        repo.setMixSeconds(was); assertEquals(was, readBack(was))
    }
}

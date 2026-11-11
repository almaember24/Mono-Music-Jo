package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.AutoplaySource
import com.prism.music.data.prefs.SettingsRepository
import com.prism.music.tr

/** When the queue ends (1.8.0, #210): autoplay's switch and its source as one choice. */
enum class QueueEnd(val label: String) { STOP(tr(R.string.stop)), YOURS(tr(R.string.your_music)), MIXED(tr(R.string.both)), YOUTUBE(tr(R.string.prism_music)) }

/** Music videos (1.8.0): "Songs, not videos" and its "Everywhere" as one choice. */
enum class VideoPolicy(val label: String) { PLAY(tr(R.string.play_them)), RADIOS(tr(R.string.songs_in_radios)), EVERYWHERE(tr(R.string.songs_everywhere)) }

/** Crossfade (1.8.0, #210): its switch and "Automatic length" as one choice; Fixed shows the length slider. */
enum class Crossfade(val label: String) { OFF(tr(R.string.off)), AUTO(tr(R.string.automatic_plain)), FIXED(tr(R.string.fixed_length)) }

/** Skip silence (1.8.0, #210; "Songs only" #325): skip silence for songs and for podcast episodes as one choice. */
enum class TrimSilence(val label: String) { OFF(tr(R.string.off)), SONGS(tr(R.string.songs_only)), PODCASTS(tr(R.string.podcasts_only)), ALL(tr(R.string.songs_and_podcasts)) }

/**
 * The merged choices on Playback & sound, each read from and written to the settings Prism already keeps, so nobody's
 * choices change on the update and Looks, backups and older builds read them as before. The mapping is pure ([read]/
 * [write] pairs, SoundChoicesTest); the `set` functions only hand the values to [SettingsRepository].
 */
object SoundChoices {
    fun queueEnd(s: AppSettings): QueueEnd = if (!s.autoplay) QueueEnd.STOP else when (s.autoplaySource) {
        AutoplaySource.YOURS -> QueueEnd.YOURS
        AutoplaySource.MIXED -> QueueEnd.MIXED
        AutoplaySource.YOUTUBE -> QueueEnd.YOUTUBE
    }

    /** Autoplay on, and its source (null = leave the source as it was, so Stop then back keeps the old source). */
    fun queueEndValues(q: QueueEnd): Pair<Boolean, AutoplaySource?> = when (q) {
        QueueEnd.STOP -> false to null
        QueueEnd.YOURS -> true to AutoplaySource.YOURS
        QueueEnd.MIXED -> true to AutoplaySource.MIXED
        QueueEnd.YOUTUBE -> true to AutoplaySource.YOUTUBE
    }

    fun setQueueEnd(prefs: SettingsRepository, q: QueueEnd) {
        val (on, source) = queueEndValues(q)
        source?.let { prefs.setAutoplaySource(it) }
        prefs.setAutoplay(on)
    }

    fun videoPolicy(s: AppSettings): VideoPolicy = when {
        !s.radioSongsOnly -> VideoPolicy.PLAY
        s.songsEverywhere -> VideoPolicy.EVERYWHERE
        else -> VideoPolicy.RADIOS
    }

    /** radioSongsOnly, songsEverywhere. */
    fun videoPolicyValues(v: VideoPolicy): Pair<Boolean, Boolean> = when (v) {
        VideoPolicy.PLAY -> false to false
        VideoPolicy.RADIOS -> true to false
        VideoPolicy.EVERYWHERE -> true to true
    }

    fun setVideoPolicy(prefs: SettingsRepository, v: VideoPolicy) {
        val (radios, everywhere) = videoPolicyValues(v)
        prefs.setRadioSongsOnly(radios)
        prefs.setSongsEverywhere(everywhere)
    }

    fun crossfade(s: AppSettings): Crossfade = when {
        !s.mix -> Crossfade.OFF
        s.mixAuto -> Crossfade.AUTO
        else -> Crossfade.FIXED
    }

    /** mix, mixAuto (null = leave as it was: Off keeps whether it was automatic, for when it's turned back on). */
    fun crossfadeValues(x: Crossfade): Pair<Boolean, Boolean?> = when (x) {
        Crossfade.OFF -> false to null
        Crossfade.AUTO -> true to true
        Crossfade.FIXED -> true to false
    }

    fun setCrossfade(prefs: SettingsRepository, x: Crossfade) {
        val (on, auto) = crossfadeValues(x)
        auto?.let { prefs.setMixAuto(it) }
        prefs.setMix(on)
    }

    /**
     * #325: the two settings say it as they're stored. Songs on with podcasts off is "Songs only" again, as it was picked
     * with 1.7.8's two switches (1.8.0's one choice had no such place, and read and played it as both).
     */
    fun trimSilence(s: AppSettings): TrimSilence = when {
        s.skipSilence && s.sound.skipSilenceEpisodes -> TrimSilence.ALL
        s.skipSilence -> TrimSilence.SONGS
        s.sound.skipSilenceEpisodes -> TrimSilence.PODCASTS
        else -> TrimSilence.OFF
    }

    /** skipSilence (songs), skipSilenceEpisodes. */
    fun trimSilenceValues(t: TrimSilence): Pair<Boolean, Boolean> = when (t) {
        TrimSilence.OFF -> false to false
        TrimSilence.SONGS -> true to false
        TrimSilence.PODCASTS -> false to true
        TrimSilence.ALL -> true to true
    }

    fun setTrimSilence(prefs: SettingsRepository, t: TrimSilence) {
        val (songs, episodes) = trimSilenceValues(t)
        prefs.setSkipSilence(songs)
        prefs.setSkipSilenceEpisodes(episodes)
    }
}

package com.prism.music.playback

import com.prism.music.AppContainer

/**
 * #281 (1.8.2): "No videos on mobile data", with Data saver on. A video is by far the most data Prism can use, so it's
 * unavailable there, like a song that has no video, and one playing when Wi-Fi drops carries on as audio.
 */
object VideoOnData {
    /**
     * How long mobile data has to last before a playing video stops: moving between networks passes through a moment of
     * mobile data, or of none.
     */
    const val SETTLE_MS = 3_000L

    /**
     * Whether videos are off right now. No connection at all isn't mobile data: nothing's being spent, and the video's
     * buffer can still play.
     */
    fun off(chosen: Boolean, dataSaver: Boolean, online: Boolean, metered: Boolean): Boolean =
        chosen && dataSaver && online && metered

    /** What's kept on the phone costs no data, so a downloaded video always plays. */
    fun stops(off: Boolean, downloaded: Boolean): Boolean = off && !downloaded

    fun off(c: AppContainer): Boolean = c.settings.current.let {
        off(it.behavior.noVideoOnData, it.dataSaver, c.network.online.value, c.network.metered.value)
    }
}

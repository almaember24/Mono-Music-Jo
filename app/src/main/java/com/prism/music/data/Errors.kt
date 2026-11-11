package com.prism.music.data

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.data.innertube.InnerTubeException
import com.prism.music.data.stream.StreamException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * [read], or [fallback] when it fails, for a read of the phone's own data on a scope with nothing to catch it: the
 * playback service's, or a page's produceState, where a failure (#103's CursorWindow, on a low-memory phone) closes Prism.
 * A cancelled coroutine stays cancelled.
 */
inline fun <T> readOrElse(what: String, fallback: T, read: () -> T): T = try {
    read()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    android.util.Log.w("PrismRead", "couldn't read $what", e)
    fallback
}

/**
 * What to tell the listener when something failed: plain words for connection trouble (instead of
 * `Unable to resolve host "music.youtube.com"…`), Prism's and YouTube's own messages otherwise.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun Throwable.friendlyMessage(fallback: String = tr(R.string.something_went_wrong)): String {
    val chain = generateSequence(this) { it.cause }.take(8).toList()
    val code = chain.firstNotNullOfOrNull { (it as? InnerTubeException)?.code?.takeIf { c -> c > 0 } }
    // Media3's own watchdog words ("Player stuck playing with no progress for 10000 ms") aren't for the listener.
    val stuck = chain.firstNotNullOfOrNull { it as? androidx.media3.common.util.StuckPlayerException }
    return when {
        stuck?.stuckType == androidx.media3.common.util.StuckPlayerException.STUCK_PLAYING_NO_PROGRESS -> tr(R.string.sound_output_not_responding)
        stuck != null -> fallback
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            tr(R.string.you_re_offline_check_your_connection)
        chain.any { it is SocketTimeoutException } -> tr(R.string.prism_music_took_too_long_to)
        chain.any { it is SSLException } -> tr(R.string.couldn_t_connect_securely_on_public)
        code == 429 -> tr(R.string.youtube_is_limiting_requests_right_now)
        code != null && code >= 500 -> tr(R.string.prism_music_is_having_trouble_right)
        else -> chain.firstOrNull { it is StreamException || it is InnerTubeException }?.message?.takeIf { it.isNotBlank() }
            ?: chain.mapNotNull { it.message }.firstOrNull(::readable)
            ?: fallback
    }
}

/** A message a person can read: not a class name, stack detail or a wall of text. */
private fun readable(m: String): Boolean =
    m.isNotBlank() && m.length <= 160 && !m.contains("Exception") && !m.startsWith("java.") && m != "Source error"

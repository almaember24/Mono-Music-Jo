package com.prism.music.download

import com.prism.music.R
import com.prism.music.tr
import androidx.media3.datasource.HttpDataSource
import com.prism.music.data.friendlyMessage
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why a download failed, in words (GitHub #98: "14 downloads failed" said nothing about which, or why). Media3 hands
 * the download manager's listener the exception a download ended on; this turns it into one short line for the list
 * of failed downloads.
 */
object DownloadFailures {
    fun describe(e: Throwable?): String {
        if (e == null) return tr(R.string.it_stopped_without_saying_why)
        val chain = generateSequence(e) { it.cause }.take(10).toList()
        val http = chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }
        val messages = chain.mapNotNull { it.message?.lowercase() }
        return when {
            http != null -> when (http.responseCode) {
                403 -> tr(R.string.youtube_refused_the_download_link_403)
                404, 410 -> tr(R.string.youtube_no_longer_has_this_song, http.responseCode)
                416 -> tr(R.string.the_saved_part_didn_t_match)
                429 -> tr(R.string.youtube_asked_prism_to_slow_down)
                in 500..599 -> tr(R.string.youtube_had_trouble_answering_try_again, http.responseCode)
                else -> tr(R.string.youtube_answered_with_an_error, http.responseCode)
            }
            messages.any { "enospc" in it || "no space left" in it } -> tr(R.string.the_phone_ran_out_of_space)
            chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } -> tr(R.string.the_phone_was_offline)
            chain.any { it is SocketTimeoutException } -> tr(R.string.the_connection_timed_out)
            chain.any { it is SSLException } -> tr(R.string.couldn_t_connect_securely_public_wi)
            messages.any { "sign in" in it || "login_required" in it || "age" in it && "restrict" in it } ->
                tr(R.string.needs_a_signed_in_account_age)
            messages.any { "unavailable" in it || "not available" in it } -> tr(R.string.this_song_isn_t_available_to)
            chain.any { it is HttpDataSource.HttpDataSourceException } -> tr(R.string.the_connection_dropped_part_way)
            else -> e.friendlyMessage(if (chain.any { it is IOException }) tr(R.string.the_connection_dropped_part_way) else tr(R.string.something_went_wrong))
        }
    }
}

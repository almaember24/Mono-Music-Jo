package com.prism.music.data.stream

import com.prism.music.R
import com.prism.music.tr
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.arr
import com.prism.music.data.innertube.describePlayerAnswer
import com.prism.music.data.innertube.obj
import com.prism.music.data.innertube.str
import com.prism.music.data.prefs.AudioQuality
import kotlinx.serialization.json.JsonObject
import com.prism.music.data.prefs.SettingsRepository
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import okio.buffer
import android.util.Log
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

/** Bridges NewPipeExtractor's HTTP needs to OkHttp. */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): NpResponse {
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            request.httpMethod() == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", DESKTOP_UA)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}

/**
 * Mirrors NewPipe's YoutubeHttpDataSource on top of OkHttp: googlevideo
 * "videoplayback" requests are sent as POST with a range query parameter, a
 * request counter, and client-appropriate headers. Responses to a translated
 * range request are reported as 206 so ExoPlayer doesn't skip bytes.
 *
 * googlevideo throttles long open-ended reads to roughly playback speed, so
 * when [chunkClient] is given, any read longer than [chunkSize] is served as
 * one continuous response stitched from short range requests (the next one
 * prefetched while the current one is read), which arrive at full line speed.
 */
class YouTubeStreamInterceptor(
    private val chunkClient: OkHttpClient? = null,
    private val chunkSize: Long = 1L shl 20,
) : Interceptor {
    private val requestNumber = AtomicInteger(0)

    private fun isStream(url: HttpUrl) = url.host.endsWith("googlevideo.com") && url.encodedPath.startsWith("/videoplayback")

    /** The POST googlevideo expects, for bytes [from]..[to] (open-ended when [to] is null). */
    private fun streamRequest(base: okhttp3.Request, from: Long, to: Long?, ranged: Boolean): okhttp3.Request {
        val url = base.url
        val newUrl = url.newBuilder().setQueryParameter("rn", requestNumber.incrementAndGet().toString())
        if (ranged) newUrl.setQueryParameter("range", "$from-${to ?: ""}")
        val full = url.toString()
        val builder = base.newBuilder()
            .url(newUrl.build())
            .removeHeader("Range")
            .post(byteArrayOf(0x78, 0).toRequestBody(null))
            .header("Accept-Encoding", "identity")
            .header(
                "User-Agent",
                if (YoutubeParsingHelper.isVisionOsStreamingUrl(full)) YoutubeParsingHelper.getVisionOsUserAgent(null)
                else DESKTOP_UA,
            )
        if (YoutubeParsingHelper.isWebStreamingUrl(full)) {
            builder.header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
        }
        return builder.build()
    }

    /** googlevideo occasionally rejects a request transiently; one quick retry usually succeeds. */
    private inline fun withRetry(request: okhttp3.Request, send: (okhttp3.Request) -> Response): Response {
        val resp = send(request)
        if (resp.code != 403) return resp
        resp.close()
        Thread.sleep(350)
        return send(request.newBuilder().url(request.url.newBuilder().setQueryParameter("rn", requestNumber.incrementAndGet().toString()).build()).build())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (!isStream(url)) return chain.proceed(req)
        if (url.queryParameter("range") != null) return withRetry(streamRequest(req, 0, null, false), chain::proceed)

        val m = req.header("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        val start = m?.groupValues?.get(1)?.toLong() ?: 0L
        val clen = url.queryParameter("clen")?.toLongOrNull()
        val end = m?.groupValues?.get(2)?.toLongOrNull()?.let { e -> clen?.let { minOf(e, it - 1) } ?: e } ?: clen?.minus(1)
        // Asked from the stream's end on (#206, Reddit: "Response code: 400" after every song): googlevideo answers that
        // with a 400, which the player takes as an error, buffering and holding up the next song. HTTP's own answer is a
        // 416 naming the length, which the player reads as the end of the file.
        if (m != null && clen != null && start >= clen) {
            return Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(416).message("Range Not Satisfiable")
                .header("Content-Range", "bytes */$clen").body(ByteArray(0).toResponseBody(null)).build()
        }

        if (chunkClient != null && clen != null && end != null && end - start + 1 > chunkSize) {
            val firstEnd = minOf(end, start + chunkSize - 1)
            val first = withRetry(streamRequest(req, start, firstEnd, true), chain::proceed)
            if (!first.isSuccessful) {
                runCatching {
                    com.prism.music.playback.AudioDiagnostics.log(
                        "stream", "refused: HTTP ${first.code} at byte $start of ${url.queryParameter("id") ?: "?"} (itag ${url.queryParameter("itag")}, client ${url.queryParameter("c")})",
                    )
                }
                return first
            }
            val len = end - start + 1
            val body = ChunkedBody(first, start, end, req)
            val rb = first.newBuilder().body(body).removeHeader("Content-Length").removeHeader("Content-Range")
                .header("Content-Length", len.toString())
            return if (m != null) rb.code(206).message("Partial Content").header("Content-Range", "bytes $start-$end/$clen").build()
            else rb.code(200).message("OK").build()
        }

        val translated = m != null && !(start == 0L && m.groupValues[2].isEmpty())
        val resp = withRetry(streamRequest(req, start, m?.groupValues?.get(2)?.toLongOrNull(), translated), chain::proceed)
        if (translated && resp.code == 200) {
            val len = resp.header("Content-Length")?.toLongOrNull()
            val rb = resp.newBuilder().code(206).message("Partial Content")
            if (len != null) rb.header("Content-Range", "bytes $start-${start + len - 1}/${clen ?: "*"}")
            return rb.build()
        }
        return resp
    }

    /** Streams bytes [start]..[end] by reading short ranges back to back. */
    private inner class ChunkedBody(first: Response, start: Long, private val end: Long, private val base: okhttp3.Request) : ResponseBody() {
        private val type = first.body.contentType()
        private var pos = start
        private var current: Response? = first
        private var currentEnd = minOf(end, start + chunkSize - 1)
        private var next: Pair<Long, Future<Response>>? = null

        private fun fetch(from: Long): Future<Response> {
            val to = minOf(end, from + chunkSize - 1)
            val f = CompletableFuture<Response>()
            val call = chunkClient!!.newCall(streamRequest(base, from, to, true))
            f.whenComplete { _, _ -> if (f.isCancelled) call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { f.completeExceptionally(e) }
                override fun onResponse(call: Call, response: Response) {
                    if (response.code == 403) {
                        response.close()
                        runCatching { chunkClient.newCall(streamRequest(base, from, to, true)).execute() }
                            .onSuccess { if (!f.complete(it)) it.close() }.onFailure { f.completeExceptionally(it) }
                    } else if (!f.complete(response)) response.close()
                }
            })
            return f
        }

        /**
         * A later chunk turned away fails as a turned-away first one does (#408): as the player's own HTTP error, with its
         * code. As a plain IOException it read as a dropped connection, so playback asked the same client again rather
         * than another one, and a download kept its refused link through all its retries.
         */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun refused(resp: Response, message: String): IOException = runCatching {
            androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException(
                resp.code, resp.message, IOException(message), resp.headers.toMultimap(),
                androidx.media3.datasource.DataSpec.Builder().setUri(base.url.toString()).setPosition(pos).build(), ByteArray(0),
            )
        }.getOrElse { IOException(message) }

        private fun prefetch() {
            val from = currentEnd + 1
            if (from <= end && next == null) next = from to fetch(from)
        }

        private fun open() {
            current?.close()
            current = null
            val pending = next?.takeIf { it.first == pos }
            if (pending == null) next?.second?.cancel(true)
            next = null
            val resp = try {
                (pending?.second ?: fetch(pos)).get()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } catch (e: InterruptedException) {
                throw java.io.InterruptedIOException()
            }
            if (!resp.isSuccessful) {
                resp.close()
                // Which of YouTube's clients the link came from, and how far in (#114/#100: the first chunk played, then
                // 403 at 1 MB for one listener on every song): the diagnostics say so, so the next log can tell why.
                val u = base.url
                val what = "HTTP ${resp.code} at byte $pos of ${u.queryParameter("id") ?: "?"} (itag ${u.queryParameter("itag")}, client ${u.queryParameter("c")}, " +
                    "${if (u.queryParameter("pot") != null) "with" else "no"} PO token, chunk ${chunkSize / 1024} KB)"
                runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "refused: $what") }
                throw refused(resp, "Stream chunk at $pos failed: $what")
            }
            current = resp
            currentEnd = minOf(end, pos + chunkSize - 1)
        }

        private val source = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (pos > end) return -1
                while (true) {
                    val resp = current ?: run { open(); current!! }
                    if (next == null) prefetch()
                    val n = resp.body.source().read(sink, minOf(byteCount, end - pos + 1))
                    if (n > 0) {
                        pos += n
                        return n
                    }
                    if (pos > end) return -1
                    current?.close()
                    current = null
                }
            }

            override fun timeout() = Timeout.NONE
            override fun close() {
                current?.close()
                next?.second?.let { f -> f.cancel(true); if (f.isDone && !f.isCancelled) runCatching { f.get().close() } }
                next = null
            }
        }.buffer()

        override fun contentType() = type
        private val length = end - start + 1
        override fun contentLength() = length
        override fun source(): BufferedSource = source
    }
}

data class ResolvedStream(
    val videoId: String,
    val audioUrl: String,
    val itag: Int,
    val mimeType: String,
    val codec: String?,
    val bitrate: Int,
    val sampleRate: Int,
    val channels: Int,
    val contentLength: Long,
    val client: String?,
    val expiresAtMs: Long,
    val videoUrl: String? = null,
    val videoHeight: Int = 0,
    val videoCodec: String? = null,
    val videoFps: Int = 0,
)

class StreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The stream of [songId] (a song's id, or its music video's) couldn't be looked up. It says which: the player reports a
 * failure while reading ahead for the next song as the playing song's own ([com.prism.music.playback.readAheadFailed]).
 */
class SongStreamException(val songId: String, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Songs every one of the account's clients just refused, and no other way played (from PR #240, CodeWormer): a song is
 * asked for several times by the player, each time of five clients, so it's not asked again for [FOR_MS]. For the
 * [account] it was refused to; signing in to another one asks afresh.
 */
internal class RefusedSongs(private val now: () -> Long = System::currentTimeMillis) {
    private class Entry(val until: Long, val reason: String, val account: String)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun note(id: String, reason: String, account: String) { entries[id] = Entry(now() + FOR_MS, reason, account) }

    /** Why [id] was refused to [account] a moment ago; null: ask. */
    fun reasonFor(id: String, account: String): String? {
        val e = entries[id] ?: return null
        if (now() < e.until && e.account == account) return e.reason
        entries.remove(id, e)
        return null
    }

    fun forget(id: String) { entries.remove(id) }

    companion object { const val FOR_MS = 5 * 60_000L }
}

/** [videoId] turned out to be live: it plays from its HLS manifest (`prism://live/…`), not as a file. */
class LiveStreamException(val videoId: String) : IOException("$videoId is a live stream")

/**
 * Whether a player answer is a stream on air now. A past broadcast (many podcasts' episodes, #128) still says
 * `isLiveContent` and still carries an HLS manifest, but it has a length and no `isLive`: it plays as a normal video.
 */
internal fun isOnAir(isLive: Boolean, liveContent: Boolean, hasHls: Boolean, lengthSec: Long): Boolean =
    isLive || liveContent && hasHls && lengthSec <= 0

/**
 * Resolves playable googlevideo URLs for a video id. One direct request as YouTube's visionOS app comes first
 * ([InnerTube.visionOsPlayer], ~0.2 s); NewPipeExtractor does it anonymously when that has no plain answer; songs
 * YouTube won't play anonymously (age-restricted, Music Premium-only) are asked for again as the
 * signed-in user ([InnerTube.signedInPlayer]), with the stream URLs deciphered by YouTube's player
 * JS ([PlayerJsSolver]).
 */
class StreamResolver(
    private val context: Context,
    http: OkHttpClient,
    private val settings: SettingsRepository,
    private val innerTube: InnerTube,
) {
    /** One stream format, from either source. [url] is deciphered only when it's picked. */
    private class Fmt(
        val itag: Int,
        val mime: String,
        val codec: String?,
        val kbps: Int,
        val sampleRate: Int,
        val channels: Int,
        val contentLength: Long,
        val height: Int = 0,
        val fps: Int = 0,
        val original: Boolean = true,
        /** The real average bitrate (size over length), shown to the listener; [kbps] is YouTube's nominal one, for picking. */
        val actualKbps: Int = kbps,
        resolveUrl: () -> String,
    ) {
        val url: String by lazy(resolveUrl)
    }

    private class Formats(
        val audio: List<Fmt>,
        val videoOnly: List<Fmt>,
        val muxed: List<Fmt>,
        val durationSec: Long,
        val expiresAtMs: Long,
        /** A live stream (a YouTube Music "Listen together" station, a 24/7 radio): only playable from [hlsUrl]. */
        val live: Boolean = false,
        val hlsUrl: String? = null,
        /** Which way it was asked for (NewPipe, visionOS, or the account's client), for the diagnostics. */
        val via: String = "",
        /** The account's client it came from, when it did. */
        val client: InnerTube.PlayerClient? = null,
        /** YouTube says it's a music video or an upload, which never has Premium's formats. */
        val notASong: Boolean = false,
    )

    private val solver = PlayerJsSolver(context, http)

    /** The current player JS's signature timestamp, or null if it couldn't be fetched. Blocking. */
    fun signatureTimestamp(): Int? = runCatching { solver.currentPlayer().second }
        .onFailure { Log.w("StreamResolver", "Couldn't get YouTube's player JS: $it") }.getOrNull()
    private val cache = ConcurrentHashMap<String, Formats>()
    /** Resolutions under way, so the player and a prefetch never resolve the same song twice at once. */
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Formats>>()
    /** Songs YouTube only plays signed in; they skip the anonymous attempt next time. */
    private val needsAccount = ConcurrentHashMap.newKeySet<String>()

    init {
        // The content country picked in Settings (where YouTube Music isn't offered) goes to NewPipe too.
        val country = settings.current.contentCountry.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: Locale.getDefault().country.ifBlank { "US" }
        NewPipe.init(
            NewPipeDownloader(http),
            Localization(Locale.getDefault().language.ifBlank { "en" }, Locale.getDefault().country.ifBlank { "US" }),
            org.schabi.newpipe.extractor.localization.ContentCountry(country),
        )
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    /** Which network this is, for telling one from another (Wi-Fi to another Wi-Fi too). */
    private fun networkNow(): String =
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.toString().orEmpty()

    fun currentQuality(): AudioQuality = when {
        // Data saver on mobile data (#40) wins over lossless: the mobile data quality, at most Normal.
        settings.current.dataSaver && isMetered() -> settings.current.cellularQuality.takeIf { it != AudioQuality.HIGH } ?: AudioQuality.NORMAL
        settings.current.lossless -> AudioQuality.HIGH // lossless mode always takes the best stream there is
        isMetered() -> settings.current.cellularQuality
        else -> settings.current.audioQuality
    }

    /**
     * Forgets [videoId]'s stream, to be looked up again. [refused]: its server turned the stream away (403), so the next
     * lookup doesn't take the direct answer again (on the phone, "Beat It" `kOn-HdEg6AQ` signed in got 403s on every
     * direct link, so it failed twice and was skipped); it goes through NewPipe, as before the direct lookup.
     */
    fun invalidate(songId: String, refused: Boolean = false) {
        val videoId = versionOf(songId) ?: songId
        refusedSongs.forget(videoId)
        val was = cache.remove(videoId)
        // The account's own link was the one turned away (its web client's links stop after the first megabyte without
        // a PO token, GitHub #188 #192): asking as the account again gets the same, so the other ways are tried first
        // from now on. Before, such a song stayed "account only" and every try failed ~1 minute in.
        if (refused && was?.via?.startsWith("account") == true) {
            if (accountRefused.add(videoId)) {
                needsAccount -= videoId
                Log.i("PrismStream", "$videoId: the account's link was refused (${was.via}); trying the other ways first")
                runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "$videoId: the account's link was refused (${was.via}); trying the other ways first") }
            }
            return
        }
        // Turned away a second time (the direct link and then NewPipe's): a song only the account may play, such as
        // music the listener uploaded themselves (GitHub #101, "response code: 403"). Asked for as the account from then on.
        if (refused && !directRefused.add(videoId) && settings.current.isLoggedIn && videoId !in accountRefused) {
            needsAccount += videoId
            Log.i("PrismStream", "$videoId refused twice: asking as the signed-in account from now on")
        }
    }

    /** [videoId] is the listener's own upload (#101): asked for as the signed-in account from the start. */
    fun forAccount(videoId: String) {
        if (settings.current.isLoggedIn && needsAccount.add(videoId)) Log.i("PrismStream", "$videoId is an upload: asking as the account")
    }

    /** Songs whose direct ([directFormats]) links were turned away: looked up through NewPipe from then on. */
    private val directRefused = ConcurrentHashMap.newKeySet<String>()

    /** Songs whose account link was turned away: the anonymous ways come first for them ([invalidate]). */
    private val accountRefused = ConcurrentHashMap.newKeySet<String>()

    /** Whether [videoId]'s account link was turned away this session ([invalidate]); playback falls back sooner then. */
    fun accountWasRefused(videoId: String): Boolean = videoId in accountRefused

    /** Resolves a song ahead of time (e.g. the next one in the queue) so it starts straight away. */
    fun prefetch(videoId: String) {
        runCatching { formats(versionOf(videoId) ?: videoId) }
    }

    /** Songs refused by every account client a moment ago, and no other way played them ([RefusedSongs]). */
    private val refusedSongs = RefusedSongs()

    /** Every account client refused the song being looked up now; kept in [refusedSongs] if no other way plays it either. */
    private val refusedNow = ConcurrentHashMap.newKeySet<String>()

    private fun account() = settings.current.let { "${it.cookie.hashCode()}/${it.authUser}" }

    /** [forVideo]: looked up for its picture (a music video), which never has Premium's formats ([load]). */
    private fun formats(videoId: String, forVideo: Boolean = false): Formats {
        // An episode from a feed is no YouTube video, and its id never goes there (everything asked of YouTube comes
        // through here). It plays through AppContainer's own route; a prefetch or a scan asking for it just gets nothing.
        if (videoId.startsWith(com.prism.music.data.model.Song.RSS_PREFIX)) throw StreamException(tr(R.string.no_playable_audio_stream_was_found))
        cache[videoId]?.let { if (System.currentTimeMillis() < it.expiresAtMs) return it }
        refusedSongs.reasonFor(videoId, account())?.let { throw StreamException(it) }
        val mine = CompletableFuture<Formats>()
        val running = inflight.putIfAbsent(videoId, mine)
        if (running != null) {
            return try {
                running.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? StreamException) ?: StreamException(e.cause?.message ?: tr(R.string.couldn_t_load_this_track), e.cause)
            }
        }
        try {
            val started = System.nanoTime()
            val f = load(videoId, forVideo)
            refusedNow -= videoId
            val took = (System.nanoTime() - started) / 1_000_000
            Log.i("PrismStream", "$videoId resolved via ${f.via} in $took ms")
            runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "$videoId resolved via ${f.via} in $took ms (audio ${f.audio.joinToString(",") { it.itag.toString() }})") }
            cache[videoId] = f
            mine.complete(f)
            return f
        } catch (e: Throwable) {
            if (refusedNow.remove(videoId)) e.message?.let { refusedSongs.note(videoId, it, account()) }
            mine.completeExceptionally(e)
            throw e
        } finally {
            inflight.remove(videoId, mine)
        }
    }

    /** Until when NewPipe is skipped: YouTube was turning its anonymous web clients away from this IP ("not a bot"). */
    @Volatile
    private var newPipeBlockedUntil = 0L

    /** When [premiumFormats] is worth asking: paused after several songs in a row came without Premium formats. */
    private val premiumBackoff = PremiumBackoff()

    /**
     * Settings → Playback & sound → "Premium quality" (Reddit 1.7): asked as the signed-in account, which YouTube answers
     * with its 256 kbps formats (AAC itag 141, Opus 774) for Music Premium. Kept only when it has them; otherwise the
     * usual quick path plays. Only after several answers in a row without them (not a Premium account) is the account
     * not asked first for an hour, so songs don't start slower for nothing; one video or upload, which never has them,
     * no longer turns it off for the songs after it (Reddit, 1.7.7). Not on metered networks with Data saver on.
     */
    private fun premiumFormats(videoId: String): Formats? {
        val s = settings.current
        val metered = isMetered()
        // Another sign-in starts afresh: what's been learned, and any pause, was about the last one.
        val who = account()
        if (who != premiumFor) {
            premiumFor = who
            premiumBackoff.reset()
            premiumClient = null
            premiumState.value = PremiumState.NOT_KNOWN
        }
        // A change of network starts afresh: the same account was answered both ways within a day (#373's log).
        premiumBackoff.onNetwork(networkNow())
        val skip = premiumSkip(s.premiumStream, s.isLoggedIn, s.dataSaver, metered, premiumBackoff.minutesPaused())
        if (skip != null) { notePremiumSkip(skip); return null }
        notePremiumSkip(null)
        // Until an answer has Premium formats, an ask goes on to the account's other clients when the first has links
        // but no Premium formats: "first with links wins" would hide that another one has them.
        val walk = premiumBackoff.walkNow()
        val answers = ArrayList<String>()
        val f = try {
            signedInFormats(videoId, AccountClients.order(premiumClient), if (walk) { fs -> fs.audio.any { it.itag in PREMIUM_ITAGS } } else null, answers)
        } catch (e: Exception) {
            premiumLog("$videoId: couldn't ask (${e.message?.take(120)}). ${answers.joinToString("; ")}")
            return null
        }
        if (f.live) return f
        val premium = f.audio.filter { it.itag in PREMIUM_ITAGS }
        if (premium.isEmpty()) {
            val counted = f.audio.isNotEmpty() && !f.notASong
            premiumLog("$videoId: no 256 kbps${if (counted) "" else " (not counted: not a song)"}. ${answers.joinToString("; ")}")
            // Said only after several in a row: one song without them says nothing about the account.
            if (counted && premiumBackoff.missed(premiumSeenHere())) {
                premiumState.value = PremiumState.MISSING
                premiumLog("${PremiumBackoff.MISSES_TO_PAUSE} songs in a row without 256 kbps: not asking for ${premiumBackoff.minutesPaused()} min")
            }
            return null
        }
        premiumBackoff.found()
        premiumClient = f.client
        premiumState.value = PremiumState.GETTING
        premiumPrefs.edit().putString(SEEN_FOR, account()).apply()
        premiumLog("$videoId: 256 kbps (${premium.joinToString(",") { it.itag.toString() }}) from ${f.client?.name}")
        return f
    }

    /** Whether the listener is getting Premium's 256 kbps, as far as the last songs asked can say (Settings shows it). */
    enum class PremiumState { NOT_KNOWN, GETTING, MISSING }
    val premiumState = kotlinx.coroutines.flow.MutableStateFlow(PremiumState.NOT_KNOWN)

    /** Whether [premiumState] is about the sign-in there is now: it's only looked at again with the next song. */
    fun premiumStateIsCurrent() = premiumFor == account()

    /** The sign-in the pause, [premiumClient] and [premiumState] are about. */
    @Volatile private var premiumFor: String? = null

    /** The client that last had Premium's formats: asked first from then on. */
    @Volatile private var premiumClient: InnerTube.PlayerClient? = null
    private val premiumPrefs by lazy { context.getSharedPreferences("premium_quality", Context.MODE_PRIVATE) }

    /** This sign-in has had Premium's formats on this phone: a run of songs without them doesn't mean it isn't Premium. */
    private fun premiumSeenHere() = premiumPrefs.getString(SEEN_FOR, null) == account()

    @Volatile private var lastPremiumSkip: String? = null

    private fun notePremiumSkip(why: String?) {
        // One line when the reason changes; a paused ask's minutes aren't a change.
        val key = why?.substringBefore(" (")
        if (key == lastPremiumSkip) return
        lastPremiumSkip = key
        if (why != null) premiumLog("not asked: $why")
    }

    private fun premiumLog(line: String) {
        Log.i("PrismStream", "Premium quality: $line")
        runCatching { com.prism.music.playback.AudioDiagnostics.log("premium", line) }
    }

    private fun load(videoId: String, forVideo: Boolean = false): Formats {
        val signedIn = settings.current.isLoggedIn
        if (signedIn && videoId in needsAccount) return signedInFormats(videoId)
        // A music video looked up for its picture or its length (each song's, ahead of time) never has Premium's formats:
        // its ask slowed it down for nothing and counted as a miss, so that songs in the cache with videos asked for
        // around them paused Premium for the next hour.
        if (!forVideo && videoId !in accountRefused) premiumFormats(videoId)?.let { return it }
        // While YouTube is blocking NewPipe here, signed-in playback goes straight to the account's own clients
        // (each song would otherwise wait for NewPipe to be refused first).
        if (signedIn && System.currentTimeMillis() < newPipeBlockedUntil) {
            runCatching { signedInFormats(videoId) }.getOrNull()?.let { return it }
        }
        if (videoId !in directRefused) directFormats(videoId, signedIn)?.let { return it }
        return try {
            anonymousFormats(videoId).also { if (it.audio.isEmpty() && it.hlsUrl == null) throw StreamException(tr(R.string.no_playable_audio_stream_was_found)) }
        } catch (e: Exception) {
            val restricted = e is ContentNotAvailableException
            val blocked = e is SignInConfirmNotBotException || e is ReCaptchaException
            if (blocked) {
                newPipeBlockedUntil = System.currentTimeMillis() + BLOCK_BACKOFF_MS
                Log.w("StreamResolver", "YouTube is blocking anonymous web playback here; using other clients for a while: $e")
            }
            val why = if (blocked) BLOCKED else e.message ?: tr(R.string.couldn_t_load_this_track)
            when {
                // Anything YouTube won't play (or NewPipe can't get) anonymously is asked for as the user.
                signedIn -> try {
                    signedInFormats(videoId).also { if (restricted) needsAccount += videoId }
                } catch (signed: StreamException) {
                    Log.w("StreamResolver", "Signed-in playback of $videoId failed too: ${signed.message}")
                    throw if (restricted) signed else StreamException(why, e)
                }
                e is AgeRestrictedContentException -> throw StreamException(tr(R.string.sign_in_to_play_age_restricted), e)
                e is YoutubeMusicPremiumContentException -> throw StreamException(tr(R.string.this_song_needs_youtube_music_premium), e)
                else -> throw StreamException(why, e)
            }
        }
    }

    /** Until when [directFormats] is skipped: YouTube was answering it with bot checks from this network. */
    @Volatile
    private var directOffUntil = 0L

    /**
     * The quick way: one player request as YouTube's visionOS app ([InnerTube.visionOsPlayer]), the client NewPipe
     * takes its streams from, without NewPipe's other requests. On the phone a song not resolved ahead started ~2 s
     * later through NewPipe. Null whenever its answer isn't one to play as it is (refused, ciphered, an "n" to solve,
     * a live stream without its HLS manifest): NewPipe then resolves it as before. Songs that need an account
     * (age-restricted, Premium-only) go straight to the signed-in clients when signed in.
     */
    private fun directFormats(videoId: String, signedIn: Boolean): Formats? {
        if (System.currentTimeMillis() < directOffUntil) return null
        val res = try {
            innerTube.visionOsPlayer(videoId)
        } catch (e: Exception) {
            Log.w("StreamResolver", "Direct lookup of $videoId failed: $e")
            return null
        }
        val status = res.str("playabilityStatus", "status")
        if (status != "OK") {
            val reason = res.str("playabilityStatus", "reason")
            Log.i("StreamResolver", "Direct lookup of $videoId: $status ($reason)")
            runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "$videoId, asked directly: $status ($reason)") }
            if (reason?.contains("bot", ignoreCase = true) == true) {
                directOffUntil = System.currentTimeMillis() + BLOCK_BACKOFF_MS
            } else if (signedIn && status == "LOGIN_REQUIRED") {
                runCatching { signedInFormats(videoId) }.getOrNull()?.let { needsAccount += videoId; return it }
            }
            return null
        }
        // Every format plain (music videos' pictures come from the same answer).
        val adaptive = res.obj("streamingData")?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
        if (adaptive.any { o -> o.str("url").let { it == null || Regex("[?&]n=").containsMatchIn(it) } }) return null
        val f = parsePlayer(res, null)
        if (f.live) return f.takeIf { it.hlsUrl != null }
        return f.takeIf { it.audio.isNotEmpty() }?.also { Log.i("PrismStream", "$videoId resolved directly (visionOS)") }
    }

    private fun anonymousFormats(videoId: String): Formats {
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        fun clen(s: org.schabi.newpipe.extractor.stream.Stream) =
            s.itagItem?.contentLength?.takeIf { it > 0 } ?: Uri.parse(s.content).getQueryParameter("clen")?.toLongOrNull() ?: -1
        val audio = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.map { a ->
            val len = clen(a)
            val ms = a.itagItem?.approxDurationMs?.takeIf { it > 0 } ?: (info.duration * 1000)
            val nominal = a.averageBitrate.takeIf { it > 0 } ?: (a.bitrate / 1000)
            Fmt(
                itag = a.itag, mime = a.format?.mimeType ?: "audio/*", codec = a.codec,
                kbps = nominal,
                sampleRate = a.itagItem?.sampleRate ?: 0, channels = a.itagItem?.audioChannels ?: 2,
                contentLength = len,
                original = a.audioTrackType == null || a.audioTrackType.toString() == "ORIGINAL",
                actualKbps = realKbps(len, ms, nominal),
            ) { a.content }
        }
        fun video(v: VideoStream) = Fmt(
            itag = v.itag, mime = v.format?.mimeType ?: "video/*", codec = v.codec, kbps = (v.itagItem?.bitrate ?: 0) / 1000,
            sampleRate = 0, channels = 0, contentLength = clen(v), height = v.height, fps = v.fps,
        ) { v.content }
        val progressive = { v: VideoStream -> v.isUrl && v.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val expire = info.audioStreams.firstOrNull()?.content?.let { Uri.parse(it).getQueryParameter("expire")?.toLongOrNull() }
            ?.let { it * 1000 - 10 * 60_000 } ?: (System.currentTimeMillis() + 60 * 60_000)
        val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        return Formats(
            audio, info.videoOnlyStreams.filter(progressive).map(::video), info.videoStreams.filter(progressive).map(::video), info.duration, expire,
            live = live, hlsUrl = info.hlsUrl?.takeIf { it.isNotBlank() }, via = "NewPipe",
        )
    }

    /**
     * Which of the account's clients are asked, in what order (1.7.9.3, GitHub #260): yt-dlp's for signed-in users
     * (the older TV app as a Samsung TV, the embedded player), then the plain older TV app, today's TV app (links
     * rarely), and the web app last (its links stop after a megabyte without a PO token, unless Premium).
     */
    internal object AccountClients {
        val order = listOf(
            InnerTube.PlayerClient.TV_SAMSUNG, InnerTube.PlayerClient.WEB_EMBEDDED, InnerTube.PlayerClient.TV_OLD,
            InnerTube.PlayerClient.TV, InnerTube.PlayerClient.WEB_REMIX,
        )

        /** One more ask for the older TV app on UNPLAYABLE ("The page needs to be reloaded" comes and goes). */
        fun retries(client: InnerTube.PlayerClient): Int =
            if (client == InnerTube.PlayerClient.TV_SAMSUNG || client == InnerTube.PlayerClient.TV_OLD) 1 else 0

        /** [order] with [first] (the one that last had what's wanted) at its head. */
        fun order(first: InnerTube.PlayerClient?): List<InnerTube.PlayerClient> =
            if (first == null) order else listOf(first) + order.filter { it != first }

        const val RETRY_PAUSE_MS = 300L
    }

    /**
     * The player response as the signed-in user: [order], the first with links wins. With [wanted], the first with links
     * that it likes wins, else the first with links. What each client answered goes in [answers], with no links in it.
     */
    private fun signedInFormats(
        videoId: String, order: List<InnerTube.PlayerClient> = AccountClients.order,
        wanted: ((Formats) -> Boolean)? = null, answers: MutableList<String>? = null,
    ): Formats {
        val player = try {
            solver.currentPlayer()
        } catch (e: Exception) {
            Log.w("StreamResolver", "Couldn't get YouTube's player JS: $e")
            null
        }
        var reason: String? = null
        // A live answer without an HLS manifest is kept only until a client gives one ([liveManifest] fills it in).
        var liveWithoutHls: Formats? = null
        // Each client said UNPLAYABLE (not a failed request, nor an answer another moment could change).
        var allRefused = true
        var firstWithLinks: Formats? = null
        if (player == null) answers?.add("no player JS, so no signature timestamp")
        for (client in order) {
            val res = try {
                var answer = innerTube.signedInPlayer(client, videoId, player?.second)
                // "The page needs to be reloaded" (GitHub #260) is often gone on the next ask.
                repeat(AccountClients.retries(client)) {
                    if (answer.str("playabilityStatus", "status") != "UNPLAYABLE") return@repeat
                    Thread.sleep(AccountClients.RETRY_PAUSE_MS)
                    answer = innerTube.signedInPlayer(client, videoId, player?.second)
                }
                answer
            } catch (e: Exception) {
                allRefused = false
                reason = reason ?: e.message
                noteClient(videoId, client, "request failed: ${e.message}")
                answers?.add("${client.name}: request failed")
                continue
            }
            val status = res.str("playabilityStatus", "status")
            if (status != "UNPLAYABLE") allRefused = false
            if (status != "OK") {
                reason = res.str("playabilityStatus", "reason") ?: reason
                noteClient(videoId, client, "$status (${res.str("playabilityStatus", "reason")})")
                answers?.add("${client.name}: $status")
                continue
            }
            val f = parsePlayer(res, player?.first, via = "account (${client.name})", client = client)
            if (f.live && f.hlsUrl == null) { liveWithoutHls = liveWithoutHls ?: f; continue }
            answers?.add("${client.name}: ${describePlayerAnswer(res)}")
            if (f.live && f.hlsUrl != null) return f
            if (f.audio.isNotEmpty()) {
                if (wanted == null || wanted(f)) return f
                firstWithLinks = firstWithLinks ?: f
                continue
            }
            noteClient(videoId, client, "OK but no audio links (${linkless(res)})")
        }
        firstWithLinks?.let { return it }
        liveWithoutHls?.let { return it }
        if (allRefused) refusedNow += videoId
        throw StreamException(reason ?: tr(R.string.couldn_t_load_this_track))
    }

    /**
     * Why one of the account's clients gave nothing, into the diagnostics once per client and answer (GitHub #188,
     * #192: every account song came from the web client, whose links YouTube cuts off after the first megabyte without
     * a PO token, while the TV client, which needs none, never answered; the logs couldn't say why). It names the song:
     * #372's log couldn't say which song it was about (one that isn't on YouTube any more, a download tried at each start).
     */
    private fun noteClient(videoId: String, client: InnerTube.PlayerClient, what: String) {
        val line = "$videoId, ${client.name}: ${what.take(160)}"
        if (clientNotes.add(line)) {
            Log.i("PrismStream", "account client $line")
            runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "account client $line") }
        }
    }

    private val clientNotes = ConcurrentHashMap.newKeySet<String>()

    /** How an OK answer's formats come: without links at all is YouTube's newer streaming (SABR) only. */
    private fun linkless(res: JsonObject): String {
        val data = res.obj("streamingData")
        val all = data?.arr("adaptiveFormats").orEmpty().mapNotNull { it as? JsonObject }
        val bare = all.count { it.str("url") == null && it.str("signatureCipher") == null && it.str("cipher") == null }
        val sabr = data?.str("serverAbrStreamingUrl") != null
        return "${all.size} formats, $bare without a link${if (sabr) ", streaming-only (SABR)" else ""}"
    }

    /**
     * Premium quality's pause: answers without Premium formats are counted in a row, and only [MISSES_TO_PAUSE] of them
     * (not a Premium account, most likely) pause asking for [PAUSE_MS]; for [KNOWN_PAUSE_MS] when this sign-in has had
     * them before, since then it's YouTube's answer that changed (#373: the same songs came both ways on one day, and
     * the hour's pause then hid every song after three misses). A Premium answer starts the count again and ends a
     * pause. So does a change of network ([onNetwork]).
     */
    internal class PremiumBackoff(private val now: () -> Long = System::currentTimeMillis) {
        private var misses = 0
        private var pausedUntil = 0L
        private var hadThem = false
        private var network: String? = null

        @Synchronized fun asking(): Boolean = now() >= pausedUntil

        /** Minutes left of the pause, 0 when asking (a part of a minute counts as one). */
        @Synchronized fun minutesPaused(): Int = ((pausedUntil - now()).coerceAtLeast(0) + 59_999).div(60_000).toInt()

        /** One answer without Premium formats; true when it starts a pause. [known]: this sign-in has had them before. */
        @Synchronized fun missed(known: Boolean = false): Boolean {
            hadThem = false
            if (++misses < MISSES_TO_PAUSE) return false
            misses = 0
            pausedUntil = now() + if (known) KNOWN_PAUSE_MS else PAUSE_MS
            return true
        }

        /** An answer with Premium formats, which may be an ask still under way when others started a pause. */
        @Synchronized fun found() { misses = 0; pausedUntil = 0; hadThem = true }

        /**
         * Whether this ask should go through all the account's clients: unless the last answer had Premium formats.
         * So for a song or two at most after a pause or the start, on an account that really has none.
         */
        @Synchronized fun walkNow(): Boolean = !hadThem

        /** The network now; a change from the one last seen ends the pause. */
        @Synchronized fun onNetwork(id: String) {
            if (network != null && network != id) reset()
            network = id
        }

        /** As at the start. */
        @Synchronized fun reset() { misses = 0; pausedUntil = 0; hadThem = false }

        companion object {
            const val MISSES_TO_PAUSE = 3
            const val PAUSE_MS = 60 * 60_000L
            const val KNOWN_PAUSE_MS = 10 * 60_000L
        }
    }

    companion object {
        /** YouTube Music Premium's 256 kbps audio: AAC (141) and Opus (774). */
        val PREMIUM_ITAGS = setOf(141, 774)
        private const val SEEN_FOR = "seen_for"

        /** Why Premium's formats won't be asked for now, for the diagnostics; null: they will. */
        internal fun premiumSkip(on: Boolean, signedIn: Boolean, dataSaver: Boolean, metered: Boolean, pausedMinutes: Int): String? = when {
            !on -> "Premium quality is off"
            !signedIn -> "signed out"
            dataSaver && metered -> "Data saver on a metered network"
            pausedMinutes > 0 -> "paused after songs without 256 kbps ($pausedMinutes min left)"
            else -> null
        }

        private const val BLOCK_BACKOFF_MS = 30 * 60_000L
        val BLOCKED: String get() = com.prism.music.tr(com.prism.music.R.string.youtube_is_limiting_playback)

        /**
         * A stream's real average bitrate, from its size and length. YouTube's own figure is the format's nominal one
         * (Opus always says 160 kbps), which is why every song showed the same number.
         */
        fun realKbps(bytes: Long, durationMs: Long, nominal: Int): Int =
            if (bytes > 0 && durationMs > 1000) (bytes * 8 / durationMs).toInt().takeIf { it in 8..5000 } ?: nominal else nominal

        /** A format YouTube has copy-protected: its link can't be played, so it's never picked (idea from PR #240, by CodeWormer). */
        internal fun isProtected(format: JsonObject): Boolean = format.arr("drmFamilies")?.isNotEmpty() == true
    }

    /** [playerId] is the player JS whose signature timestamp the request sent; its ciphers are solved with it. */
    private fun parsePlayer(res: JsonObject, playerId: String?, via: String = "visionOS", client: InnerTube.PlayerClient? = null): Formats {
        val data = res.obj("streamingData")
        fun fmt(o: JsonObject): Fmt? {
            if (isProtected(o)) return null
            val mimeFull = o.str("mimeType") ?: return null
            val mime = mimeFull.substringBefore(";").trim()
            val codec = Regex("codecs=\"([^\"]+)\"").find(mimeFull)?.groupValues?.get(1)?.substringBefore(",")?.trim()
            val direct = o.str("url")
            val cipher = o.str("signatureCipher") ?: o.str("cipher")
            if (direct == null && cipher == null) return null
            val len = o.str("contentLength")?.toLongOrNull() ?: -1
            val nominal = ((o.str("averageBitrate") ?: o.str("bitrate"))?.toIntOrNull() ?: 0) / 1000
            return Fmt(
                itag = o.str("itag")?.toIntOrNull() ?: 0, mime = mime, codec = codec,
                kbps = nominal,
                actualKbps = if (mime.startsWith("audio/")) realKbps(len, o.str("approxDurationMs")?.toLongOrNull() ?: 0, nominal) else nominal,
                sampleRate = o.str("audioSampleRate")?.toIntOrNull() ?: 0,
                channels = o.str("audioChannels")?.toIntOrNull() ?: 2,
                contentLength = len,
                height = o.str("height")?.toIntOrNull() ?: 0, fps = o.str("fps")?.toIntOrNull() ?: 0,
                original = o.obj("audioTrack")?.let { t -> t.str("audioIsDefault") != "false" } ?: true,
            ) { decipher(playerId, direct, cipher) }
        }
        val adaptive = data.arr("adaptiveFormats")?.mapNotNull { (it as? JsonObject)?.let(::fmt) }.orEmpty()
        val muxed = data.arr("formats")?.mapNotNull { (it as? JsonObject)?.let(::fmt) }.orEmpty()
        val expiresIn = data.str("expiresInSeconds")?.toLongOrNull() ?: 3600
        return Formats(
            audio = adaptive.filter { it.mime.startsWith("audio/") },
            videoOnly = adaptive.filter { it.mime.startsWith("video/") },
            muxed = muxed,
            durationSec = res.str("videoDetails", "lengthSeconds")?.toLongOrNull() ?: 0,
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000 - 10 * 60_000,
            live = isOnAir(
                isLive = res.str("videoDetails", "isLive") == "true",
                liveContent = res.str("videoDetails", "isLiveContent") == "true",
                hasHls = data.str("hlsManifestUrl") != null,
                lengthSec = res.str("videoDetails", "lengthSeconds")?.toLongOrNull() ?: 0,
            ),
            hlsUrl = data.str("hlsManifestUrl"),
            via = via,
            client = client,
            notASong = res.str("videoDetails", "musicVideoType").let { it != null && it != "MUSIC_VIDEO_TYPE_ATV" },
        )
    }

    /** [videoId]'s length in seconds as YouTube gives it (resolving it if it hasn't been), 0 when unknown. Blocking. */
    fun durationSec(videoId: String, video: Boolean = false): Long = formats(versionOf(videoId) ?: videoId, forVideo = video).durationSec

    /** Whether [videoId] is a live stream (resolving it if it hasn't been). Blocking. */
    fun isLive(videoId: String): Boolean = formats(videoId).live

    /** A live stream's HLS manifest, fresh enough to play. Blocking; called from ExoPlayer loader threads. */
    fun liveManifest(videoId: String): String {
        val f = formats(videoId)
        f.hlsUrl?.let { return it }
        // The account's clients (signed in, while NewPipe is backed off) can answer a live stream with its DASH
        // formats and no HLS manifest, which left live streams unplayable in song and video mode alike. The
        // anonymous web player always has the manifest.
        val anon = try {
            anonymousFormats(videoId)
        } catch (e: Exception) {
            throw StreamException(tr(R.string.this_live_stream_can_t_be), e)
        }
        val url = anon.hlsUrl ?: throw StreamException(tr(R.string.this_live_stream_can_t_be))
        cache[videoId] = Formats(f.audio, f.videoOnly, f.muxed, f.durationSec, minOf(f.expiresAtMs, anon.expiresAtMs), live = true, hlsUrl = url)
        return url
    }

    /**
     * A playable URL: the signature put back (ciphered formats) and the throttling parameter "n" solved,
     * both by [playerId]'s JS. An unsolved "n" still plays (slowly), so only the signature is required.
     */
    private fun decipher(playerId: String?, direct: String?, cipher: String?): String {
        var sig: String? = null
        var param = "signature"
        val base = direct ?: run {
            // A bare query string; android.net.Uri can't be trusted to parse it (a stray ':' makes it opaque).
            val q = cipher!!.split('&').associate { part ->
                URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            sig = q["s"]
            param = q["sp"] ?: param
            val url = q["url"]
            if (url == null || sig == null) {
                Log.w("StreamResolver", "signatureCipher has keys ${q.keys}")
                throw StreamException(tr(R.string.couldn_t_read_this_track_s))
            }
            url
        }
        val url = base.toHttpUrlOrNull() ?: throw StreamException(tr(R.string.couldn_t_read_this_track_s))
        val n = url.queryParameter("n")
        if (sig == null && n == null) return base
        val solved = try {
            solver.solve(playerId ?: throw IOException("No player JS to solve with"), sig, n)
        } catch (e: Exception) {
            Log.w("StreamResolver", "Couldn't solve the stream URL: $e")
            if (sig == null) return base
            throw e as? StreamException ?: StreamException(tr(R.string.couldn_t_unlock_this_track_s), e)
        }
        return url.newBuilder().apply {
            if (sig != null) setQueryParameter(param, solved.first ?: throw StreamException(tr(R.string.couldn_t_unlock_this_track_s)))
            if (n != null && solved.second != null) setQueryParameter("n", solved.second)
        }.build().toString()
    }

    data class AnalysisStream(val url: String, val contentLength: Long, val durationSec: Long)

    /** The smallest audio stream (AAC preferred, which decodes from a partial file), for analysis. */
    fun analysisAudio(videoId: String, video: Boolean = false): AnalysisStream {
        val f = formats(versionOf(videoId) ?: videoId, forVideo = video)
        val usable = f.audio.filter { it.original }.ifEmpty { f.audio }
        val pick = usable.filter { it.mime.contains("mp4") }.minByOrNull { it.kbps }
            ?: usable.minByOrNull { it.kbps } ?: throw StreamException(tr(R.string.no_audio_stream))
        return AnalysisStream(pick.url, pick.contentLength, f.durationSec)
    }

    private fun pickAudio(streams: List<Fmt>, quality: AudioQuality): Fmt? =
        AudioPick.pick(streams.map { AudioPick.Choice(it.mime, it.codec, it.kbps, it.original) }, quality)?.let { streams[it] }

    private fun pickVideo(streams: List<Fmt>, maxHeight: Int): Fmt? {
        val usable = streams.filter { it.height in 1..maxHeight }
        val avc = usable.filter { it.codec?.startsWith("avc") == true || it.mime == "video/mp4" }
        return (avc.ifEmpty { usable }).maxWithOrNull(compareBy<Fmt> { it.height }.thenBy { it.fps })
    }

    /** Blocking; called from ExoPlayer loader threads. */
    /** The version the listener picked to hear for a song ([com.prism.music.data.SongVersions]), or null for its own. */
    @Volatile var versionOf: (String) -> String? = { null }

    /** The picture last picked for each music video (its itag, client and length), for the player's diagnostics (#411). */
    private val pictures = ConcurrentHashMap<String, String>()

    /** What [videoId]'s picture was last streamed as ([resolve] with its video), or null when it hasn't been. */
    fun pictureOf(videoId: String): String? = pictures[videoId]

    fun resolve(songId: String, withVideo: Boolean = false): ResolvedStream {
        // A picked version's sound plays under the song's own key (a song's video keeps its own picture).
        val videoId = if (withVideo) songId else versionOf(songId) ?: songId
        val f = formats(videoId, forVideo = withVideo)
        // A live stream's "file" is only its newest few seconds: the player has to take it as HLS instead.
        if (f.live) throw LiveStreamException(videoId)
        val audio = pickAudio(f.audio, currentQuality())
            ?: throw StreamException(tr(R.string.no_playable_audio_stream_was_found))
        // The account's link couldn't be unlocked (GitHub #179, 1.7.7.1: the player JS sandbox ran out of memory on a
        // OnePlus) for a song that went through the account only for Premium quality: it plays as everyone gets it,
        // at once, rather than failing and falling back to its music video's sound.
        try {
            audio.url
        } catch (e: StreamException) {
            if (!f.via.startsWith("account") || videoId in needsAccount || !accountRefused.add(videoId)) throw e
            cache.remove(videoId)
            Log.i("PrismStream", "$videoId: the account's link couldn't be unlocked (${e.message}); playing the usual stream")
            runCatching { com.prism.music.playback.AudioDiagnostics.log("stream", "$videoId: the account's link couldn't be unlocked; playing the usual stream instead") }
            return resolve(videoId, withVideo)
        }
        val video = if (withVideo) {
            // Data saver on mobile data (#40): music videos at 480p at most.
            val maxHeight = if (settings.current.dataSaver && isMetered()) minOf(480, settings.current.videoMaxHeight) else settings.current.videoMaxHeight
            pickVideo(f.videoOnly, maxHeight) ?: f.muxed.minByOrNull { if (it.height <= maxHeight) maxHeight - it.height else it.height }
        } else null
        if (video != null) runCatching {
            pictures[videoId] = "itag ${video.itag} (${video.codec} ${video.height}p${video.fps}), client ${Uri.parse(video.url).getQueryParameter("c")}, " +
                "clen ${video.contentLength.takeIf { it > 0 } ?: Uri.parse(video.url).getQueryParameter("clen")}, via ${f.via}"
        }
        val uri = Uri.parse(audio.url)
        return ResolvedStream(
            videoId = videoId,
            audioUrl = audio.url,
            itag = audio.itag,
            mimeType = audio.mime,
            codec = audio.codec,
            bitrate = audio.actualKbps,
            sampleRate = audio.sampleRate,
            channels = audio.channels,
            contentLength = audio.contentLength.takeIf { it > 0 } ?: uri.getQueryParameter("clen")?.toLongOrNull() ?: -1,
            client = uri.getQueryParameter("c"),
            expiresAtMs = f.expiresAtMs,
            videoUrl = video?.url,
            videoHeight = video?.height ?: 0,
            videoCodec = video?.codec,
            videoFps = video?.fps ?: 0,
        )
    }
}

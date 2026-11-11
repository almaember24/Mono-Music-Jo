package com.prism.music.data.podcast

import com.prism.music.BuildConfig
import com.prism.music.R
import com.prism.music.connect.Lan
import com.prism.music.tr
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Why a feed couldn't be used, in words a listener can act on ([RssException.message]). */
enum class RssProblem { BAD_ADDRESS, OWN_NETWORK, NOT_A_FEED, NO_EPISODES, TOO_MANY_SHOWS, SERVER_ERROR, NO_SECURE_SERVER, CANT_REACH, TIMED_OUT, LINK_REFUSED, EPISODE_GONE }

class RssException(val problem: RssProblem, private val code: Int = 0, cause: Throwable? = null) : IOException(problem.name, cause) {
    override val message: String
        get() = when (problem) {
            RssProblem.BAD_ADDRESS -> tr(R.string.rss_bad_address)
            RssProblem.OWN_NETWORK -> tr(R.string.rss_own_network)
            RssProblem.NOT_A_FEED -> tr(R.string.rss_not_a_feed)
            RssProblem.NO_EPISODES -> tr(R.string.rss_no_episodes)
            RssProblem.TOO_MANY_SHOWS -> tr(R.string.rss_too_many_shows)
            RssProblem.SERVER_ERROR -> tr(R.string.rss_server_error, code)
            RssProblem.NO_SECURE_SERVER -> tr(R.string.rss_no_secure_server)
            RssProblem.CANT_REACH -> tr(R.string.rss_cant_reach)
            RssProblem.TIMED_OUT -> tr(R.string.rss_timed_out)
            RssProblem.LINK_REFUSED -> tr(R.string.rss_link_refused)
            RssProblem.EPISODE_GONE -> tr(R.string.rss_episode_gone)
        }
}

/**
 * Everything between Prism and a podcast's server, which isn't a server Prism knows:
 *
 * - **Its own client.** Never InnerTube's or anything YouTube's: no cookies, no account headers, no cache, nothing but a
 *   plain GET.
 * - **Secure connections only.** Prism has no network security config, so Android forbids unencrypted traffic to its
 *   HTTP client (targetSdk 28+), and that stays as it is: an `http://` address or redirect is asked for as `https://`,
 *   which nearly every podcast host answers (a tracking prefix's redirect often says http). A server that can't is told
 *   to the listener ([RssProblem.NO_SECURE_SERVER]).
 * - **Not the listener's own network.** A feed from anywhere could otherwise point a phone at its router (a link, a
 *   redirect, or a name that resolves to a private address). Prism reads feeds from the public internet only.
 */
object RssGuard {
    private const val MAX_LINK = 2_048
    private const val MAX_HOPS = 8
    private val USER_AGENT = "Prism/${BuildConfig.VERSION_NAME} (Android; podcast player)"

    /** Names that only ever mean something on a private network. */
    private val PRIVATE_SUFFIXES = listOf(".localhost", ".local", ".localdomain", ".internal", ".intranet", ".lan", ".home", ".home.arpa", ".corp")

    /** The address the listener pasted, as Prism will ask for it. Throws [RssException] when it can't be one. */
    fun feedAddress(text: String): HttpUrl {
        val typed = text.trim()
        if (typed.isEmpty() || typed.length > MAX_LINK || typed.any { it.isWhitespace() || it.code < 0x20 }) throw RssException(RssProblem.BAD_ADDRESS)
        val scheme = SCHEME.find(typed)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val web = when (scheme) {
            null -> "https://$typed"
            "http", "https" -> typed
            // What podcast sites link their "subscribe" buttons with: the same address with another name for its scheme.
            "feed", "itpc", "pcast", "podcast" -> typed.substringAfter(':').removePrefix("//").let { rest ->
                if (rest.startsWith("http://", true) || rest.startsWith("https://", true)) rest else "https://$rest"
            }
            else -> throw RssException(RssProblem.BAD_ADDRESS)
        }
        val url = web.toHttpUrlOrNull() ?: throw RssException(RssProblem.BAD_ADDRESS)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw RssException(RssProblem.BAD_ADDRESS)
        if (isPrivateHost(url.host)) throw RssException(RssProblem.OWN_NETWORK)
        return secure(url)
    }

    /** "name:" at the start, unless that's a host and port ("example.com:8080/feed"). */
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(?!\\d)")

    /**
     * A link in a feed, made whole against [base], if Prism may open it; else null. `javascript:`, `file:`, `data:` and
     * every other scheme are out (only http and https parse), so are logins in a link and anything on a private network.
     */
    fun link(raw: String?, base: HttpUrl): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LINK } ?: return null
        val url = base.resolve(s) ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || isPrivateHost(url.host)) return null
        return secure(url).toString().takeIf { it.length <= MAX_LINK }
    }

    private fun secure(url: HttpUrl): HttpUrl = if (url.isHttps) url else url.newBuilder().scheme("https").build()

    /**
     * Whether [host] names the listener's own network by what it looks like, without asking anyone: "localhost", a bare
     * word, a private suffix, a private address, or an address in one of the forms only tricks use ("2130706433",
     * "0x7f.1"). Names that resolve to such an address are caught by [GuardedDns].
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (h.isEmpty() || h == "localhost") return true
        if (':' in h) return runCatching { isPrivateAddress(InetAddress.getByName(h)) }.getOrDefault(true)
        val labels = h.split('.')
        if (labels.size < 2 || PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
        // A last label that's a number makes it an address, however it's written.
        val last = labels.last()
        if (last.all { it.isDigit() } || last.startsWith("0x")) {
            val dotted = labels.size == 4 && labels.all { l -> l.length in 1..3 && l.all { it.isDigit() } && l.toInt() <= 255 }
            return !dotted || runCatching { isPrivateAddress(InetAddress.getByName(h)) }.getOrDefault(true)
        }
        return false
    }

    /** Local, unroutable or carrier-internal: nothing a public podcast host is on. */
    fun isPrivateAddress(a: InetAddress): Boolean {
        if (Lan.isLocal(a) || a.isAnyLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (b.size == 4) {
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            return first == 0 || (first == 100 && second in 64..127) // 0.0.0.0/8, carrier NAT 100.64.0.0/10
        }
        return false
    }

    /** Answers only with public addresses: a feed's name can't be pointed at the listener's router. */
    private object GuardedDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            return all.filterNot(::isPrivateAddress).ifEmpty { throw RssException(RssProblem.OWN_NETWORK) }
        }
    }

    /**
     * Follows redirects itself (OkHttp's own following is off in these clients), so every hop goes through the same
     * checks: secure, and not on a private network. The request's headers (a range, for audio) stay; there are no others.
     */
    class Hops : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = checked(chain.request())
            repeat(MAX_HOPS) {
                val response = chain.proceed(request)
                if (!response.isRedirect) return response
                val next = response.header("Location")?.let { response.request.url.resolve(it.trim()) } ?: return response
                response.close()
                request = checked(request.newBuilder().url(next).build())
            }
            throw RssException(RssProblem.LINK_REFUSED)
        }

        private fun checked(request: Request): Request {
            val url = request.url
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw RssException(RssProblem.LINK_REFUSED)
            if (isPrivateHost(url.host)) throw RssException(RssProblem.OWN_NETWORK)
            return if (url.isHttps) request else request.newBuilder().url(secure(url)).build()
        }
    }

    private fun base(hook: Interceptor?) = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dns(GuardedDns)
        .addInterceptor(Hops())
        .apply { hook?.let(::addInterceptor) }

    /** For feeds: the whole answer within a minute, however slowly a server sends it. */
    fun feedClient(hook: Interceptor? = null): OkHttpClient = base(hook).callTimeout(60, TimeUnit.SECONDS).build()

    /** For episodes' audio: an episode takes as long as it takes. */
    fun audioClient(hook: Interceptor? = null): OkHttpClient = base(hook).build()

    /** A feed's GET. Conditional when Prism has the feed's marks from last time. */
    fun feedRequest(url: HttpUrl, etag: String? = null, lastModified: String? = null): Request = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.5")
        .apply {
            // What a server sent is sent back only if it can go in a header at all.
            etag?.takeIf(::fitsHeader)?.let { header("If-None-Match", it) }
            lastModified?.takeIf(::fitsHeader)?.let { header("If-Modified-Since", it) }
        }
        .build()

    private fun fitsHeader(s: String) = s.length <= 256 && s.all { it == '\t' || it in ' '..'~' }

    val userAgent: String get() = USER_AGENT
}

package com.prism.music.connect

import java.security.SecureRandom

/**
 * Serves songs to renderers on the local network: `GET/HEAD /cast/<token>/<key>` with Range support and the DLNA
 * headers TVs look for. [token] is new for every cast session, so a link only works while it's being cast, and only
 * the few songs around the one playing are on offer at a time.
 */
class MediaServer(preferredPort: Int = PORT) {
    @Volatile var token: String = newToken()
        private set
    private val bodies = object : LinkedHashMap<String, CastBody>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CastBody>?) = size > KEEP
    }
    /** Requests served, for the log and tests: "GET key bytes=a-b". */
    val log = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val http = HttpServer("prism-cast", preferredPort, maxBody = 0, handler = ::handle)

    val port: Int get() = http.port

    fun start(): Boolean = http.start()

    fun stop() {
        http.stop()
        synchronized(bodies) { bodies.clear() }
    }

    /** A new session: links from the last one stop working. */
    fun newSession() {
        token = newToken()
        synchronized(bodies) { bodies.clear() }
    }

    /** Offers [body] as [key] (e.g. "dQw4w9WgXcQ.wav"); returns its link as seen from [host] (this phone's address). */
    fun publish(key: String, body: CastBody, host: String): String {
        synchronized(bodies) { bodies[key] = body }
        return url(host, key)
    }

    fun url(host: String, key: String) = "http://$host:$port/cast/$token/$key"

    fun has(key: String) = synchronized(bodies) { bodies.containsKey(key) }

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (req.method != "GET" && req.method != "HEAD") { res.text(405, "GET only", mapOf("Allow" to "GET, HEAD")); return }
        val parts = req.path.removePrefix("/").split('/')
        if (parts.size != 3 || parts[0] != "cast" || parts[1] != token) { res.text(404, "Not found"); return }
        val key = parts[2]
        val body = synchronized(bodies) { bodies[key] } ?: run { res.text(404, "Not found"); return }
        val total = body.length
        val headers = linkedMapOf(
            "Content-Type" to body.mime,
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-cache",
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to ProtocolInfo.of(body.mime, body.converted).substringAfterLast(':'),
            "Server" to "Android UPnP/1.1 Prism/1.7",
        )
        val range = ByteRange.parse(req.header("range"), total)
        log += "${req.method} $key ${req.header("range") ?: "whole"}"
        when (range) {
            ByteRange.Unsatisfiable -> res.send(416, null, ByteArray(0), headers + ("Content-Range" to "bytes */$total"))
            ByteRange.Whole -> {
                val out = res.start(200, headers, total)
                if (req.method == "GET") body.write(0, total - 1, out)
                out.flush()
            }
            is ByteRange.Part -> {
                val out = res.start(206, headers + ("Content-Range" to "bytes ${range.start}-${range.end}/$total"), range.length)
                if (req.method == "GET") body.write(range.start, range.end, out)
                out.flush()
            }
        }
    }

    companion object {
        const val PORT = 47322
        private const val KEEP = 8
        private val random = SecureRandom()

        fun newToken(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

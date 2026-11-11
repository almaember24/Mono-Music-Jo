package com.prism.music.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A browser paired with Prism's web remote. [id] is a short, harmless handle on it (from its key's hash). */
data class PairedBrowser(val id: String, val name: String, val pairedAt: Long, val lastSeen: Long)

/**
 * Who may use the web remote: browsers that entered the six-digit code shown on the phone get a long random key (a
 * cookie); only its hash is kept. Wrong codes are slowed down, and too many from one address, or in all, lock pairing
 * for a while (and, in all, change the code), so it can't be guessed.
 */
class RemoteAuth(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {
    /** Where pairings live (shared preferences on the phone). */
    interface Store {
        fun all(): Map<String, String>
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    sealed interface PairResult {
        data class Paired(val token: String) : PairResult
        data object Wrong : PairResult
        data class Locked(val seconds: Long) : PairResult
    }

    private val random = SecureRandom()
    @Volatile var code: String = newCodeValue()
        private set
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val recent = java.util.ArrayDeque<Long>()
    @Volatile private var lockedUntil = 0L
    /** Bumped when pairings or the code change, for the settings page. */
    val changes = AtomicInteger()

    private fun newCodeValue() = (100_000 + random.nextInt(900_000)).toString()

    fun newCode() {
        code = newCodeValue()
        changes.incrementAndGet()
    }

    @Synchronized
    fun pair(entered: String, name: String, from: String): PairResult {
        val now = clock()
        if (now < lockedUntil) return PairResult.Locked((lockedUntil - now + 999) / 1000)
        failures[from]?.let { (n, until) -> if (n >= PER_ADDRESS && now < until) return PairResult.Locked((until - now + 999) / 1000) }
        val digits = entered.filter { it.isDigit() }
        if (!MessageDigest.isEqual(digits.toByteArray(), code.toByteArray())) {
            val (n, _) = failures[from] ?: (0 to 0L)
            val count = n + 1
            // Locked a minute after five misses, then twice as long each time.
            val lock = if (count >= PER_ADDRESS) now + LOCK_MS * (1L shl (count - PER_ADDRESS).coerceAtMost(6)) else 0L
            failures[from] = count to lock
            recent.addLast(now)
            while (recent.isNotEmpty() && recent.first < now - WINDOW_MS) recent.removeFirst()
            if (recent.size >= IN_ALL) {
                recent.clear()
                lockedUntil = now + LOCK_MS
                newCode()
            }
            return PairResult.Wrong
        }
        failures.remove(from)
        val token = ByteArray(32).also { random.nextBytes(it) }.hex()
        store.put(KEY + hash(token), listOf(name.trim().take(60).ifBlank { "A browser" }, now, now).joinToString(SEP))
        // A code is good for one browser.
        newCode()
        return PairResult.Paired(token)
    }

    private val lastTouched = ConcurrentHashMap<String, Long>()

    /** The browser [token] belongs to, or null. Its "last used" moves on at most once a minute. */
    fun check(token: String?): PairedBrowser? {
        if (token.isNullOrBlank() || token.length != 64 || !token.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val h = hash(token)
        val v = store.all()[KEY + h] ?: return null
        val b = parse(h, v) ?: return null
        val now = clock()
        if (now - (lastTouched[h] ?: 0L) > 60_000) {
            lastTouched[h] = now
            store.put(KEY + h, listOf(b.name, b.pairedAt, now).joinToString(SEP))
        }
        return b
    }

    fun browsers(): List<PairedBrowser> = store.all().filterKeys { it.startsWith(KEY) }
        .mapNotNull { (k, v) -> parse(k.removePrefix(KEY), v) }.sortedByDescending { it.lastSeen }

    fun forget(id: String) {
        store.all().keys.filter { it.startsWith(KEY) && it.removePrefix(KEY).startsWith(id) }.forEach { store.remove(it) }
        changes.incrementAndGet()
    }

    fun forgetAll() {
        store.all().keys.filter { it.startsWith(KEY) }.forEach { store.remove(it) }
        changes.incrementAndGet()
    }

    private fun parse(hash: String, v: String): PairedBrowser? {
        val p = v.split(SEP)
        if (p.size != 3) return null
        return PairedBrowser(hash.take(12), p[0], p[1].toLongOrNull() ?: 0, p[2].toLongOrNull() ?: 0)
    }

    companion object {
        private const val KEY = "b:"
        private const val SEP = "\u0001"
        const val PER_ADDRESS = 5
        const val IN_ALL = 20
        const val LOCK_MS = 60_000L
        const val WINDOW_MS = 10 * 60_000L

        fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

/** What the web remote can see and do; the phone's side is [WebRemote], tests have their own. Answers are JSON. */
interface RemoteApi {
    fun phoneName(): String
    fun state(): String
    /** "play", "pause", "toggle", "next", "previous", "seek" (ms), "volume" (0–100), "shuffle", "repeat". */
    fun control(action: String, value: Long?): Boolean
    /** "jump" / "remove" ([index]), "move" ([index] to [to]). */
    fun queue(action: String, index: Int, to: Int): Boolean
    fun search(query: String): String
    /** [song] as the page got it from [search]; [mode] "now", "next" or "later". */
    fun play(song: JsonObject, mode: String): Boolean
    fun lyrics(): String
    /** A local song's cover: its type and bytes. */
    fun art(id: String): Pair<String, ByteArray>?
    /** A counter that moves on whenever what's playing changes; [awaitChange] waits for it to move past [seen]. */
    fun awaitChange(seen: Long, timeoutMs: Long): Long
}

/**
 * The web remote's HTTP side: the page (from [asset]), pairing, and the API, on the local network only. Every request
 * must name this phone by address (a page elsewhere pointing its own domain here is turned away), every API call but
 * pairing needs a paired browser's key, and every change needs the `X-Prism` header, which other sites' pages can't
 * send here without asking first (and they aren't answered).
 */
class RemoteServer(
    private val auth: RemoteAuth,
    private val api: RemoteApi,
    private val asset: (String) -> ByteArray?,
    port: Int = PORT,
    /** Wrong codes wait this long before the answer. */
    private val wrongDelayMs: Long = 700,
) {
    private val http = HttpServer("prism-remote", port, maxBody = 16 * 1024, handler = ::handle)
    private val streams = AtomicInteger()
    private val json = Json { ignoreUnknownKeys = true }

    val port: Int get() = http.port
    val running: Boolean get() = http.running

    fun start(): Boolean = http.start()
    fun stop() = http.stop()

    private val security = mapOf(
        "Content-Security-Policy" to "default-src 'self'; img-src 'self' data: https://*.ytimg.com https://*.googleusercontent.com https://*.ggpht.com; " +
            "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        "X-Frame-Options" to "DENY",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-store",
    )

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (!Lan.hostIsLocalName(req.header("host"))) { res.text(403, "Open Prism's remote by the phone's address", security); return }
        val path = req.path
        if (!path.startsWith("/api/")) { page(path, res); return }
        val change = req.method == "POST"
        if (req.method != "GET" && !change) { res.text(405, "", security + ("Allow" to "GET, POST")); return }
        if (change && req.header("x-prism") != "1") { res.json(403, """{"error":"header"}""", security); return }
        when (path) {
            "/api/hello" -> {
                val paired = auth.check(req.cookie(COOKIE)) != null
                res.json(200, """{"paired":$paired,"name":${q(api.phoneName())},"version":$VERSION}""", security)
                return
            }
            "/api/pair" -> { if (change) pair(req, res) else res.text(405, "", security); return }
        }
        if (auth.check(req.cookie(COOKIE)) == null) { res.json(401, """{"error":"pair"}""", security); return }
        val body = if (change) runCatching { json.parseToJsonElement(req.bodyText) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()) else null
        when {
            path == "/api/state" && !change -> res.json(200, api.state(), security)
            path == "/api/events" && !change -> events(res)
            path == "/api/lyrics" && !change -> res.json(200, api.lyrics(), security)
            path == "/api/search" && !change -> res.json(200, api.search(req.query["q"].orEmpty().take(200)), security)
            path == "/api/art" && !change -> {
                val a = api.art(req.query["id"].orEmpty())
                if (a == null) res.text(404, "", security) else res.send(200, a.first, a.second, security - "Cache-Control" + ("Cache-Control" to "max-age=3600"))
            }
            path == "/api/control" && change -> {
                val action = body!!.str("action") ?: return res.json(400, """{"error":"action"}""", security)
                ok(res, api.control(action, body["value"]?.jsonPrimitive?.longOrNull))
            }
            path == "/api/queue" && change -> {
                val action = body!!.str("action") ?: return res.json(400, """{"error":"action"}""", security)
                ok(res, api.queue(action, body.int("index") ?: -1, body.int("to") ?: -1))
            }
            path == "/api/play" && change -> {
                val song = body!!["song"] as? JsonObject ?: return res.json(400, """{"error":"song"}""", security)
                ok(res, api.play(song, body.str("mode") ?: "now"))
            }
            path == "/api/forget" && change -> {
                auth.check(req.cookie(COOKIE))?.let { auth.forget(it.id) }
                res.json(200, """{"ok":true}""", security + ("Set-Cookie" to "$COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"))
            }
            else -> res.json(404, """{"error":"unknown"}""", security)
        }
    }

    private fun ok(res: HttpResponse, done: Boolean) = res.json(if (done) 200 else 409, """{"ok":$done}""", security)

    private fun pair(req: HttpRequest, res: HttpResponse) {
        val body = runCatching { json.parseToJsonElement(req.bodyText) as? JsonObject }.getOrNull()
        val code = body?.str("code").orEmpty()
        val name = body?.str("name").orEmpty()
        when (val r = auth.pair(code, name, req.remote.hostAddress ?: "?")) {
            is RemoteAuth.PairResult.Paired -> res.json(200, """{"ok":true}""",
                security + ("Set-Cookie" to "$COOKIE=${r.token}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict"))
            RemoteAuth.PairResult.Wrong -> {
                Thread.sleep(wrongDelayMs)
                res.json(403, """{"error":"code"}""", security)
            }
            is RemoteAuth.PairResult.Locked -> res.json(429, """{"error":"locked","seconds":${r.seconds}}""", security + ("Retry-After" to r.seconds.toString()))
        }
    }

    /** Server-sent events: the state now, then whenever it changes (and every few seconds regardless). */
    private fun events(res: HttpResponse) {
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet()
            res.json(503, """{"error":"busy"}""", security)
            return
        }
        try {
            val out = res.start(200, security + mapOf("Content-Type" to "text/event-stream; charset=utf-8", "X-Accel-Buffering" to "no"), null)
            out.write("retry: 3000\n\n".toByteArray())
            var seen = -1L
            while (true) {
                val now = api.awaitChange(seen, if (seen < 0) 0 else HEARTBEAT_MS)
                // Changes come in bursts (a skip changes the song, the queue and the playhead): one message for them.
                if (now != seen && seen >= 0) Thread.sleep(80)
                seen = maxOf(now, api.awaitChange(-2, 0))
                out.write(("event: state\ndata: " + api.state().replace("\n", "") + "\n\n").toByteArray())
                out.flush()
            }
        } finally {
            streams.decrementAndGet()
        }
    }

    private fun page(path: String, res: HttpResponse) {
        val name = when (path) { "/", "/index.html" -> "index.html"; else -> path.removePrefix("/") }
        if (name.contains("..") || name.contains('/') || name.contains('\\')) { res.text(404, "", security); return }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript; charset=utf-8"; "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"; "png" -> "image/png"; "json" -> "application/manifest+json"; "ttf" -> "font/ttf"
            else -> { res.text(404, "", security); return }
        }
        val bytes = asset(name) ?: run { res.text(404, "", security); return }
        res.send(200, type, bytes, security)
    }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull()

    companion object {
        const val PORT = 47320
        const val COOKIE = "prism_remote"
        const val VERSION = 1
        private const val MAX_STREAMS = 6
        private const val HEARTBEAT_MS = 5_000L

        /** A JSON string literal. */
        fun q(s: String): String = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(s))
    }
}

package com.prism.music.connect

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Prism Connect's web remote: who gets in, what they can do, and the live state stream. */
class RemoteServerTest {
    private class MemoryStore : RemoteAuth.Store {
        val map = ConcurrentHashMap<String, String>()
        override fun all() = HashMap(map)
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** A pretend phone: a playing song, a queue, lyrics, and a record of what it was asked to do. */
    class FakePhone : RemoteApi {
        val done = CopyOnWriteArrayList<String>()
        @Volatile var title = "Blinding Lights"
        @Volatile var playing = true
        @Volatile var positionMs = 42_000L
        private val lock = Object()
        private var version = 0L

        fun change() = synchronized(lock) { version++; lock.notifyAll() }

        override fun phoneName() = "Test Phone"
        override fun state() = """{"song":{"title":${RemoteServer.q(title)},"artist":"The Weeknd","album":"After Hours","art":null,"durationMs":200040,
            "s":{"id":"fHI8X4OXluQ","title":${RemoteServer.q(title)},"artists":[{"name":"The Weeknd"}],"durationSec":200}},
            "positionMs":$positionMs,"durationMs":200040,"playing":$playing,"buffering":false,"shuffle":false,"smart":false,"repeat":0,"volume":40,
            "output":"This phone","source":"After Hours","lyricShift":0,"canNext":true,"canPrevious":true,
            "queue":[{"index":0,"song":{"title":"Alone Again","artist":"The Weeknd","durationMs":250000,"s":{"id":"JH398xAYpZA","title":"Alone Again"}},"at":"played"},
            {"index":1,"song":{"title":${RemoteServer.q(title)},"artist":"The Weeknd","durationMs":200040,"s":{"id":"fHI8X4OXluQ","title":${RemoteServer.q(title)}}},"at":"now"},
            {"index":2,"song":{"title":"In Your Eyes","artist":"The Weeknd","durationMs":237000,"s":{"id":"E3QiD99jPAg","title":"In Your Eyes"}},"at":"next"},
            {"index":3,"song":{"title":"Save Your Tears","artist":"The Weeknd","durationMs":215000,"s":{"id":"XXYlFuWEuKI","title":"Save Your Tears"}},"at":"next"}]}"""
        override fun control(action: String, value: Long?): Boolean {
            done += "control $action ${value ?: ""}".trim()
            when (action) { "toggle" -> playing = !playing; "seek" -> positionMs = value ?: positionMs }
            change()
            return action in setOf("play", "pause", "toggle", "next", "previous", "seek", "shuffle", "repeat", "volume")
        }
        override fun queue(action: String, index: Int, to: Int): Boolean { done += "queue $action $index $to"; change(); return index in 0..3 }
        override fun search(query: String) = """{"songs":[{"title":"Starboy","artist":"The Weeknd","durationMs":230000,"s":{"id":"34Na4j8AVgA","title":"Starboy","artists":[{"name":"The Weeknd"}]}}]}"""
        override fun play(song: JsonObject, mode: String): Boolean { done += "play $mode ${song["s"]}"; change(); return true }
        override fun lyrics() = """{"id":"fHI8X4OXluQ","synced":true,"source":"LYRICSPLUS","lines":[
            {"t":40000,"e":43000,"x":"I've been tryna call","w":[{"t":40000,"e":40600,"w":"I've"},{"t":40600,"e":41200,"w":"been"},{"t":41200,"e":42000,"w":"tryna"},{"t":42000,"e":43000,"w":"call"}]},
            {"t":43500,"e":47000,"x":"I've been on my own for long enough","bg":[{"t":45000,"e":46000,"w":"(long"},{"t":46000,"e":47000,"w":"enough)"}]},
            {"t":47500,"e":52000,"x":"Maybe you can show me how to love, maybe"}]}"""
        override fun art(id: String): Pair<String, ByteArray>? = null
        override fun awaitChange(seen: Long, timeoutMs: Long): Long = synchronized(lock) {
            if (version == seen && timeoutMs > 0) lock.wait(timeoutMs)
            version
        }
    }

    // As the app serves them: the page's files, and Prism's own font.
    private fun assets(name: String): ByteArray? = (if (name == "outfit.ttf") File("src/main/res/font/outfit.ttf") else File("src/main/assets/remote/$name")).takeIf { it.isFile }?.readBytes()

    private class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    /** A raw request (HttpURLConnection won't send a Host of our choosing). */
    private fun request(port: Int, method: String, path: String, host: String = "127.0.0.1:$port", headers: Map<String, String> = emptyMap(), body: String = ""): Answer {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 10_000
            val b = body.toByteArray()
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (b.isNotEmpty()) append("Content-Type: application/json\r\nContent-Length: ${b.size}\r\n")
                append("\r\n")
            }
            s.getOutputStream().apply { write(head.toByteArray()); write(b); flush() }
            val text = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val top = text.substringBefore("\r\n\r\n")
            val status = top.lines().first().split(' ')[1].toInt()
            val hs = top.lines().drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Answer(status, hs, text.substringAfter("\r\n\r\n"))
        }
    }

    private val change = mapOf("X-Prism" to "1")

    @Test
    fun onlyPairedBrowsersGetIn() {
        val store = MemoryStore()
        val auth = RemoteAuth(store)
        val phone = FakePhone()
        val server = RemoteServer(auth, phone, ::assets, port = 0, wrongDelayMs = 0)
        assertTrue(server.start())
        val port = server.port
        try {
            // The page itself, with its locks on.
            val page = request(port, "GET", "/")
            assertEquals(200, page.status)
            assertTrue(page.body.contains("Prism Remote"))
            assertTrue(page.headers["content-security-policy"]!!.contains("script-src 'self'"))
            assertEquals("DENY", page.headers["x-frame-options"])
            assertEquals(200, request(port, "GET", "/remote.js").status)
            assertEquals(404, request(port, "GET", "/../build.gradle.kts").status)
            assertEquals(404, request(port, "GET", "/%2e%2e%2fbuild.gradle.kts").status)
            // A page elsewhere that points its own name at the phone (DNS rebinding) gets nothing.
            assertEquals(403, request(port, "GET", "/api/hello", host = "evil.example.com").status)

            assertTrue(request(port, "GET", "/api/hello").body.contains("\"paired\":false"))
            assertEquals(401, request(port, "GET", "/api/state").status)
            assertEquals(401, request(port, "GET", "/api/state", headers = mapOf("Cookie" to "prism_remote=" + "0".repeat(64))).status)
            // Pairing: needs the header, and the code.
            assertEquals(403, request(port, "POST", "/api/pair", body = """{"code":"${auth.code}","name":"Test"}""").status)
            assertEquals(403, request(port, "POST", "/api/pair", headers = change, body = """{"code":"000000","name":"Test"}""").status.also { assertTrue(auth.code != "000000") })
            val code = auth.code
            val paired = request(port, "POST", "/api/pair", headers = change, body = """{"code":"${code.take(3)} ${code.drop(3)}","name":"Chrome on Windows"}""")
            assertEquals(200, paired.status)
            val cookie = paired.headers["set-cookie"]!!
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"))
            val token = cookie.substringAfter("prism_remote=").substringBefore(';')
            assertEquals(64, token.length)
            // Only its hash is kept, and the code was used up.
            assertTrue(store.map.keys.none { it.contains(token) })
            assertNotEquals(code, auth.code)
            assertEquals(listOf("Chrome on Windows"), auth.browsers().map { it.name })

            val me = mapOf("Cookie" to "prism_remote=$token")
            assertTrue(request(port, "GET", "/api/hello", headers = me).body.contains("\"paired\":true"))
            val state = request(port, "GET", "/api/state", headers = me)
            assertEquals(200, state.status)
            assertTrue(state.body.contains("Blinding Lights"))
            // Changes need the header (a form on another site can't add it).
            assertEquals(403, request(port, "POST", "/api/control", headers = me, body = """{"action":"toggle"}""").status)
            assertEquals(200, request(port, "POST", "/api/control", headers = me + change, body = """{"action":"toggle"}""").status)
            assertEquals(200, request(port, "POST", "/api/control", headers = me + change, body = """{"action":"seek","value":61000}""").status)
            assertEquals(409, request(port, "POST", "/api/control", headers = me + change, body = """{"action":"format-the-phone"}""").status)
            assertEquals(400, request(port, "POST", "/api/control", headers = me + change, body = "not json").status)
            assertEquals(200, request(port, "POST", "/api/queue", headers = me + change, body = """{"action":"move","index":3,"to":2}""").status)
            assertEquals(200, request(port, "POST", "/api/play", headers = me + change, body = """{"mode":"next","song":{"title":"Starboy","s":{"id":"34Na4j8AVgA","title":"Starboy"}}}""").status)
            assertTrue(request(port, "GET", "/api/search?q=the%20weeknd", headers = me).body.contains("Starboy"))
            assertTrue(request(port, "GET", "/api/lyrics", headers = me).body.contains("tryna"))
            assertEquals(listOf("control toggle", "control seek 61000", "control format-the-phone", "queue move 3 2"), phone.done.take(4))
            assertTrue(phone.done[4].startsWith("play next {\"id\":\"34Na4j8AVgA\""))

            // Unpairing from the page: the key stops working.
            assertEquals(200, request(port, "POST", "/api/forget", headers = me + change, body = "{}").status)
            assertEquals(401, request(port, "GET", "/api/state", headers = me).status)
            assertTrue(auth.browsers().isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun guessingIsSlowAndThenStopped() {
        var now = 1_000_000L
        val auth = RemoteAuth(MemoryStore()) { now }
        repeat(RemoteAuth.PER_ADDRESS) { assertEquals(RemoteAuth.PairResult.Wrong, auth.pair("111111", "x", "192.168.1.9")) }
        // Locked for that address, even with the right code…
        assertTrue(auth.pair(auth.code, "x", "192.168.1.9") is RemoteAuth.PairResult.Locked)
        // …not for another.
        assertTrue(auth.pair(auth.code, "y", "192.168.1.10") is RemoteAuth.PairResult.Paired)
        now += 61_000
        assertEquals(RemoteAuth.PairResult.Wrong, auth.pair("111111", "x", "192.168.1.9"))
        // Then twice as long.
        assertTrue((auth.pair(auth.code, "x", "192.168.1.9") as RemoteAuth.PairResult.Locked).seconds > 61)

        // Many addresses guessing at once: pairing stops for everyone and the code changes.
        val auth2 = RemoteAuth(MemoryStore()) { now }
        val first = auth2.code
        repeat(RemoteAuth.IN_ALL) { i -> auth2.pair(if (first == "123456") "654321" else "123456", "x", "10.0.0.$i") }
        assertNotEquals(first, auth2.code)
        assertTrue(auth2.pair(auth2.code, "x", "10.0.1.1") is RemoteAuth.PairResult.Locked)
        now += 61_000
        assertTrue(auth2.pair(auth2.code, "x", "10.0.1.1") is RemoteAuth.PairResult.Paired)

        assertNull(auth.check(null))
        assertNull(auth.check("../../etc"))
    }

    @Test
    fun liveStateStream() {
        val phone = FakePhone()
        val auth = RemoteAuth(MemoryStore())
        val server = RemoteServer(auth, phone, ::assets, port = 0, wrongDelayMs = 0)
        assertTrue(server.start())
        try {
            val token = (auth.pair(auth.code, "t", "127.0.0.1") as RemoteAuth.PairResult.Paired).token
            val c = URL("http://127.0.0.1:${server.port}/api/events").openConnection() as HttpURLConnection
            c.setRequestProperty("Cookie", "prism_remote=$token")
            c.readTimeout = 10_000
            assertEquals(200, c.responseCode)
            assertTrue(c.contentType.startsWith("text/event-stream"))
            val r = c.inputStream.bufferedReader()
            fun nextEvent(): String {
                while (true) {
                    val line = r.readLine() ?: throw AssertionError("stream ended")
                    if (line.startsWith("data: ")) return line.removePrefix("data: ")
                }
            }
            assertTrue(nextEvent().contains("Blinding Lights"))
            val t0 = System.currentTimeMillis()
            phone.title = "Save Your Tears"
            phone.change()
            assertTrue(nextEvent().contains("Save Your Tears"))
            // Straight away, not at the next heartbeat.
            assertTrue(System.currentTimeMillis() - t0 < 2_000)
            c.disconnect()
        } finally {
            server.stop()
        }
    }

    /**
     * Not a test: the remote's page against a pretend phone on http://127.0.0.1:47320 for [minutes] (env
     * PRISM_FAKE_REMOTE), to look at in a browser. The pairing code is written to build/fake-remote-code.txt.
     */
    @Test
    fun pageForABrowser() {
        val minutes = System.getenv("PRISM_FAKE_REMOTE")?.toIntOrNull()
        assumeTrue(minutes != null)
        val phone = FakePhone()
        val auth = RemoteAuth(MemoryStore())
        val server = RemoteServer(auth, phone, ::assets, port = RemoteServer.PORT, wrongDelayMs = 0)
        assertTrue(server.start())
        val codeFile = File("build/fake-remote-code.txt")
        val log = File("build/fake-remote.log").apply { writeText("") }
        try {
            val until = System.currentTimeMillis() + minutes!! * 60_000L
            var seen = 0
            while (System.currentTimeMillis() < until) {
                codeFile.writeText(auth.code)
                Thread.sleep(500)
                // The pretend song plays on.
                if (phone.playing) phone.positionMs += 500
                if (phone.done.size > seen) { phone.done.drop(seen).forEach { log.appendText("$it\n") }; seen = phone.done.size }
            }
        } finally {
            server.stop()
        }
        assertNotNull(server)
    }
}

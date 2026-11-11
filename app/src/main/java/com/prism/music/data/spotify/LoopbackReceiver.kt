package com.prism.music.data.spotify

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Where Spotify's authorize page sends the browser back (RFC 8252's loopback redirect): a one-request web server on the
 * phone's own 127.0.0.1, on [port] (0 = one Android picks), open only while a sign-in waits. Nothing outside the phone
 * can reach it. Its answer sends the browser on to [backTo] (prism://spotify, Prism's own intent filter), with a page and
 * a link for browsers that don't follow.
 */
class LoopbackReceiver(
    private val backTo: String,
    private val message: String = "Spotify answered. You can go back to Prism.",
    private val link: String = "Back to Prism",
    port: Int = 0,
) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true // a sign-in started again soon after the last one gets the same port back
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
    }
    val port: Int get() = server.localPort

    /**
     * Waits up to [timeoutMs] for the browser's request to /callback and returns its query string (null on time out
     * or when closed). Other requests (a favicon) get a 404 and the wait goes on.
     */
    fun await(timeoutMs: Long): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (!server.isClosed) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            server.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { return null } catch (_: Exception) { return null }
            val query = runCatching { serve(socket) }.getOrNull()
            if (query != null) return query
        }
        return null
    }

    /** One request: the callback's query, or null for anything else. */
    private fun serve(socket: Socket): String? = socket.use { s ->
        s.soTimeout = 5_000
        val line = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: return null
        val target = requestTarget(line)
        val out = s.getOutputStream()
        if (target == null || !target.path.endsWith("/callback")) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return null
        }
        val page = """<!doctype html><meta name="viewport" content="width=device-width"><title>Prism</title>
            |<body style="font-family:sans-serif;background:#111;color:#eee;text-align:center;padding:48px 16px">
            |<p>${html(message)}</p><p><a style="color:#9cf" href="$backTo">${html(link)}</a></p></body>""".trimMargin()
        val bytes = page.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 302 Found\r\nLocation: $backTo\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(bytes)
        out.flush()
        target.query
    }

    override fun close() { runCatching { server.close() } }

    internal data class Target(val path: String, val query: String)

    companion object {
        private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** "GET /callback?code=…&state=… HTTP/1.1" → its path and query (GET only). */
        internal fun requestTarget(line: String): Target? {
            val parts = line.split(" ")
            if (parts.size < 2 || parts[0] != "GET") return null
            val t = parts[1]
            val i = t.indexOf('?')
            return if (i < 0) Target(t, "") else Target(t.substring(0, i), t.substring(i + 1))
        }
    }
}

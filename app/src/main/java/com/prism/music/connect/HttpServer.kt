package com.prism.music.connect

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The local network: who Prism Connect's servers talk to (and the only addresses they answer). */
object Lan {
    fun isLocal(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        if (a is Inet6Address) {
            val b = a.address
            if ((b[0].toInt() and 0xfe) == 0xfc) return true // unique local fc00::/7
            val mapped = b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1
            if (mapped) return isLocal(InetAddress.getByAddress(b.copyOfRange(12, 16)))
        }
        return false
    }

    /**
     * Whether a Host header names this machine the way a local link does: an IP address, "localhost" or a .local name.
     * A web page elsewhere can point its own domain at a local address (DNS rebinding); its requests then carry that
     * domain here, and are refused.
     */
    fun hostIsLocalName(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        if (h.startsWith("[")) return h.substringBefore(']').removePrefix("[").let { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() || ch == ':' || ch == '.' || ch == '%' } }
        val name = h.substringBefore(':')
        if (name == "localhost" || name.endsWith(".local")) return true
        val parts = name.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true && p.length in 1..3 }
    }

    /** This phone's own address on the local network (Wi-Fi first, then its hotspot, then Ethernet), for links shown to people. */
    fun ownAddress(): String? = ownAddresses().firstOrNull()

    /** This device's own IPv4 addresses on the local network, Wi-Fi first, then its hotspot, then Ethernet. */
    fun ownAddresses(): List<String> = ownNetworks().map { it.first }.filter { it is Inet4Address && it.isSiteLocalAddress }.mapNotNull { it.hostAddress }

    /** This device's own addresses and their networks' prefix lengths, Wi-Fi first (up, not loopback). */
    fun ownNetworks(): List<Pair<InetAddress, Int>> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { i -> listOf("wlan", "swlan", "ap", "eth").indexOfFirst { i.name.startsWith(it) }.let { if (it < 0) 9 else it } }
            .flatMap { i -> i.interfaceAddresses.mapNotNull { a -> a.address?.let { it to a.networkPrefixLength.toInt() } } }
    }.getOrDefault(emptyList())

    /** [a] is on one of [own]'s networks (address and prefix length): the same Wi-Fi, whatever its addresses look like. */
    fun sameNetwork(a: InetAddress, own: List<Pair<InetAddress, Int>>): Boolean = own.any { (mine, prefix) ->
        val x = a.address
        val y = mine.address
        if (x.size != y.size || prefix <= 0) return@any false
        val bits = minOf(prefix, x.size * 8)
        (0 until bits).all { b -> ((x[b / 8].toInt() shr (7 - b % 8)) and 1) == ((y[b / 8].toInt() shr (7 - b % 8)) and 1) }
    }
}

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Lower-case names. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun cookie(name: String): String? = header("cookie")?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    val bodyText: String get() = body.toString(Charsets.UTF_8)
}

/** One answer per connection (the connection closes after it), so a handler either [send]s or [start]s once. */
class HttpResponse internal constructor(private val out: OutputStream, private val head: Boolean) {
    var status = 0
        private set
    val sent: Boolean get() = status != 0

    fun send(status: Int, contentType: String?, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        val h = LinkedHashMap(headers)
        if (contentType != null) h["Content-Type"] = contentType
        start(status, h, body.size.toLong()).apply { if (!head) write(body); flush() }
    }

    fun text(status: Int, text: String, headers: Map<String, String> = emptyMap()) =
        send(status, "text/plain; charset=utf-8", text.toByteArray(), headers)

    fun json(status: Int, json: String, headers: Map<String, String> = emptyMap()) =
        send(status, "application/json; charset=utf-8", json.toByteArray(), headers + ("Cache-Control" to "no-store"))

    /**
     * Writes the status line and headers; the body then goes to the stream returned (nothing for HEAD). [length] null:
     * the body runs until the connection closes (event streams).
     */
    fun start(status: Int, headers: Map<String, String>, length: Long?): OutputStream {
        check(!sent) { "already answered" }
        this.status = status
        val b = StringBuilder()
        b.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        for ((k, v) in headers) b.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n")
        if (length != null) b.append("Content-Length: ").append(length).append("\r\n")
        b.append("Connection: close\r\n\r\n")
        out.write(b.toString().toByteArray(Charsets.ISO_8859_1))
        if (head) { out.flush(); return NullOut }
        return out
    }

    private object NullOut : OutputStream() { override fun write(b: Int) = Unit; override fun write(b: ByteArray, off: Int, len: Int) = Unit }

    companion object {
        fun reason(code: Int) = when (code) {
            200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            411 -> "Length Required"; 413 -> "Payload Too Large"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}

/**
 * A small HTTP/1.1 server for the local network: one request per connection, GET/HEAD/POST, bodies up to [maxBody].
 * Connections from anywhere but the local network ([Lan.isLocal]) are closed unanswered.
 */
class HttpServer(
    private val name: String,
    private val preferredPort: Int,
    private val maxBody: Int = 64 * 1024,
    private val allow: (InetAddress) -> Boolean = Lan::isLocal,
    private val handler: (HttpRequest, HttpResponse) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "$name-http").apply { isDaemon = true }
    }

    val port: Int get() = server?.localPort ?: -1
    val running: Boolean get() = server?.isClosed == false

    /** Listens on [preferredPort], else any free port. False if it couldn't listen at all. */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val ss = runCatching { ServerSocket(preferredPort) }.getOrNull() ?: runCatching { ServerSocket(0) }.getOrNull() ?: return false
        server = ss
        Thread({ acceptLoop(ss) }, "$name-accept").apply { isDaemon = true }.start()
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (_: IOException) { break }
            if (!allow(s.inetAddress)) { runCatching { s.close() }; continue }
            try {
                pool.execute { serve(s) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                runCatching { s.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
                runCatching { s.close() }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 15_000
            s.tcpNoDelay = true
            val input = BufferedInputStream(s.getInputStream())
            val out = java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            val req = read(input, s.inetAddress) ?: run {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            if (req === TOO_BIG) {
                out.write("HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
            // Writing a long body can take as long as the other side takes to read it (a TV reads a song as it plays).
            s.soTimeout = 0
            val res = HttpResponse(out, req.method == "HEAD")
            try {
                handler(req, res)
                if (!res.sent) res.text(404, "Not found")
            } catch (e: SocketException) {
                // They went (a TV seeking closes its last request mid-song).
            } catch (e: IOException) {
                // Same.
            } catch (e: Exception) {
                if (!res.sent) runCatching { res.text(500, "Something went wrong") }
            }
            runCatching { out.flush() }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
    }

    private fun read(input: InputStream, remote: InetAddress): HttpRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3 || !parts[2].startsWith("HTTP/1.")) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0
        if (headers["transfer-encoding"] != null) return null
        if (length > maxBody) return TOO_BIG
        val body = ByteArray(length.toInt())
        var got = 0
        while (got < body.size) {
            val n = input.read(body, got, body.size - got)
            if (n < 0) return null
            got += n
        }
        val rawPath = target.substringBefore('?')
        val path = runCatching { URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        return HttpRequest(method, path, parseQuery(target.substringAfter('?', "")), headers, body, remote)
    }

    /** Up to the blank line; null if it's longer than 16 KB or the connection ends first. */
    private fun readHead(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream(512)
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) return buf.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
            if (buf.size() > 16 * 1024) return null
        }
    }

    companion object {
        const val MAX_CONNECTIONS = 24
        private val TOO_BIG = HttpRequest("", "", emptyMap(), emptyMap(), ByteArray(0), InetAddress.getLoopbackAddress())

        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            dec(k) to dec(v)
        }

        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

/** A byte range asked for with a Range header, inclusive. */
sealed interface ByteRange {
    data object Whole : ByteRange
    data class Part(val start: Long, val end: Long) : ByteRange { val length get() = end - start + 1 }
    data object Unsatisfiable : ByteRange

    companion object {
        /** "bytes=a-b", "bytes=a-", "bytes=-n"; with several ranges only the first is served. Anything else: the whole. */
        fun parse(header: String?, total: Long): ByteRange {
            val h = header?.trim() ?: return Whole
            if (!h.startsWith("bytes=", ignoreCase = true)) return Whole
            val first = h.substring(6).split(',').first().trim()
            val dash = first.indexOf('-')
            if (dash < 0) return Whole
            val a = first.substring(0, dash).trim()
            val b = first.substring(dash + 1).trim()
            if (total <= 0) return Unsatisfiable
            return when {
                a.isEmpty() -> {
                    val n = b.toLongOrNull() ?: return Whole
                    if (n <= 0) Unsatisfiable else Part((total - n).coerceAtLeast(0), total - 1)
                }
                else -> {
                    val start = a.toLongOrNull() ?: return Whole
                    val end = if (b.isEmpty()) total - 1 else (b.toLongOrNull() ?: return Whole).coerceAtMost(total - 1)
                    if (start >= total || end < start) Unsatisfiable else Part(start, end)
                }
            }
        }
    }
}

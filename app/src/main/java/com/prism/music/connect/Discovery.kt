package com.prism.music.connect

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/** SSDP searches for renderers (the same on the phone and in tests, which point [to] at a pretend one). */
object SsdpSearch {
    /**
     * Asks for MediaRenderers and Sonos speakers (three times, as UDP can drop a packet) and passes every answer that's about one to
     * [onFound] for [windowMs]. Blocking.
     */
    fun run(
        windowMs: Long,
        to: InetSocketAddress = InetSocketAddress(Ssdp.ADDRESS, Ssdp.PORT),
        stop: () -> Boolean = { false },
        onFound: (SsdpMessage, InetAddress) -> Unit,
    ) {
        DatagramSocket().use { s ->
            s.soTimeout = 300
            val until = System.currentTimeMillis() + windowMs
            val buf = ByteArray(8 * 1024)
            var rounds = 0
            var nextSend = 0L
            while (System.currentTimeMillis() < until && !stop()) {
                if (rounds < 3 && System.currentTimeMillis() >= nextSend) {
                    for (t in listOf(Ssdp.RENDERER, Ssdp.AV_TRANSPORT, Ssdp.ZONE_PLAYER)) {
                        val b = Ssdp.search(t).toByteArray()
                        runCatching { s.send(DatagramPacket(b, b.size, to)) }
                    }
                    rounds++
                    nextSend = System.currentTimeMillis() + 350
                }
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                val m = SsdpMessage.parse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                if (Ssdp.isRenderer(m) && Ssdp.speaksForItself(m, p.address)) onFound(m, p.address)
            }
        }
    }
}

object Describe {
    /** Whether [location] is on the local network, by address (names aren't looked up: a renderer gives its address). */
    fun isLocalLink(location: String): Boolean = runCatching {
        val u = URI(location)
        if (u.scheme?.lowercase() != "http") return@runCatching false
        val host = u.host?.removePrefix("[")?.removeSuffix("]") ?: return@runCatching false
        val literal = host.contains(':') || host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
        literal && Lan.isLocal(InetAddress.getByName(host))
    }.getOrDefault(false)

    /**
     * The renderer a description at [location] describes, or null (not on the local network, not a renderer, no answer).
     * [problem] hears why, for diagnostics: a speaker that answers the search and then isn't listed is otherwise silent.
     */
    fun fetch(http: OkHttpClient, location: String, problem: (String) -> Unit = {}): Renderer? {
        if (!isLocalLink(location)) { problem("not on the local network: $location"); return null }
        val client = with(LanHttp) { http.newBuilder().lanFirst() }.connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
        val text = runCatching {
            client.newCall(Request.Builder().url(location).header("User-Agent", "Android UPnP/1.1 Prism/1.7").build()).execute().use { r ->
                if (!r.isSuccessful) { problem("HTTP ${r.code} from $location"); null } else r.body?.byteStream()?.use { s -> s.readNBytesCompat(512 * 1024) }?.toString(Charsets.UTF_8)
            }
        }.onFailure { problem("couldn't read $location: $it") }.getOrNull() ?: return null
        return Description.parse(text, location).also { if (it == null) problem("$location doesn't describe something that plays") }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

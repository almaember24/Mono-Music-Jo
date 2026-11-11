package com.prism.music.connect

import com.sun.net.httpserver.HttpExchange
import java.io.Closeable
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * A pretend UPnP / DLNA MediaRenderer, written apart from Prism's own HTTP code: a description, AVTransport,
 * RenderingControl and ConnectionManager over SOAP (on the JDK's own HTTP server), an SSDP responder, and a player
 * that fetches what it's sent with Range requests, as TVs do. With [audible] it plays WAV / L16 on this PC's speakers,
 * so casting can be tried from the phone without a TV (see [SimulatedRendererTest.castFromThePhone]).
 */
class FakeRenderer(
    private val sink: String = "http-get:*:audio/wav:*,http-get:*:audio/L16:*,http-get:*:audio/mpeg:*",
    /** MIME types SetAVTransportURI turns down with error 714, as a fussy TV would. */
    private val refuse: Set<String> = emptySet(),
    private val gapless: Boolean = true,
    /** False: seeks with milliseconds are refused (711), as some strict renderers do. */
    private val preciseSeeks: Boolean = true,
    bind: String = "127.0.0.1",
    port: Int = 0,
    private val audible: Boolean = false,
    val name: String = "Test Renderer",
    private val log: (String) -> Unit = {},
    /** A real device's own description instead of the made-up one, with its control URLs answered all the same. */
    private val descriptionXml: String? = null,
    private val descriptionPath: String = "/description.xml",
    /** Search targets it answers; null: MediaRenderer, AVTransport and ssdp:all. Sonos: its ZonePlayer. */
    private val answersTo: Set<String>? = null,
    /** The error a seek with milliseconds gets when [preciseSeeks] is false. */
    private val seekRefusal: Int = 711,
) : Closeable {
    class Fetch(val url: String, val range: String?, val status: Int, val type: String?, val contentRange: String?, val bytes: ByteArray)

    private val http = com.sun.net.httpserver.HttpServer.create(InetSocketAddress(InetAddress.getByName(bind), port), 0)
    private val ssdp = DatagramSocket(InetSocketAddress(InetAddress.getByName(bind), 0))
    val fetches = CopyOnWriteArrayList<Fetch>()
    val actions = CopyOnWriteArrayList<String>()

    @Volatile var state = "NO_MEDIA_PRESENT"; private set
    @Volatile var uri: String? = null; private set
    @Volatile var meta: String? = null; private set
    @Volatile var nextUri: String? = null; private set
    @Volatile var volume = 30
    @Volatile private var nextMeta: String? = null
    @Volatile private var baseMs = 0L
    @Volatile private var since = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var closed = false
    private var player: Thread? = null

    val port: Int get() = http.address.port
    val ssdpPort: Int get() = ssdp.localPort
    val location: String get() = "http://127.0.0.1:$port$descriptionPath"
    val positionMs: Long get() = if (state == "PLAYING") baseMs + (System.currentTimeMillis() - since) else baseMs

    init {
        http.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { log("fake renderer: $it"); runCatching { ex.sendResponseHeaders(500, -1) } }; ex.close() }
        http.executor = java.util.concurrent.Executors.newCachedThreadPool()
        http.start()
        Thread({ answerSearches() }, "fake-ssdp").apply { isDaemon = true }.start()
        Thread({ tick() }, "fake-clock").apply { isDaemon = true }.start()
    }

    override fun close() {
        closed = true
        stopPlayer()
        http.stop(0)
        ssdp.close()
    }

    private fun answerSearches() {
        val buf = ByteArray(4096)
        while (!closed) {
            val p = DatagramPacket(buf, buf.size)
            try { ssdp.receive(p) } catch (_: Exception) { break }
            val text = String(p.data, 0, p.length)
            if (!text.startsWith("M-SEARCH")) continue
            val st = text.lines().firstOrNull { it.startsWith("ST:", true) }?.substringAfter(':')?.trim() ?: continue
            if (answersTo != null) { if (st !in answersTo) continue }
            else if (!st.contains("MediaRenderer") && !st.contains("AVTransport") && st != "ssdp:all") continue
            val answer = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: $location\r\nSERVER: Fake/1.0 UPnP/1.0\r\n" +
                "ST: $st\r\nUSN: uuid:fake-renderer-1::$st\r\n\r\n"
            val b = answer.toByteArray()
            runCatching { ssdp.send(DatagramPacket(b, b.size, p.socketAddress)) }
        }
    }

    /** The end of a track: on to the next one (gapless), or stopped. */
    private fun tick() {
        while (!closed) {
            Thread.sleep(50)
            if (state == "PLAYING" && durationMs > 0 && positionMs >= durationMs) {
                val n = nextUri
                if (n != null) {
                    log("fake renderer: on to the next track")
                    uri = n; meta = nextMeta; nextUri = null; nextMeta = null
                    durationMs = durationOf(meta)
                    baseMs = 0; since = System.currentTimeMillis()
                    startPlayer(0)
                } else {
                    baseMs = durationMs
                    state = "STOPPED"
                    stopPlayer()
                }
            }
        }
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        if (ex.requestMethod == "GET" && path == descriptionPath) return reply(ex, 200, descriptionXml ?: description())
        if (ex.requestMethod != "POST") return reply(ex, 405, "")
        val action = ex.requestHeaders.getFirst("SOAPACTION")?.trim('"')?.substringAfter('#') ?: return reply(ex, 400, "")
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val args = with(Xml) { Xml.parse(body)!!.documentElement.all(action).first().let { a -> a.childNodes.let { l -> (0 until l.length).mapNotNull { l.item(it) as? org.w3c.dom.Element }.associate { it.name() to it.textContent } } } }
        actions += action
        val service = when {
            path.contains("RenderingControl") -> "rc"
            path.contains("ConnectionManager") -> "cm"
            else -> path.substringAfterLast('/')
        }
        val out: Map<String, String> = when (action) {
            "SetAVTransportURI" -> {
                val mime = Xml.parse(args["CurrentURIMetaData"].orEmpty())?.let { d -> with(Xml) { d.documentElement.all("res").firstOrNull()?.getAttribute("protocolInfo") } }
                    ?.split(':')?.getOrNull(2)?.substringBefore(';')?.lowercase()
                if (mime != null && mime in refuse) return fault(ex, 714, "Illegal MIME-type")
                stopPlayer()
                uri = args["CurrentURI"]; meta = args["CurrentURIMetaData"]; nextUri = null
                durationMs = durationOf(meta)
                baseMs = 0
                state = "STOPPED"
                emptyMap()
            }
            "SetNextAVTransportURI" -> {
                if (!gapless) return fault(ex, 401, "Invalid Action")
                nextUri = args["NextURI"]; nextMeta = args["NextURIMetaData"]
                emptyMap()
            }
            "Play" -> {
                if (uri == null) return fault(ex, 701, "Transition not available")
                if (state != "PLAYING") {
                    if (state == "STOPPED" && baseMs >= durationMs && durationMs > 0) baseMs = 0
                    since = System.currentTimeMillis()
                    state = "PLAYING"
                    startPlayer(baseMs)
                }
                emptyMap()
            }
            "Pause" -> { if (state == "PLAYING") { baseMs = positionMs; state = "PAUSED_PLAYBACK"; pausePlayer() }; emptyMap() }
            "Stop" -> { baseMs = 0; state = if (uri == null) "NO_MEDIA_PRESENT" else "STOPPED"; stopPlayer(); emptyMap() }
            "Seek" -> {
                if (!preciseSeeks && args["Target"].orEmpty().contains('.')) return fault(ex, seekRefusal, "Illegal seek target")
                val to = UpnpTime.parse(args["Target"]) ?: return fault(ex, 711, "Illegal seek target")
                if (state == "STOPPED" || state == "NO_MEDIA_PRESENT") return fault(ex, 701, "Transition not available")
                baseMs = to; since = System.currentTimeMillis()
                if (state == "PLAYING") startPlayer(to)
                emptyMap()
            }
            "GetTransportInfo" -> mapOf("CurrentTransportState" to state, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> mapOf(
                "Track" to "1", "TrackDuration" to UpnpTime.format(durationMs), "TrackMetaData" to meta.orEmpty(), "TrackURI" to uri.orEmpty(),
                // Whole seconds, as most TVs report.
                "RelTime" to UpnpTime.format(positionMs), "AbsTime" to "NOT_IMPLEMENTED", "RelCount" to "2147483647", "AbsCount" to "2147483647",
            )
            "GetVolume" -> mapOf("CurrentVolume" to volume.toString())
            "SetVolume" -> { volume = args["DesiredVolume"]?.toIntOrNull() ?: volume; emptyMap() }
            "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to sink)
            else -> return fault(ex, 401, "Invalid Action")
        }
        log("fake renderer: $action ${args.filterKeys { it != "CurrentURIMetaData" && it != "NextURIMetaData" && it != "InstanceID" }} -> $state")
        val type = when (service) { "rc" -> "urn:schemas-upnp-org:service:RenderingControl:1"; "cm" -> "urn:schemas-upnp-org:service:ConnectionManager:1"; else -> "urn:schemas-upnp-org:service:AVTransport:1" }
        reply(ex, 200, "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:${action}Response xmlns:u=\"$type\">" + out.entries.joinToString("") { "<${it.key}>${Xml.escape(it.value)}</${it.key}>" } +
            "</u:${action}Response></s:Body></s:Envelope>", "text/xml; charset=\"utf-8\"")
    }

    private fun durationOf(meta: String?): Long =
        Xml.parse(meta.orEmpty())?.let { d -> with(Xml) { d.documentElement.all("res").firstOrNull()?.getAttribute("duration") } }?.let { UpnpTime.parse(it) } ?: 0L

    private fun fault(ex: HttpExchange, code: Int, text: String) = reply(ex, 500,
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>" +
            "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode><errorDescription>$text</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>",
        "text/xml; charset=\"utf-8\"")

    private fun reply(ex: HttpExchange, status: Int, body: String, type: String = "text/xml; charset=\"utf-8\"") {
        val b = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) ex.responseBody.use { it.write(b) }
    }

    private fun description() = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>$name</friendlyName><manufacturer>Prism tests</manufacturer>
<modelName>Fake</modelName><UDN>uuid:fake-renderer-1</UDN><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/av.xml</SCPDURL><controlURL>/ctl/av</controlURL><eventSubURL>/evt/av</eventSubURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/rc.xml</SCPDURL><controlURL>/ctl/rc</controlURL><eventSubURL>/evt/rc</eventSubURL></service>
<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/cm.xml</SCPDURL><controlURL>/ctl/cm</controlURL><eventSubURL>/evt/cm</eventSubURL></service>
</serviceList></device></root>"""

    // ------------------------------------------------------------ The player

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var playGen = 0

    /** Fetches the track from [fromMs] (by byte offset, like a TV seeking a WAV), and plays it out loud when [audible]. */
    @Synchronized
    private fun startPlayer(fromMs: Long) {
        stopPlayer()
        val u = uri ?: return
        val gen = ++playGen
        player = Thread({ play(u, fromMs, gen) }, "fake-player").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stopPlayer() {
        playGen++
        player?.interrupt()
        player = null
        line?.let { runCatching { it.stop(); it.flush() } }
    }

    private fun pausePlayer() { playGen++; line?.let { runCatching { it.stop(); it.flush() } } }

    private fun play(u: String, fromMs: Long, gen: Int) {
        // First the head (as TVs do, to read the WAV header), then the stream from where it's to start.
        var rate = 0
        var bigEndian = false
        var header = 0
        val head = fetch(u, "bytes=0-43")
        val type = head?.type.orEmpty()
        if (type.startsWith("audio/L16", true)) {
            bigEndian = true
            rate = Regex("rate=(\\d+)").find(type)?.groupValues?.get(1)?.toInt() ?: 44100
        } else if (head != null && head.bytes.size >= 44 && String(head.bytes, 0, 4) == "RIFF") {
            header = 44
            rate = java.nio.ByteBuffer.wrap(head.bytes, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        }
        if (rate <= 0) {
            // Not PCM (an MP3 sent as it is): just read it.
            fetch(u, null)
            return
        }
        val offset = header + fromMs * rate / 1000 * 4
        val c = (URL(u).openConnection() as HttpURLConnection)
        c.setRequestProperty("Range", "bytes=$offset-")
        c.setRequestProperty("getcontentFeatures.dlna.org", "1")
        val status = c.responseCode
        val input: InputStream = c.inputStream
        val got = java.io.ByteArrayOutputStream()
        try {
            val out = if (audible) openLine(rate, bigEndian) else null
            val buf = ByteArray(16 * 1024)
            while (gen == playGen && !closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (out != null) {
                    // Pause: hold here until playing again (or replaced).
                    while (state == "PAUSED_PLAYBACK" && gen == playGen) Thread.sleep(50)
                    if (gen != playGen) break
                    if (!out.isRunning) out.start()
                    out.write(buf, 0, n - n % 4)
                } else if (got.size() < MAX_KEPT) got.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { input.close() }
            fetches += Fetch(u, "bytes=$offset-", status, c.contentType, c.getHeaderField("Content-Range"), got.toByteArray())
        }
    }

    private fun fetch(u: String, range: String?): Fetch? = runCatching {
        val c = (URL(u).openConnection() as HttpURLConnection)
        if (range != null) c.setRequestProperty("Range", range)
        val bytes = c.inputStream.use { s -> s.readBytes().let { if (it.size > MAX_KEPT) it.copyOf(MAX_KEPT) else it } }
        Fetch(u, range, c.responseCode, c.contentType, c.getHeaderField("Content-Range"), bytes).also { fetches += it }
    }.getOrNull()

    private fun openLine(rate: Int, bigEndian: Boolean): SourceDataLine {
        val format = AudioFormat(rate.toFloat(), 16, 2, true, bigEndian)
        val l = line?.takeIf { it.format.matches(format) } ?: AudioSystem.getSourceDataLine(format).also { n ->
            line?.close()
            n.open(format, rate * 4 / 2)
            line = n
        }
        l.start()
        return l
    }

    private companion object { const val MAX_KEPT = 8 * 1024 * 1024 }
}

package com.prism.music.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * A pretend Google Cast device for tests: speaks the Cast v2 protocol (over a plain socket rather than TLS) the way a
 * Chromecast's platform and Default Media Receiver answer, with a playhead that runs while it's "playing". It records
 * every message it gets ([received]). [fetch] makes it read the head of each song it's sent, as a TV does; with
 * [audible] it plays WAV out loud on this PC (for casting from the phone through adb, [rewriteHost]).
 */
class FakeCastDevice(
    bind: String = "127.0.0.1",
    port: Int = 0,
    private val fetch: Boolean = true,
    private val audible: Boolean = false,
    /** Fetches from here instead of the host in the URL (the phone's media server through `adb forward`). */
    private val rewriteHost: String? = null,
    private val failLoads: Boolean = false,
    /** Over TLS with a self-signed certificate, as a real device (what the app connects with). */
    tls: Boolean = false,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val server = if (tls) tlsServer(port, bind) else ServerSocket(port, 4, InetAddress.getByName(bind))
    val port: Int get() = server.localPort

    /** Every message the device was sent: namespace and its JSON. */
    val received = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    val fetched = CopyOnWriteArrayList<String>()

    @Volatile var appRunning = false
    @Volatile var volume = 0.5
    @Volatile var state = "IDLE"
    @Volatile var idleReason: String? = null
    @Volatile var contentId: String? = null
    @Volatile var contentType: String? = null
    @Volatile var durationS = 0.0
    @Volatile private var positionS = 0.0
    @Volatile private var since = 0L
    @Volatile private var mediaSession = 0
    @Volatile private var closed = false
    private var out: OutputStream? = null
    private val sessionId = "fake-session-1"
    private val transportId = "fake-transport-1"

    init {
        Thread({
            while (!closed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ serve(s) }, "fake-cast-client").apply { isDaemon = true; start() }
            }
        }, "fake-cast").apply { isDaemon = true; start() }
    }

    fun location() = "googlecast://127.0.0.1:$port"
    fun renderer() = GoogleCastDevices.byLocation(location())!!
    fun types(namespace: String) = received.filter { it.first == namespace }.mapNotNull { (it.second["type"] as? JsonPrimitive)?.content }

    /** Where it is in the song now. */
    fun position(): Double = if (state == "PLAYING") positionS + (System.currentTimeMillis() - since) / 1000.0 else positionS

    /** The song runs out, as if it played to its end. */
    fun finish() { positionS = durationS; state = "IDLE"; idleReason = "FINISHED"; mediaSession = 0; stopSound(); broadcastMedia() }

    /** Another app opens on the TV (YouTube, say): Prism's player is gone. */
    fun takeOver() {
        appRunning = false
        state = "IDLE"; mediaSession = 0
        runCatching { send(CastNs.RECEIVER, "*", receiverStatus(0, otherApp = true)) }
    }

    override fun close() {
        closed = true
        stopSound()
        runCatching { server.close() }
    }

    private fun serve(s: Socket) {
        s.use {
            out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream().buffered())
            while (!closed) {
                val m = runCatching { CastWire.read(input) }.getOrElse { break } ?: continue
                val json = Json.parseToJsonElement(m.payload).jsonObject
                received += m.namespace to json
                log("${m.namespace.substringAfterLast('.')} ${json["type"]?.jsonPrimitive?.content}")
                handle(m, json)
            }
        }
    }

    private fun handle(m: CastMessage, json: JsonObject) {
        val type = json["type"]?.jsonPrimitive?.content
        val id = json["requestId"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        when (m.namespace) {
            CastNs.HEARTBEAT -> if (type == "PING") send(CastNs.HEARTBEAT, m.source, buildJsonObject { put("type", "PONG") })
            CastNs.RECEIVER -> when (type) {
                "GET_STATUS" -> send(CastNs.RECEIVER, m.source, receiverStatus(id))
                "LAUNCH" -> { appRunning = true; send(CastNs.RECEIVER, m.source, receiverStatus(id)) }
                "SET_VOLUME" -> {
                    json["volume"]?.jsonObject?.get("level")?.jsonPrimitive?.doubleOrNull?.let { volume = it }
                    send(CastNs.RECEIVER, m.source, receiverStatus(id))
                }
                "STOP" -> { appRunning = false; state = "IDLE"; mediaSession = 0; stopSound(); send(CastNs.RECEIVER, m.source, receiverStatus(id)) }
            }
            CastNs.MEDIA -> when (type) {
                "LOAD" -> {
                    val media = json["media"]!!.jsonObject
                    if (failLoads) { send(CastNs.MEDIA, m.source, buildJsonObject { put("type", "LOAD_FAILED"); put("requestId", id) }); return }
                    contentId = media["contentId"]?.jsonPrimitive?.content
                    contentType = media["contentType"]?.jsonPrimitive?.content
                    durationS = media["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    positionS = json["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    mediaSession++
                    idleReason = null
                    if (fetch) contentId?.let { u -> runCatching { head(u) } }
                    state = if (json["autoplay"]?.jsonPrimitive?.content == "false") "PAUSED" else "PLAYING"
                    since = System.currentTimeMillis()
                    if (state == "PLAYING") startSound()
                    send(CastNs.MEDIA, m.source, mediaStatus(id))
                }
                "PLAY" -> { if (state != "PLAYING") { since = System.currentTimeMillis(); state = "PLAYING"; startSound() }; send(CastNs.MEDIA, m.source, mediaStatus(id)) }
                "PAUSE" -> { positionS = position(); state = "PAUSED"; stopSound(); send(CastNs.MEDIA, m.source, mediaStatus(id)) }
                "SEEK" -> {
                    positionS = json["currentTime"]?.jsonPrimitive?.doubleOrNull ?: positionS
                    since = System.currentTimeMillis()
                    if (state == "PLAYING") startSound()
                    send(CastNs.MEDIA, m.source, mediaStatus(id))
                }
                "STOP" -> { state = "IDLE"; idleReason = "CANCELLED"; mediaSession = 0; stopSound(); send(CastNs.MEDIA, m.source, mediaStatus(id)) }
                "GET_STATUS" -> send(CastNs.MEDIA, m.source, mediaStatus(id))
            }
        }
    }

    private fun receiverStatus(id: Int, otherApp: Boolean = false) = buildJsonObject {
        put("type", "RECEIVER_STATUS")
        put("requestId", id)
        putJsonObject("status") {
            putJsonObject("volume") { put("level", volume); put("muted", false) }
            putJsonArray("applications") {
                if (appRunning) add(buildJsonObject {
                    put("appId", CastNs.DEFAULT_RECEIVER); put("sessionId", sessionId); put("transportId", transportId); put("displayName", "Default Media Receiver")
                })
                if (otherApp) add(buildJsonObject { put("appId", "233637DE"); put("sessionId", "yt"); put("transportId", "yt-t"); put("displayName", "YouTube") })
            }
        }
    }

    private fun mediaStatus(id: Int) = buildJsonObject {
        put("type", "MEDIA_STATUS")
        put("requestId", id)
        put("status", buildJsonArray {
            if (mediaSession > 0 || state != "IDLE") add(buildJsonObject {
                put("mediaSessionId", mediaSession)
                put("playerState", state)
                idleReason?.let { put("idleReason", it) }
                put("currentTime", position())
                putJsonObject("media") { contentId?.let { put("contentId", it) }; put("duration", durationS) }
            })
        })
    }

    private fun broadcastMedia() = runCatching { send(CastNs.MEDIA, "*", mediaStatus(0)) }

    @Synchronized
    private fun send(namespace: String, destination: String, json: JsonObject) {
        val src = if (namespace == CastNs.MEDIA) transportId else CastNs.PLATFORM
        CastWire.write(out ?: return, CastMessage(src, destination, namespace, json.toString()))
    }

    private fun localUrl(u: String) = rewriteHost?.let { h -> u.replace(Regex("^http://[^/]+"), "http://$h") } ?: u

    private fun head(u: String) {
        val c = URL(localUrl(u)).openConnection() as HttpURLConnection
        c.setRequestProperty("Range", "bytes=0-43")
        c.inputStream.use { it.readBytes() }
        fetched += u
    }

    /** A TLS server socket with a fresh self-signed certificate (made with the JDK's keytool), like a Cast device's. */
    private fun tlsServer(port: Int, bind: String): ServerSocket {
        val pass = "prismcast".toCharArray()
        val file = java.io.File.createTempFile("fakecast", ".p12").apply { delete(); deleteOnExit() }
        val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val p = ProcessBuilder(
            keytool.path, "-genkeypair", "-alias", "cast", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Fake Cast",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", file.path, "-storepass", String(pass), "-keypass", String(pass),
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor() == 0) { "keytool failed" }
        val store = java.security.KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, pass) } }
        // The device's end uses the same TLS as the app's end (the JVM's default now). Robolectric tests that ran earlier
        // in this JVM make Conscrypt (the phone's TLS) the default, and the app's Conscrypt against the JDK's own TLS
        // here never finished its handshake (2026-11-07: overTlsLikeARealDevice failed in every full run, passed alone).
        val kmf = javax.net.ssl.KeyManagerFactory.getInstance("SunX509", "SunJSSE").apply { init(store, pass) }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return ctx.serverSocketFactory.createServerSocket(port, 4, InetAddress.getByName(bind))
    }

    // ------------------------------------------------------------ Out loud (the phone test)

    @Volatile private var soundGen = 0
    private var line: SourceDataLine? = null

    private fun stopSound() { soundGen++; streamed = 0; line?.let { runCatching { it.stop(); it.flush() } } }

    /** Bytes of sound read since the last load (it streams, out loud or not, once there's a [rewriteHost] to fetch from). */
    @Volatile var streamed = 0L

    private fun startSound() {
        if (!audible && rewriteHost == null) return
        val u = contentId ?: return
        val gen = ++soundGen
        val from = position()
        Thread({
            runCatching {
                val c = URL(localUrl(u)).openConnection() as HttpURLConnection
                c.setRequestProperty("Range", "bytes=0-43")
                val head = c.inputStream.use { it.readBytes() }
                if (head.size < 44 || String(head, 0, 4) != "RIFF") return@runCatching
                val rate = java.nio.ByteBuffer.wrap(head, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                val body = URL(localUrl(u)).openConnection() as HttpURLConnection
                body.setRequestProperty("Range", "bytes=${44 + (from * rate).toLong() * 4}-")
                val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
                val l = if (!audible) null else line?.takeIf { it.format.matches(format) } ?: AudioSystem.getSourceDataLine(format).also { n -> line?.close(); n.open(format, rate * 2); line = n }
                l?.start()
                log("streaming $u from ${"%.1f".format(from)} s at $rate Hz")
                body.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    var lastLog = 0L
                    while (gen == soundGen && !closed) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (l != null) l.write(buf, 0, n - n % 4)
                        else Thread.sleep((n / 4) * 1000L / rate) // as fast as it'd play
                        streamed += n
                        if (streamed - lastLog > 2_000_000) { lastLog = streamed; log("streamed ${streamed / 1024} KB, at ${"%.1f".format(position())} s") }
                    }
                }
            }.onFailure { log("sound: ${it.message}") }
        }, "fake-cast-sound").apply { isDaemon = true; start() }
    }
}

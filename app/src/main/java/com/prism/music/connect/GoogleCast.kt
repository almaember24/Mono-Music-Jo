package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

/**
 * One Google Cast ("CASTV2") message: the CastMessage protobuf every Cast device speaks, here always with a JSON
 * payload. Written and read by hand (seven fields), so there's no protobuf library or Google SDK in Prism.
 */
data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7F.inv().toLong() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun field(n: Int, s: String) { val b = s.toByteArray(Charsets.UTF_8); varint(((n shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        varint((1 shl 3).toLong()); varint(0) // protocol_version: CASTV2_1_0
        field(2, source)
        field(3, destination)
        field(4, namespace)
        varint((5 shl 3).toLong()); varint(0) // payload_type: STRING
        field(6, payload)
        return out.toByteArray()
    }

    companion object {
        /** Null for anything that isn't a CastMessage with a text payload (binary ones, device-auth, are never needed here). */
        fun decode(bytes: ByteArray): CastMessage? {
            var i = 0
            fun varint(): Long {
                var shift = 0; var v = 0L
                while (true) {
                    if (i >= bytes.size || shift > 63) throw IOException("bad varint")
                    val b = bytes[i++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return v
                    shift += 7
                }
            }
            val strings = HashMap<Int, String>()
            return runCatching {
                while (i < bytes.size) {
                    val key = varint()
                    val n = (key ushr 3).toInt()
                    when ((key and 7).toInt()) {
                        0 -> varint()
                        2 -> {
                            val len = varint().toInt()
                            if (len < 0 || i + len > bytes.size) throw IOException("bad length")
                            if (n in 2..6) strings[n] = String(bytes, i, len, Charsets.UTF_8)
                            i += len
                        }
                        1 -> i += 8
                        5 -> i += 4
                        else -> throw IOException("bad wire type")
                    }
                }
                CastMessage(strings[2] ?: return null, strings[3] ?: return null, strings[4] ?: return null, strings[6] ?: return null)
            }.getOrNull()
        }
    }
}

/** Cast's framing: each message is its length (4 bytes, big-endian) and then its bytes. */
object CastWire {
    private const val MAX = 64 * 1024

    fun write(out: OutputStream, m: CastMessage) {
        val b = m.encode()
        val head = byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte())
        out.write(head + b)
        out.flush()
    }

    /** The next message (null for one that isn't JSON text, which is skipped); throws at the end of the stream. */
    fun read(input: DataInputStream): CastMessage? {
        val len = input.readInt()
        if (len < 0 || len > MAX) throw IOException("Cast message of $len bytes")
        val b = ByteArray(len)
        input.readFully(b)
        return CastMessage.decode(b)
    }
}

/** Google Cast devices as mDNS announces them (_googlecast._tcp, with the device's details in its TXT record). */
object GoogleCastDevices {
    const val SERVICE = "_googlecast._tcp"

    /**
     * The device a service describes: "fn" is the name people gave it ("Living Room TV"), "md" its model ("Google TV
     * Streamer", "Chromecast", "Google Cast Group"), "id" its stable id. Null without an address.
     */
    fun renderer(serviceName: String, host: String, port: Int, txt: Map<String, String>): Renderer? {
        if (host.isBlank() || port <= 0) return null
        val id = txt["id"]?.takeIf { it.isNotBlank() } ?: serviceName
        val model = txt["md"].orEmpty()
        val name = txt["fn"]?.takeIf { it.isNotBlank() } ?: model.ifBlank { serviceName.substringBeforeLast('-') }
        val address = if (host.contains(':')) "[$host]" else host
        return Renderer(
            id = "cast:$id", name = name, manufacturer = "", model = model,
            location = "googlecast://$address:$port", avTransport = null, kind = RendererKind.GOOGLE_CAST,
        )
    }

    /** "googlecast://host:port" (the debug hook's way of adding one, e.g. a test receiver on a PC). */
    fun byLocation(location: String): Renderer? = runCatching {
        val u = java.net.URI(location)
        if (u.scheme != "googlecast") return null
        renderer("Cast test device", u.host ?: return null, if (u.port > 0) u.port else 8009, mapOf("fn" to "Cast test (${u.host})", "id" to "${u.host}:${u.port}"))
    }.getOrNull()
}

/** The Cast namespaces Prism uses, and the receiver app it plays in. */
object CastNs {
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"
    const val PLATFORM = "receiver-0"
    const val SENDER = "sender-prism"

    /** Google's Default Media Receiver: plays a URL with its title, artist and cover on the TV. Built into every device. */
    const val DEFAULT_RECEIVER = "CC1AD845"

    /** What Google Cast devices play from a plain URL (the Default Media Receiver's formats Prism sends). */
    val SINK = setOf("audio/wav", "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/webm")
}

/**
 * A Google Cast device (Chromecast, Google TV, a speaker with Cast built in) spoken to directly over its local TLS
 * port: the Default Media Receiver is opened on it, and songs from the phone's [MediaServer] are loaded into it by URL.
 * Status comes back as MEDIA_STATUS messages, mapped to the same [TransportInfo] / [PositionInfo] UPnP renderers give.
 *
 * Cast devices use self-signed certificates (senders check a device's identity with Google's own scheme, which isn't
 * needed to play to it), so the TLS connection takes any certificate: it's only ever made to a device on this Wi-Fi.
 * [open] makes the connection (plain sockets in tests).
 */
class GoogleCastLink(
    private val renderer: Renderer,
    private val open: (host: String, port: Int) -> Socket = ::tlsSocket,
) : RendererLink {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val writeLock = Any()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private var reader: Job? = null
    private var heartbeat: Job? = null

    /** The Default Media Receiver once it's running for us: its transport (where media messages go) and session. */
    @Volatile private var transportId: String? = null
    @Volatile private var appSession: String? = null
    /** Another app took the device over (YouTube opened on the TV), or ours was closed from the TV. */
    @Volatile private var foreign = false

    @Volatile private var mediaSession: Int? = null
    @Volatile private var playerState = "IDLE"
    @Volatile private var idleReason: String? = null
    @Volatile private var currentMs: Long? = null
    @Volatile private var durationMs: Long? = null
    @Volatile private var contentId: String? = null
    @Volatile private var volumeLevel: Int? = null

    // ------------------------------------------------------------ The connection

    private suspend fun ensureConnected() {
        if (socket?.isClosed == false && reader?.isActive == true) return
        connectLock.withLock {
            if (socket?.isClosed == false && reader?.isActive == true) return
            dropConnection()
            val host = renderer.host ?: throw IOException(tr(R.string.has_no_address, renderer.name))
            val port = renderer.port.takeIf { it > 0 } ?: 8009
            val s = withContext(Dispatchers.IO) { open(host, port) }
            socket = s
            out = s.getOutputStream()
            transportId = null
            appSession = null
            mediaSession = null
            foreign = false
            send(CastNs.CONNECTION, CastNs.PLATFORM) { put("type", "CONNECT") }
            val input = DataInputStream(s.getInputStream().buffered())
            reader = io.launch { readLoop(s, input) }
            heartbeat = io.launch {
                while (isActive) {
                    delay(5_000)
                    runCatching { send(CastNs.HEARTBEAT, CastNs.PLATFORM) { put("type", "PING") } }
                }
            }
        }
    }

    private fun readLoop(s: Socket, input: DataInputStream) {
        try {
            while (!s.isClosed) {
                val m = CastWire.read(input) ?: continue
                val json = runCatching { Json.parseToJsonElement(m.payload).jsonObject }.getOrNull() ?: continue
                onMessage(m, json)
            }
        } catch (e: Exception) {
            if (socket === s) Log.i(TAG, "connection to ${renderer.name} ended: ${e.message}")
        } finally {
            if (socket === s) {
                runCatching { s.close() }
                pending.values.forEach { it.completeExceptionally(IOException(tr(R.string.lost_the_connection_to, renderer.name))) }
                pending.clear()
            }
        }
    }

    private fun onMessage(m: CastMessage, json: JsonObject) {
        val type = json.str("type")
        when (m.namespace) {
            CastNs.HEARTBEAT -> if (type == "PING") runCatching { send(CastNs.HEARTBEAT, m.source) { put("type", "PONG") } }
            CastNs.CONNECTION -> if (type == "CLOSE" && m.source == transportId) {
                // Our player was closed on the TV (its Back button, or another app opening).
                foreign = true
                transportId = null
                mediaSession = null
            }
            CastNs.RECEIVER -> if (type == "RECEIVER_STATUS") onReceiverStatus(json)
            CastNs.MEDIA -> if (type == "MEDIA_STATUS") onMediaStatus(json)
        }
        json.int("requestId")?.takeIf { it != 0 }?.let { id -> pending.remove(id)?.complete(json) }
    }

    private fun onReceiverStatus(json: JsonObject) {
        val status = json.obj("status") ?: return
        status.obj("volume")?.let { v ->
            val level = v["level"]?.jsonPrimitive?.doubleOrNull
            val muted = v.str("muted") == "true"
            if (level != null) volumeLevel = if (muted) 0 else (level * 100).roundToInt().coerceIn(0, 100)
        }
        val apps = status["applications"] as? JsonArray ?: JsonArray(emptyList())
        val ours = apps.map { it.jsonObject }.firstOrNull { it.str("appId") == CastNs.DEFAULT_RECEIVER && it.str("sessionId") == appSession }
        // Only once Prism has a player there: another app running instead means the TV moved on to something else.
        if (appSession != null && ours == null) {
            foreign = true
            transportId = null
            mediaSession = null
        }
    }

    private fun onMediaStatus(json: JsonObject) {
        val list = json["status"] as? JsonArray ?: return
        val st = list.firstOrNull()?.jsonObject
        if (st == null) {
            // The media session is over (it played to the end, or it was stopped): stopped, with the last song kept.
            if (playerState != "IDLE") idleReason = idleReason ?: "FINISHED"
            playerState = "IDLE"
            mediaSession = null
            return
        }
        st.int("mediaSessionId")?.let { mediaSession = it }
        st.str("playerState")?.let { playerState = it }
        idleReason = st.str("idleReason")
        st["currentTime"]?.jsonPrimitive?.doubleOrNull?.let { currentMs = (it * 1000).toLong() }
        st.obj("media")?.let { media ->
            media.str("contentId")?.let { contentId = it }
            media["duration"]?.jsonPrimitive?.doubleOrNull?.let { durationMs = (it * 1000).toLong() }
        }
    }

    private fun send(namespace: String, destination: String, build: JsonObjectBuilder.() -> Unit) {
        val o = out ?: throw IOException(tr(R.string.not_connected_to, renderer.name))
        val payload = buildJsonObject(build).toString()
        synchronized(writeLock) { CastWire.write(o, CastMessage(CastNs.SENDER, destination, namespace, payload)) }
    }

    /** Sends a request and waits for the answer with the same requestId. */
    private suspend fun request(namespace: String, destination: String, timeoutMs: Long = 8_000, build: JsonObjectBuilder.() -> Unit): JsonObject {
        val id = ids.getAndIncrement()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            withContext(Dispatchers.IO) { send(namespace, destination) { put("requestId", id); build() } }
            return withTimeoutOrNull(timeoutMs) { answer.await() } ?: throw IOException(tr(R.string.didn_t_answer, renderer.name))
        } finally {
            pending.remove(id)
        }
    }

    /** The Default Media Receiver running and connected to, launched if need be (the TV shows its cast screen). */
    private suspend fun ensurePlayer(): String {
        ensureConnected()
        transportId?.let { return it }
        var app: JsonObject? = null
        var status = request(CastNs.RECEIVER, CastNs.PLATFORM) { put("type", "GET_STATUS") }
        fun findApp(s: JsonObject) = (s.obj("status")?.get("applications") as? JsonArray)?.map { it.jsonObject }
            ?.firstOrNull { it.str("appId") == CastNs.DEFAULT_RECEIVER }
        app = findApp(status)
        if (app == null) {
            status = request(CastNs.RECEIVER, CastNs.PLATFORM, timeoutMs = 20_000) { put("type", "LAUNCH"); put("appId", CastNs.DEFAULT_RECEIVER) }
            if (status.str("type") == "LAUNCH_ERROR") throw IOException(tr(R.string.wouldn_t_open_its_player, renderer.name, status.str("reason") ?: tr(R.string.error_word)))
            app = findApp(status)
            // Some devices answer the launch before the app is up: asked again for a few seconds.
            var tries = 0
            while (app == null && tries++ < 20) {
                delay(500)
                app = findApp(request(CastNs.RECEIVER, CastNs.PLATFORM) { put("type", "GET_STATUS") })
            }
        }
        val a = app ?: throw IOException(tr(R.string.didn_t_open_its_player, renderer.name))
        val transport = a.str("transportId") ?: throw IOException(tr(R.string.s_player_has_no_transport, renderer.name))
        appSession = a.str("sessionId")
        foreign = false
        send(CastNs.CONNECTION, transport) { put("type", "CONNECT") }
        transportId = transport
        // Whatever it already had (a cast screen left from before) is looked at once.
        runCatching { request(CastNs.MEDIA, transport) { put("type", "GET_STATUS") } }
        return transport
    }

    /** Network work never on the caller's (often the main) thread. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun dropConnection() {
        reader?.cancel(); heartbeat?.cancel()
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    private suspend fun media(type: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        // Never takes the TV back from whatever's on it now: casting ends instead.
        if (foreign) throw UpnpException(tr(R.string.is_playing_something_else_now, renderer.name))
        val transport = ensurePlayer()
        val session = mediaSession ?: throw UpnpException(tr(R.string.has_no_song_loaded, renderer.name))
        val answer = request(CastNs.MEDIA, transport) { put("type", type); put("mediaSessionId", session); extra() }
        answer.str("type")?.takeIf { it != "MEDIA_STATUS" }?.let { throw UpnpException(tr(R.string.failed_2, renderer.name, type, it)) }
        return answer
    }

    // ------------------------------------------------------------ RendererLink

    override suspend fun load(url: String, didl: String, media: CastMedia): Unit = io {
        val transport = ensurePlayer()
        val answer = request(CastNs.MEDIA, transport, timeoutMs = 25_000) {
            put("type", "LOAD")
            appSession?.let { put("sessionId", it) }
            put("autoplay", false)
            put("currentTime", 0)
            putJsonObject("media") {
                put("contentId", url)
                put("contentUrl", url)
                put("streamType", "BUFFERED")
                put("contentType", media.mime.substringBefore(';'))
                if (media.durationMs > 0) put("duration", media.durationMs / 1000.0)
                putJsonObject("metadata") {
                    put("metadataType", 3) // MusicTrackMediaMetadata
                    put("title", media.title)
                    if (media.artist.isNotBlank()) put("artist", media.artist)
                    media.album?.let { put("albumName", it) }
                    media.artUrl?.let { art -> putJsonArray("images") { add(buildJsonObject { put("url", art) }) } }
                }
            }
        }
        when (answer.str("type")) {
            "MEDIA_STATUS" -> { contentId = url; currentMs = 0; durationMs = media.durationMs.takeIf { it > 0 } }
            // 714 is UPnP's "illegal MIME type": Cast tries the other kind of PCM the same way a renderer would.
            "LOAD_FAILED", "LOAD_CANCELLED" -> throw UpnpException(tr(R.string.couldn_t_load_the_song, renderer.name), 714)
            else -> throw UpnpException(tr(R.string.load_failed, renderer.name, answer.str("type"), answer.str("reason").orEmpty()).trim())
        }
    }

    /** Cast's queue could do this, but one song at a time is what Prism needs: [Cast] then sends each as it comes. */
    override suspend fun loadNext(url: String, didl: String, media: CastMedia) {
        throw UpnpException(tr(R.string.one_song_at_a_time, renderer.name), 401)
    }

    override suspend fun play(): Unit = io { media("PLAY") }
    override suspend fun pause(): Unit = io { media("PAUSE") }

    override suspend fun stop(): Unit = io {
        if (transportId == null || mediaSession == null) return@io
        runCatching { media("STOP") }
        Unit
    }

    override suspend fun seek(ms: Long): Unit = io {
        media("SEEK") { put("currentTime", ms.coerceAtLeast(0) / 1000.0) }
    }

    override suspend fun transport(): TransportInfo = io {
        if (foreign) return@io TransportInfo("STOPPED", "OK")
        val transport = ensurePlayer()
        if (mediaSession != null || contentId != null) runCatching { request(CastNs.MEDIA, transport) { put("type", "GET_STATUS") } }
        if (foreign) return@io TransportInfo("STOPPED", "OK")
        if (contentId == null) return@io TransportInfo("NO_MEDIA_PRESENT", "OK")
        when (playerState) {
            "PLAYING" -> TransportInfo("PLAYING", "OK")
            "PAUSED" -> TransportInfo("PAUSED_PLAYBACK", "OK")
            "BUFFERING", "LOADING" -> TransportInfo("TRANSITIONING", "OK")
            else -> TransportInfo("STOPPED", if (idleReason == "ERROR") "ERROR_OCCURRED" else "OK")
        }
    }

    override suspend fun position(): PositionInfo =
        // Taken over by something else: a track that isn't Prism's, so casting ends the way it does for a renderer.
        if (foreign) PositionInfo(OTHER, null, null) else PositionInfo(contentId, currentMs, durationMs)

    override suspend fun volume(): Int? = io {
        runCatching { ensureConnected(); request(CastNs.RECEIVER, CastNs.PLATFORM) { put("type", "GET_STATUS") } }
        volumeLevel
    }

    override suspend fun setVolume(v: Int): Unit = io {
        ensureConnected()
        volumeLevel = v.coerceIn(0, 100)
        request(CastNs.RECEIVER, CastNs.PLATFORM) {
            put("type", "SET_VOLUME")
            putJsonObject("volume") { put("level", v.coerceIn(0, 100) / 100.0) }
        }
    }

    override suspend fun sinkMimes(): Set<String> = CastNs.SINK

    override suspend fun close(): Unit = io {
        try {
            // Closes Prism's player on the TV (back to its home screen), unless something else has taken it over.
            val session = appSession
            if (!foreign && session != null && socket?.isClosed == false) {
                runCatching { withTimeoutOrNull(3_000) { request(CastNs.RECEIVER, CastNs.PLATFORM) { put("type", "STOP"); put("sessionId", session) } } }
            }
        } finally {
            dropConnection()
            io.cancel()
        }
    }

    companion object {
        private const val TAG = "PrismCast"
        /** The track a device reports once another app has it: not one of Prism's. */
        const val OTHER = "googlecast://other"

        /** TLS to a Cast device, which presents a self-signed certificate. */
        fun tlsSocket(host: String, port: Int): Socket {
            val trustAny = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAny), SecureRandom()) }
            val s = ctx.socketFactory.createSocket() as SSLSocket
            s.connect(InetSocketAddress(host, port), 5_000)
            // The device pings every 5 s: nothing for 20 s means it's gone.
            s.soTimeout = 20_000
            s.startHandshake()
            return s
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        private fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}

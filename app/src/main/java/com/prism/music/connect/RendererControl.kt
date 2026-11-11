package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** A renderer turned an action down ([code] is its UPnP error code, e.g. 714 "illegal MIME type"), or didn't answer. */
class UpnpException(message: String, val code: Int = -1) : IOException(message)

/** AVTransport's GetTransportInfo: PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT… and OK / ERROR_OCCURRED. */
data class TransportInfo(val state: String, val status: String) {
    val playing get() = state == "PLAYING"
    val paused get() = state == "PAUSED_PLAYBACK" || state == "PAUSED_RECORDING"
    val stopped get() = state == "STOPPED" || state == "NO_MEDIA_PRESENT"
    val transitioning get() = state == "TRANSITIONING"
    val failed get() = status.equals("ERROR_OCCURRED", true)
}

/** AVTransport's GetPositionInfo: the track it has, where it is in it, and how long it is (nulls when it doesn't say). */
data class PositionInfo(val trackUri: String?, val relMs: Long?, val durationMs: Long?)

/** Sends a renderer its UPnP actions (SOAP over HTTP) and reads their answers. */
class RendererControl(http: OkHttpClient, val renderer: Renderer) : RendererLink {
    private val client = with(LanHttp) { http.newBuilder().lanFirst() }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val av = renderer.avTransport ?: throw IllegalArgumentException(tr(R.string.isn_t_a_upnp_renderer, renderer.name))

    suspend fun call(service: UpnpService, action: String, args: List<Pair<String, String>>): Map<String, String> = withContext(Dispatchers.IO) {
        val body = Soap.envelope(service.type, action, args).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())
        val request = Request.Builder()
            .url(service.controlUrl)
            .post(body)
            .header("SOAPACTION", Soap.actionHeader(service.type, action))
            .header("User-Agent", "Android UPnP/1.1 Prism/1.7")
            .build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            when (val parsed = Soap.parse(text)) {
                is SoapResult.Fault -> throw UpnpException(tr(R.string.failed_3, renderer.name, action, parsed.code, parsed.description).trim(), parsed.code)
                is SoapResult.Ok -> if (r.isSuccessful) parsed.values else throw UpnpException(tr(R.string.failed_http, renderer.name, action, r.code), r.code)
                null -> if (r.isSuccessful) emptyMap() else throw UpnpException(tr(R.string.failed_http, renderer.name, action, r.code), r.code)
            }
        }
    }

    private val instance = "InstanceID" to "0"

    suspend fun setUri(url: String, metadata: String) =
        call(av, "SetAVTransportURI", listOf(instance, "CurrentURI" to url, "CurrentURIMetaData" to metadata))

    /** For gapless moves on: the song after this one. Not every renderer has it (error 401). */
    suspend fun setNextUri(url: String, metadata: String) =
        call(av, "SetNextAVTransportURI", listOf(instance, "NextURI" to url, "NextURIMetaData" to metadata))

    override suspend fun load(url: String, didl: String, media: CastMedia) { setUri(url, didl) }
    override suspend fun loadNext(url: String, didl: String, media: CastMedia) { setNextUri(url, didl) }

    override suspend fun play() { call(av, "Play", listOf(instance, "Speed" to "1")) }
    override suspend fun pause() { call(av, "Pause", listOf(instance)) }
    override suspend fun stop() { call(av, "Stop", listOf(instance)) }
    /** Whether seeks may carry milliseconds; turned off for a renderer that refuses them (it then gets the nearest second). */
    @Volatile var preciseSeeks = true
        private set

    override suspend fun seek(ms: Long) {
        if (preciseSeeks) {
            try {
                call(av, "Seek", listOf(instance, "Unit" to "REL_TIME", "Target" to UpnpTime.format(ms, fraction = true)))
                return
            } catch (e: UpnpException) {
                // 711 "illegal seek target" (or a plain HTTP error from a strict parser): whole seconds from now on.
                if (e.code == 701) throw e
                preciseSeeks = false
            }
        }
        call(av, "Seek", listOf(instance, "Unit" to "REL_TIME", "Target" to UpnpTime.format(ms + 500)))
    }

    override suspend fun transport(): TransportInfo = call(av, "GetTransportInfo", listOf(instance)).let {
        TransportInfo(it["CurrentTransportState"].orEmpty().trim(), it["CurrentTransportStatus"].orEmpty().trim())
    }

    override suspend fun position(): PositionInfo = call(av, "GetPositionInfo", listOf(instance)).let {
        PositionInfo(it["TrackURI"]?.trim()?.takeIf { u -> u.isNotEmpty() && u != "NOT_IMPLEMENTED" }, UpnpTime.parse(it["RelTime"]), UpnpTime.parse(it["TrackDuration"]))
    }

    /** 0–100 (most renderers' range), or null without RenderingControl. */
    override suspend fun volume(): Int? {
        val rc = renderer.rendering ?: return null
        return call(rc, "GetVolume", listOf(instance, "Channel" to "Master"))["CurrentVolume"]?.trim()?.toIntOrNull()
    }

    override suspend fun setVolume(v: Int) {
        val rc = renderer.rendering ?: return
        call(rc, "SetVolume", listOf(instance, "Channel" to "Master", "DesiredVolume" to v.coerceIn(0, 100).toString()))
    }

    /** The MIME types it says it plays; empty when it won't say (then WAV, which nearly all of them take). */
    override suspend fun sinkMimes(): Set<String> {
        val cm = renderer.connection ?: return emptySet()
        return runCatching { ProtocolInfo.sinkMimes(call(cm, "GetProtocolInfo", emptyList())["Sink"]) }.getOrDefault(emptySet())
    }

    companion object {
        /**
         * This phone's address as [host] sees it (the interface the route to it goes out of), for the links it's sent.
         * Nothing is sent: a UDP socket only picks its route on connect.
         */
        fun localAddressFor(host: String): String? = runCatching {
            DatagramSocket().use { s ->
                s.connect(InetAddress.getByName(host), 9)
                val a = s.localAddress ?: return@use null
                if (a.isAnyLocalAddress) null else if (a is Inet6Address) "[${a.hostAddress?.substringBefore('%')}]" else a.hostAddress
            }
        }.getOrNull()
    }
}

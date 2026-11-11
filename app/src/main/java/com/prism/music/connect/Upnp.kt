package com.prism.music.connect

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** One of a renderer's UPnP services: its type and the URLs its actions go to. */
data class UpnpService(val type: String, val controlUrl: String, val eventUrl: String? = null, val scpdUrl: String? = null)

/**
 * How a device is spoken to: UPnP / DLNA, Google Cast (Chromecast, Google TV, speakers with Cast built in), or a TV
 * with Prism on it (1.8.0: it plays along with the phone over the handoff link, [TvCast]).
 */
enum class RendererKind { UPNP, GOOGLE_CAST, PRISM_TV }

/**
 * A speaker, TV or receiver on the local network that plays what it's sent. For UPnP / DLNA [id] is its UDN (stable
 * across restarts) and [location] the description it was found at; for Google Cast [id] is "cast:" and its device id,
 * and [location] "googlecast://host:port". Only UPnP renderers have the [avTransport] / [rendering] / [connection]
 * services.
 */
data class Renderer(
    val id: String,
    val name: String,
    val manufacturer: String,
    val model: String,
    val location: String,
    val avTransport: UpnpService?,
    val rendering: UpnpService? = null,
    val connection: UpnpService? = null,
    val kind: RendererKind = RendererKind.UPNP,
) {
    /** What a row shows under the name: the maker and model, when they say something the name doesn't. */
    val detail: String
        get() {
            val about = listOf(manufacturer, model).filter { it.isNotBlank() && !name.contains(it, ignoreCase = true) }.joinToString(" ")
            return when (kind) {
                RendererKind.GOOGLE_CAST -> listOf(about, "Google Cast").filter { it.isNotBlank() }.joinToString(" · ")
                RendererKind.PRISM_TV -> "Prism TV"
                RendererKind.UPNP -> about.ifBlank { "Speaker or TV" }
            }
        }

    val host: String? get() = runCatching { URI(location).host }.getOrNull()

    /** The port a Google Cast device listens on (8009 for most; speaker groups have their own). */
    val port: Int get() = runCatching { URI(location).port }.getOrDefault(-1)
}

/** XML as UPnP devices write it, read with the platform's DOM parser (the same on the phone and in tests). */
internal object Xml {
    /** Null for anything that isn't well-formed, and for documents with a DOCTYPE or entities (nothing UPnP needs them). */
    fun parse(text: String): Document? {
        val body = text.trim().removePrefix("\uFEFF")
        if (body.contains("<!DOCTYPE", ignoreCase = true) || body.contains("<!ENTITY", ignoreCase = true)) return null
        return runCatching {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.isExpandEntityReferences = false }
            f.newDocumentBuilder().parse(InputSource(StringReader(body)))
        }.getOrNull()
    }

    fun Node.name(): String = (localName ?: nodeName).substringAfter(':')

    fun Element.kids(name: String): List<Element> {
        val out = ArrayList<Element>()
        val list = childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n is Element && n.name().equals(name, ignoreCase = true)) out += n
        }
        return out
    }

    fun Element.child(name: String): Element? = kids(name).firstOrNull()

    fun Element.text(name: String): String? = child(name)?.textContent?.trim()

    /** Every element under this one called [name], depth first, in document order. */
    fun Element.all(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            val list = e.childNodes
            for (i in 0 until list.length) {
                val n = list.item(i) as? Element ?: continue
                if (n.name().equals(name, ignoreCase = true)) out += n
                walk(n)
            }
        }
        walk(this)
        return out
    }

    fun escape(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (ch in s) when (ch) {
            '&' -> b.append("&amp;")
            '<' -> b.append("&lt;")
            '>' -> b.append("&gt;")
            '"' -> b.append("&quot;")
            '\'' -> b.append("&apos;")
            // Control characters aren't allowed in XML 1.0 at all (titles sometimes carry them).
            else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') Unit else b.append(ch)
        }
        return b.toString()
    }
}

/** A message seen on SSDP (UPnP discovery): a search answer or a NOTIFY. */
data class SsdpMessage(val notify: Boolean, val headers: Map<String, String>) {
    val location: String? get() = headers["location"]
    val usn: String? get() = headers["usn"]
    /** What the message is about: ST in a search answer, NT in a NOTIFY. */
    val target: String? get() = headers["st"] ?: headers["nt"]
    /** A NOTIFY saying the device is leaving. */
    val byebye: Boolean get() = notify && headers["nts"]?.contains("byebye", ignoreCase = true) == true

    companion object {
        fun parse(text: String): SsdpMessage? {
            val lines = text.split("\r\n", "\n")
            val first = lines.firstOrNull()?.trim() ?: return null
            val notify = when {
                first.startsWith("HTTP/1.", ignoreCase = true) -> {
                    if (first.split(' ').getOrNull(1) != "200") return null
                    false
                }
                first.startsWith("NOTIFY", ignoreCase = true) -> true
                else -> return null // M-SEARCH from someone else, or noise
            }
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            return SsdpMessage(notify, headers)
        }
    }
}

object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    /** Sonos speakers answer as a ZonePlayer, with the MediaRenderer inside it (GitHub #239, Reddit: Sonos never showed). */
    const val ZONE_PLAYER = "urn:schemas-upnp-org:device:ZonePlayer:1"

    fun search(target: String, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mx\r\n" +
            "ST: $target\r\n" +
            "USER-AGENT: Android UPnP/1.1 Prism/1.7\r\n\r\n"

    /** Whether a search answer or NOTIFY is about something that can play music sent to it. */
    fun isRenderer(m: SsdpMessage): Boolean {
        val t = m.target ?: return false
        return t.contains(":MediaRenderer:", ignoreCase = true) || t.contains(":AVTransport:", ignoreCase = true) || t.contains(":ZonePlayer:", ignoreCase = true)
    }

    /**
     * Whether a message's description is on the address it came from. Anyone on the Wi-Fi can answer a search, so it isn't
     * taken on behalf of another address, least of all the phone's own. Addresses only: a name isn't looked up.
     */
    fun speaksForItself(m: SsdpMessage, sender: java.net.InetAddress): Boolean = runCatching {
        val host = URI(m.location ?: return false).host?.removePrefix("[")?.removeSuffix("]") ?: return false
        val literal = host.contains(':') || host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
        literal && java.net.InetAddress.getByName(host).address.contentEquals(sender.address)
    }.getOrDefault(false)
}

object Description {
    /**
     * The renderer a device description describes: the (possibly embedded) device that has an AVTransport service, a
     * MediaRenderer preferred. Relative URLs are resolved against URLBase, else [location]. Null when it can't play.
     * Everything stays on [location]'s host (any port): its actions carry the phone's stream address, and a description
     * doesn't get to send them, or a look at anything else on the network, to some other address.
     */
    fun parse(xml: String, location: String): Renderer? {
        val doc = Xml.parse(xml) ?: return null
        val root = doc.documentElement ?: return null
        with(Xml) {
            val base = root.text("URLBase")?.takeIf { it.startsWith("http", ignoreCase = true) && sameHost(it, location) } ?: location
            fun onDevice(ref: String) = resolve(base, ref)?.takeIf { sameHost(it, location) }
            val devices = (if (root.name().equals("device", true)) listOf(root) else emptyList()) + root.all("device")
            val playable = devices.mapNotNull { d ->
                val services = d.child("serviceList")?.kids("service").orEmpty()
                if (services.none { it.text("serviceType")?.contains(":AVTransport:", ignoreCase = true) == true }) return@mapNotNull null
                d to services
            }
            val (device, services) = playable.firstOrNull { it.first.text("deviceType")?.contains(":MediaRenderer:", true) == true }
                ?: playable.firstOrNull() ?: return null
            fun service(kind: String): UpnpService? = services.firstOrNull { it.text("serviceType")?.contains(":$kind:", true) == true }?.let { s ->
                val control = s.text("controlURL")?.takeIf { it.isNotBlank() } ?: return@let null
                UpnpService(
                    type = s.text("serviceType")!!,
                    controlUrl = onDevice(control) ?: return@let null,
                    eventUrl = s.text("eventSubURL")?.takeIf { it.isNotBlank() }?.let { onDevice(it) },
                    scpdUrl = s.text("SCPDURL")?.takeIf { it.isNotBlank() }?.let { onDevice(it) },
                )
            }
            val av = service("AVTransport") ?: return null
            val model = device.text("modelName").orEmpty()
            // A Sonos speaker's room ("Living Room") is its name; the renderer inside it is called "… Media Renderer".
            val room = devices.firstNotNullOfOrNull { it.text("roomName")?.takeIf { r -> r.isNotBlank() } }
            return Renderer(
                id = device.text("UDN")?.takeIf { it.isNotBlank() } ?: location,
                name = room ?: device.text("friendlyName")?.takeIf { it.isNotBlank() } ?: model.ifBlank { "Speaker" },
                manufacturer = device.text("manufacturer").orEmpty(),
                model = model,
                location = location,
                avTransport = av,
                rendering = service("RenderingControl"),
                connection = service("ConnectionManager"),
            )
        }
    }

    /** [ref] against [base]; a base with no path gets "/" first (java.net.URI would glue "x" straight onto the port). */
    fun resolve(base: String, ref: String): String? = runCatching {
        val r = ref.trim()
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return@runCatching r
        var b = URI(base.trim())
        if (b.rawPath.isNullOrEmpty()) b = URI(b.scheme, b.rawAuthority, "/", null, null)
        b.resolve(r).toString()
    }.getOrNull()

    /** Plain http on the same host. Read by [URI]'s rules, the ones the address will be used by ("http://a@b/" is on b). */
    private fun sameHost(url: String, other: String): Boolean = runCatching {
        val u = URI(url.trim())
        val o = URI(other.trim())
        u.scheme.equals("http", ignoreCase = true) && u.host != null && u.host.equals(o.host, ignoreCase = true)
    }.getOrDefault(false)
}

/** What a SOAP action came back with: its out-arguments, or the device's UPnP error. */
sealed interface SoapResult {
    data class Ok(val values: Map<String, String>) : SoapResult
    data class Fault(val code: Int, val description: String) : SoapResult
}

object Soap {
    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(Xml.escape(serviceType)).append("\">")
        for ((k, v) in args) append('<').append(k).append('>').append(Xml.escape(v)).append("</").append(k).append('>')
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /** The SOAPACTION header's value. */
    fun actionHeader(serviceType: String, action: String) = "\"$serviceType#$action\""

    /** A response body: the `<actionResponse>`'s children, or the fault's UPnPError (code -1 when it doesn't say). */
    fun parse(xml: String): SoapResult? {
        val doc = Xml.parse(xml) ?: return null
        with(Xml) {
            val root = doc.documentElement ?: return null
            root.all("Fault").firstOrNull()?.let { fault ->
                val err = fault.all("UPnPError").firstOrNull()
                return SoapResult.Fault(
                    err?.text("errorCode")?.toIntOrNull() ?: -1,
                    err?.text("errorDescription") ?: fault.all("faultstring").firstOrNull()?.textContent?.trim().orEmpty(),
                )
            }
            val body = root.all("Body").firstOrNull() ?: return null
            val response = body.childNodes.let { l -> (0 until l.length).map { l.item(it) }.firstOrNull { it is Element } as? Element }
                ?: return SoapResult.Ok(emptyMap())
            val values = LinkedHashMap<String, String>()
            val l = response.childNodes
            for (i in 0 until l.length) {
                val n = l.item(i) as? Element ?: continue
                values[n.name()] = n.textContent ?: ""
            }
            return SoapResult.Ok(values)
        }
    }
}

/** UPnP's "H:MM:SS(.mmm)" times. */
object UpnpTime {
    /** [fraction]: with milliseconds ("0:01:02.500"), which seeks need; whole seconds otherwise (rounded down). */
    fun format(ms: Long, fraction: Boolean = false): String {
        val t = ms.coerceAtLeast(0)
        val h = t / 3_600_000
        val m = t / 60_000 % 60
        val s = t / 1000 % 60
        return if (fraction) "%d:%02d:%02d.%03d".format(java.util.Locale.ROOT, h, m, s, t % 1000)
        else "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s)
    }

    /** Milliseconds, or null for "NOT_IMPLEMENTED", blanks and anything else that isn't a time. */
    fun parse(text: String?): Long? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = t.substringBefore('/').split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secText = parts[2]
        val whole = secText.substringBefore('.').toLongOrNull() ?: return null
        val frac = secText.substringAfter('.', "").takeIf { it.isNotEmpty() }?.let { f ->
            f.take(3).padEnd(3, '0').toLongOrNull() ?: return null
        } ?: 0L
        if (h < 0 || m !in 0..59 || whole !in 0..59) return null
        return ((h * 60 + m) * 60 + whole) * 1000 + frac
    }
}

/** How a song goes to a renderer: as the file itself, or decoded on the phone into plain PCM (which every renderer plays). */
enum class CastFormat(val ext: String) { ORIGINAL("bin"), WAV("wav"), L16("pcm") }

object ProtocolInfo {
    /** The MIME types a renderer's ConnectionManager says it plays (GetProtocolInfo's Sink), lower case, without parameters. */
    fun sinkMimes(sink: String?): Set<String> =
        sink.orEmpty().split(',').mapNotNull { entry ->
            val f = entry.trim().split(':')
            if (f.size < 3) return@mapNotNull null
            if (!f[0].equals("http-get", true) && f[0] != "*") return@mapNotNull null
            f[2].substringBefore(';').trim().lowercase().takeIf { it.isNotEmpty() }
        }.toSet()

    private val families = listOf(
        setOf("audio/mpeg", "audio/mp3", "audio/x-mpeg", "audio/mpeg3"),
        setOf("audio/flac", "audio/x-flac"),
        setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave"),
        setOf("audio/ogg", "application/ogg", "audio/x-ogg"),
    )

    /**
     * How to send a file of [mime]: as it is when the renderer lists its type (MP3, FLAC, WAV, Ogg; YouTube's own
     * streams are DASH MP4 / WebM, which TVs often refuse even when they list the type, so those are always decoded);
     * else as WAV, or L16 when the renderer only lists that. Returns the format and the MIME type to announce.
     */
    fun choose(sink: Set<String>, mime: String?, decodedFallback: Boolean = false): Pair<CastFormat, String> {
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        if (!decodedFallback && m != null) {
            if ("*" in sink) families.firstOrNull { m in it }?.let { return CastFormat.ORIGINAL to m }
            families.firstOrNull { m in it }?.let { fam -> sink.firstOrNull { it in fam }?.let { return CastFormat.ORIGINAL to it } }
        }
        val wav = sink.firstOrNull { it in families[2] }
        return when {
            wav != null -> CastFormat.WAV to wav
            "audio/l16" in sink -> CastFormat.L16 to "audio/L16"
            else -> CastFormat.WAV to "audio/wav"
        }
    }

    /** The protocolInfo a `<res>` carries. [converted]: decoded on the phone (DLNA's CI flag). */
    fun of(mime: String, converted: Boolean): String {
        val pn = if (mime.startsWith("audio/L16", true)) "DLNA.ORG_PN=LPCM;" else if (mime.contains("mpeg")) "DLNA.ORG_PN=MP3;" else ""
        return "http-get:*:$mime:${pn}DLNA.ORG_OP=01;DLNA.ORG_CI=${if (converted) 1 else 0};DLNA.ORG_FLAGS=$DLNA_FLAGS"
    }

    /** Streaming transfer, background transfer, connection stall allowed, DLNA 1.5. */
    const val DLNA_FLAGS = "01700000000000000000000000000000"
}

object Didl {
    /** DIDL-Lite for one song, as SetAVTransportURI's CurrentURIMetaData (escaped once more by the SOAP envelope). */
    fun song(
        id: String, title: String, artist: String, album: String?, artUrl: String?,
        url: String, protocolInfo: String, durationMs: Long, sizeBytes: Long?,
    ): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
        append("<item id=\"").append(Xml.escape(id)).append("\" parentID=\"0\" restricted=\"1\">")
        append("<dc:title>").append(Xml.escape(title)).append("</dc:title>")
        if (artist.isNotBlank()) {
            append("<dc:creator>").append(Xml.escape(artist)).append("</dc:creator>")
            append("<upnp:artist>").append(Xml.escape(artist)).append("</upnp:artist>")
        }
        if (!album.isNullOrBlank()) append("<upnp:album>").append(Xml.escape(album)).append("</upnp:album>")
        if (!artUrl.isNullOrBlank()) append("<upnp:albumArtURI>").append(Xml.escape(artUrl)).append("</upnp:albumArtURI>")
        append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        append("<res protocolInfo=\"").append(Xml.escape(protocolInfo)).append('"')
        if (durationMs > 0) append(" duration=\"").append(UpnpTime.format(durationMs)).append(".000\"")
        if (sizeBytes != null && sizeBytes > 0) append(" size=\"").append(sizeBytes).append('"')
        append('>').append(Xml.escape(url)).append("</res>")
        append("</item></DIDL-Lite>")
    }
}

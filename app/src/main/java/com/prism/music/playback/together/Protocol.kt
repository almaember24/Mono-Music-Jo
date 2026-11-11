package com.prism.music.playback.together

import com.prism.music.data.model.Song
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.InputStream
import java.net.Socket

/**
 * What phones in a Listen Together session say to each other: one JSON message per line over a
 * plain TCP connection on the local network. There's no server; the host's phone is the session.
 */
@Serializable
sealed class Msg {
    /** Guest → host, first thing: who's joining and the session's code. */
    @Serializable @SerialName("hello")
    data class Hello(val name: String, val code: String, val version: Int = VERSION) : Msg()

    /** Host → guest: you're in. */
    @Serializable @SerialName("welcome")
    data class Welcome(val hostName: String) : Msg()

    /** Host → guest: not let in (wrong code, or a newer Prism is needed). */
    @Serializable @SerialName("denied")
    data class Denied(val reason: String) : Msg()

    /**
     * Host → guests: what's playing. [songs] starts with the current song, then what plays next,
     * in order. [positionMs] was the playhead at [sentAt] (the host's clock).
     */
    @Serializable @SerialName("state")
    data class State(
        val songs: List<Song>,
        val positionMs: Long,
        val playing: Boolean,
        val buffering: Boolean = false,
        val sentAt: Long,
        val title: String = "",
        val guests: List<String> = emptyList(),
        val canControl: Boolean = false,
    ) : Msg()

    /** Guest → host: what time is it for you? ([t0] is the guest's clock.) */
    @Serializable @SerialName("ping")
    data class Ping(val t0: Long) : Msg()

    /** Host → guest: the answer, in the host's clock. */
    @Serializable @SerialName("pong")
    data class Pong(val t0: Long, val hostTime: Long) : Msg()

    /** Guest → host: put this song in the queue ([next] = right after the current one). */
    @Serializable @SerialName("add")
    data class Add(val song: Song, val next: Boolean) : Msg()

    /** Host → everyone: someone added a song. */
    @Serializable @SerialName("added")
    data class Added(val song: Song, val by: String) : Msg()

    /** Guest → host, when the host lets guests control playback: "play", "pause", "next", "previous" or "seek". */
    @Serializable @SerialName("control")
    data class Control(val action: String, val positionMs: Long = 0) : Msg()

    /** Either way: leaving (or, from the host, the session's over). */
    @Serializable @SerialName("bye")
    data class Bye(val reason: String = "") : Msg()

    companion object {
        const val VERSION = 1
    }
}

private val videoId = Regex("[A-Za-z0-9_-]{11}")
private val imageHosts = listOf(".googleusercontent.com", ".ggpht.com", ".ytimg.com")

/**
 * A song as another phone described it, made safe to play and show: a real YouTube id (or null),
 * pictures only from YouTube's own image servers, and text of a sensible length.
 */
fun Song.fromPeer(): Song? {
    if (!videoId.matches(id)) return null
    val thumb = thumbnail?.takeIf { t ->
        val u = runCatching { java.net.URI(t) }.getOrNull()
        u?.scheme == "https" && u.host?.lowercase()?.let { h -> imageHosts.any { h.endsWith(it) } } == true
    }
    return copy(
        title = title.take(300),
        artists = artists.take(10).map { it.copy(name = it.name.take(200)) },
        album = album?.let { it.copy(title = it.title.take(300)) },
        thumbnail = thumb,
    )
}

object Wire {
    val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(m: Msg): String = json.encodeToString(Msg.serializer(), m)

    /** Null for anything that isn't a message this version understands. */
    fun decode(line: String): Msg? = runCatching { json.decodeFromString(Msg.serializer(), line) }.getOrNull()

    /** The longest line read; anything longer is dropped (no one needs a megabyte of queue). */
    const val MAX_LINE = 512 * 1024
}

/**
 * Reads lines of at most [max] bytes (UTF-8). Returns null at the end of the stream. An over-long line is skipped
 * whole, or ends the reading ([endOnTooLong]: null, as at the end).
 */
internal class LineReader(private val input: InputStream, @Volatile var max: Int = Wire.MAX_LINE) {
    private val buf = java.io.ByteArrayOutputStream()
    @Volatile var endOnTooLong = false

    fun readLine(): String? {
        buf.reset()
        var tooLong = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() > 0 && !tooLong) buf.toString(Charsets.UTF_8.name()) else null
            if (b == '\n'.code) {
                if (tooLong) { tooLong = false; buf.reset(); continue }
                return buf.toString(Charsets.UTF_8.name())
            }
            if (!tooLong) {
                buf.write(b)
                if (buf.size() > max) {
                    if (endOnTooLong) return null
                    tooLong = true; buf.reset()
                }
            }
        }
    }
}

/** One connection between two phones: messages out with [send], in with [read]. */
class Link(private val socket: Socket) : Closeable {
    private val reader = LineReader(socket.getInputStream().buffered())
    private val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)

    val remote: String get() = socket.inetAddress?.hostAddress ?: "?"

    /** False if the connection has gone. Safe to call from any thread. */
    fun send(m: Msg): Boolean = synchronized(writer) {
        runCatching { writer.write(Wire.encode(m)); writer.write("\n"); writer.flush() }.isSuccess
    }

    /** The next message, skipping lines it doesn't understand; null once the connection closes. Blocks. */
    fun read(): Msg? {
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: return null
            if (line.isBlank()) continue
            Wire.decode(line)?.let { return it }
        }
    }

    override fun close() { runCatching { socket.close() } }
}

/**
 * The guest's estimate of the host's clock, NTP-style: each ping's round trip halves into the
 * offset; the sample with the shortest trip is trusted most (it had the least room for delay).
 */
class ClockSync {
    private var bestRtt = Long.MAX_VALUE
    private var bestAt = 0L
    /** Host clock minus guest clock, in ms. */
    @Volatile var offset = 0L
        private set
    @Volatile var samples = 0
        private set

    /** [t1] is the guest's clock when the pong arrived. */
    fun onPong(p: Msg.Pong, t1: Long) {
        val rtt = (t1 - p.t0).coerceAtLeast(0)
        // Old samples age out, so a changing network (or a drifting clock) is followed.
        val stale = t1 - bestAt > 60_000
        if (rtt <= bestRtt || stale) {
            bestRtt = rtt
            bestAt = t1
            offset = p.hostTime - (p.t0 + rtt / 2)
        }
        samples++
    }

    /** Where the host's playhead is now, by the guest's clock [now]. */
    fun hostPosition(s: Msg.State, now: Long): Long =
        if (s.playing && !s.buffering) s.positionMs + (now + offset - s.sentAt).coerceAtLeast(0) else s.positionMs
}

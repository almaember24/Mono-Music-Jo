package com.prism.music.data.blend

import com.prism.music.R
import com.prism.music.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Makes a Blend on both phones at once, with no server: while a phone shows its Blend QR code it listens on the local
 * network, and the code carries a line with its addresses, a port and a one-time key ([HINT]). The phone that scans it
 * makes its Blend, then sends its own code straight back over Wi-Fi with that key, so the first phone makes the very
 * same Blend. Only a phone that read the key off the screen gets in, and only from the local network. When the phones
 * can't reach each other (different networks, a code sent as a message), the old way still works: send yours back.
 */
class BlendExchange(
    /** Makes (or updates) the Blend from a friend's code, with this phone's own as it was shown ([Blends.import]). */
    private val receive: suspend (code: String, mine: String?) -> Blends.Import,
    /** The name this phone shows in Blends. */
    private val myName: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var accepting: Job? = null
    private var key: String? = null
    /** The code on screen: what the other phone's Blend was made from, so this phone's is made from the same. */
    private var shown: String? = null

    private val _made = MutableSharedFlow<Blends.Import.Added>(extraBufferCapacity = 4)
    /** A friend's phone sent its code back and the Blend was made here too. */
    val made: SharedFlow<Blends.Import.Added> = _made

    /**
     * Starts listening for the phone that scans [myCode] (this phone's code, as the QR shows it). The text to put in the
     * QR code with it: the code, and the line saying where to send one back (or just the code without a local address).
     */
    @Synchronized
    fun open(myCode: String): String {
        shown = myCode
        val addresses = localAddresses()
        if (addresses.isEmpty()) return myCode
        val s = server ?: runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }
            .recoverCatching { ServerSocket(0) }.getOrNull()?.also { server = it; accepting = scope.launch { acceptLoop(it) } }
            ?: return myCode
        val k = key ?: ByteArray(12).also { SecureRandom().nextBytes(it) }
            .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }.also { key = it }
        // Debug builds say where, so a test on a computer can play the scanning phone (BlendExchangeTest.sendToAPhone).
        if (com.prism.music.BuildConfig.DEBUG) runCatching { android.util.Log.d("PrismBlend", "listening on ${s.localPort} key $k") }
        return myCode + "\n" + HINT + addresses.joinToString(",") + ":" + s.localPort + ":" + k
    }

    /** For tests: where it listens and the key a sender needs. */
    internal val listeningPort: Int? get() = server?.localPort
    internal val currentKey: String? get() = key

    /** Stops listening (the Blend screen closed). */
    @Synchronized
    fun close() {
        runCatching { server?.close() }
        server = null
        accepting?.cancel()
        accepting = null
        key = null
    }

    private suspend fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = runCatching { s.accept() }.getOrNull() ?: break
            scope.launch { socket.use { handle(it) } }
        }
    }

    private suspend fun handle(socket: Socket) {
        // Only a phone on the local network (a private address) is answered at all.
        val from = socket.inetAddress
        if (!(from.isSiteLocalAddress || from.isLinkLocalAddress || from.isLoopbackAddress)) return
        socket.soTimeout = 8_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val line = runCatching { readLimited(reader) }.getOrNull() ?: return
        val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
        val k = key
        val sentKey = line.substringBefore('\t')
        val code = line.substringAfter('\t', "")
        if (k == null || sentKey != k || BlendCode.findIn(code) == null) {
            writer.write("NO\n"); writer.flush(); return
        }
        when (val r = receive(code, shown)) {
            is Blends.Import.Added -> {
                writer.write("OK\t" + myName().ifBlank { tr(R.string.a_prism_listener) }.replace('\t', ' ').replace('\n', ' ') + "\n"); writer.flush()
                _made.tryEmit(r)
            }
            else -> { writer.write("NO\n"); writer.flush() }
        }
    }

    /** One line of at most 4 KB (a code is under 2). */
    private fun readLimited(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (sb.length < 4096) {
            val ch = reader.read()
            if (ch < 0) break
            if (ch == '\n'.code) return sb.toString()
            sb.append(ch.toChar())
        }
        return if (sb.isNotEmpty() && sb.length < 4096) sb.toString() else null
    }

    sealed interface SendBack {
        /** Their phone made the Blend too. */
        data class Done(val name: String) : SendBack
        /** The QR code had no way back (a code sent as a message, or from an older Prism). */
        data object NoWay : SendBack
        /** It had one, but their phone didn't answer (another network, or their Blend screen closed). */
        data object Unreachable : SendBack
    }

    /** After a Blend was made from [scanned] (a QR code's text), sends [myCode] back to the phone it came from. */
    suspend fun sendBack(scanned: String, myCode: String): SendBack = withContext(Dispatchers.IO) {
        val hint = parseHint(scanned) ?: return@withContext SendBack.NoWay
        for (host in hint.hosts) {
            val answer = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, hint.port), 2_500)
                    s.soTimeout = 15_000
                    val w = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
                    w.write(hint.key + "\t" + myCode + "\n"); w.flush()
                    BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).readLine()
                }
            }.getOrNull() ?: continue
            if (answer.startsWith("OK")) return@withContext SendBack.Done(answer.substringAfter('\t', "").ifBlank { tr(R.string.your_friend) })
        }
        SendBack.Unreachable
    }

    data class Hint(val hosts: List<String>, val port: Int, val key: String)

    companion object {
        const val HINT = "PRISMBLENDX:"
        /** Tried first, so a firewall rule or a capture is easy to set up; any free port otherwise. */
        const val PORT = 47324

        /** The way back in a scanned QR code's text, or null. */
        fun parseHint(text: String): Hint? {
            val line = text.lineSequence().firstOrNull { it.startsWith(HINT) }?.removePrefix(HINT)?.trim() ?: return null
            val parts = line.split(':')
            if (parts.size != 3) return null
            val hosts = parts[0].split(',').map { it.trim() }.filter { it.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) }.take(4)
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val key = parts[2].takeIf { it.length in 8..64 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } } ?: return null
            return if (hosts.isEmpty()) null else Hint(hosts, port, key)
        }

        /** This phone's private IPv4 addresses on its networks (Wi-Fi, a hotspot), at most three. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
                .distinct().take(3)
        }.getOrDefault(emptyList())
    }
}

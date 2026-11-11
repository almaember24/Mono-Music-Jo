package com.prism.music.connect

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Prism on a TV (1.8.0): while Settings → Speakers, TVs & browser → "Share Prism with a TV" is on, this phone hands its
 * own copy of Prism to a TV on the same Wi-Fi. A TV can't scan a QR code and a phone can't install an app on another
 * device, so the TV fetches it: the address goes into the Downloader app (or a TV browser), and the address itself is
 * the APK (no page to click through with a remote). Local network only ([Lan.isLocal]); stops when the page goes.
 */
class TvInstall(private val apk: () -> File?) {
    private val server = HttpServer("PrismTv", PORT, handler = ::handle)
    private val _sent = MutableStateFlow(0)
    /** Whole copies a TV finished fetching since sharing started. */
    val sent: StateFlow<Int> = _sent

    val running: Boolean get() = server.running
    val port: Int get() = server.port

    /** What to type on the TV: "192.168.1.40:47325" (Downloader adds the http:// itself). Null off Wi-Fi or stopped. */
    val address: String? get() = if (!server.running) null else Lan.ownAddress()?.let { "$it:${server.port}" }

    /** False when there's nothing to hand over (a copy installed in parts) or nowhere to listen. */
    fun start(): Boolean {
        if (apk() == null) return false
        _sent.value = 0
        return server.start()
    }

    fun stop() = server.stop()

    private fun handle(req: HttpRequest, res: HttpResponse) {
        if (req.method != "GET" && req.method != "HEAD") { res.text(405, "GET only", mapOf("Allow" to "GET, HEAD")); return }
        // A page elsewhere pointing its own name at this phone (DNS rebinding) is refused, as on the other servers.
        if (!Lan.hostIsLocalName(req.header("host"))) { res.text(403, "Forbidden"); return }
        if (req.path != "/" && !req.path.equals("/Prism.apk", ignoreCase = true)) { res.text(404, "Not found"); return }
        val file = apk() ?: run { res.text(404, "Not found"); return }
        val total = file.length()
        val headers = linkedMapOf(
            "Content-Type" to MIME,
            "Content-Disposition" to "attachment; filename=\"Prism.apk\"",
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
        )
        when (val range = ByteRange.parse(req.header("range"), total)) {
            ByteRange.Unsatisfiable -> res.send(416, null, ByteArray(0), headers + ("Content-Range" to "bytes */$total"))
            ByteRange.Whole -> {
                val out = res.start(200, headers, total)
                if (req.method == "GET") { copy(file, 0, total, out); _sent.value++ }
                out.flush()
            }
            is ByteRange.Part -> {
                val out = res.start(206, headers + ("Content-Range" to "bytes ${range.start}-${range.end}/$total"), range.length)
                if (req.method == "GET") { copy(file, range.start, range.length, out); if (range.end == total - 1) _sent.value++ }
                out.flush()
            }
        }
    }

    private fun copy(file: File, from: Long, length: Long, out: java.io.OutputStream) {
        RandomAccessFile(file, "r").use { f ->
            f.seek(from)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    companion object {
        const val PORT = 47325
        const val MIME = "application/vnd.android.package-archive"

        /**
         * The AFTVnews Downloader code for `releases/latest/download/Prism.apk` (go.aftvnews.com; codes are permanent and
         * the link always names the newest release). Also aftv.news/8986606 in a browser.
         */
        const val DOWNLOADER_CODE = "8986606"

        /** The installed copy when it's one file (sideloaded Prism always is); null for a copy installed in parts. */
        fun installedApk(info: android.content.pm.ApplicationInfo): File? =
            if (!info.splitSourceDirs.isNullOrEmpty()) null else File(info.sourceDir).takeIf { it.isFile }
    }
}

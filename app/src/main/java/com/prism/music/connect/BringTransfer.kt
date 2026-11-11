package com.prism.music.connect

import com.prism.music.R
import com.prism.music.tr
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * What a phone brings to a TV ("Bring your Prism", 1.8.0): [size] bytes (a bundle in the backup's format,
 * [com.prism.music.data.Backup.bringBundle]) whose SHA-256 is [sha256], read from [open].
 */
class BringSource(val size: Long, val sha256: String, val open: () -> InputStream) {
    companion object {
        fun of(file: File): BringSource = BringSource(file.length(), BringTransfer.sha256(file)) { file.inputStream() }
    }
}

/**
 * "Bring your Prism" over a link ([HandoffPeer]), after both sides proved the pairing, every line sealed: Begin (size,
 * SHA-256, how many chunks), numbered chunks of at most [CHUNK] bytes each answered by the TV, then End. The TV writes
 * the chunks to a file of its own, checks the size and the hash, and only then merges it in ([HandoffLocal.bringArrived]);
 * anything else (a line that doesn't open, a chunk out of turn, too much, a dropped connection) ends it with nothing
 * changed.
 */
object BringTransfer {
    /** One chunk's bytes: ≈ 350 KB in Base64, ≈ 470 KB as a sealed line (well under [HLink.SEALED_MAX_LINE]). */
    const val CHUNK = 256 * 1024
    /** The most a TV takes (a listener with years of history and many playlists is a few MB). */
    const val MAX_BYTES = 64L * 1024 * 1024
    /** The whole transfer, slow Wi-Fi and the merge included. */
    const val TRANSFER_MS = 15 * 60_000L

    fun chunksFor(size: Long): Int = ((size + CHUNK - 1) / CHUNK).toInt()

    fun sha256(file: File): String = file.inputStream().use { input ->
        val d = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        LinkKeys.hex(d.digest())
    }

    fun sha256(bytes: ByteArray): String = LinkKeys.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** The phone's side, once the link is sealed: what came of it. [progress]: bytes the TV has, of [BringSource.size]. */
    fun send(link: HLink, source: BringSource, them: String, progress: (Long, Long) -> Unit): HandoffResult {
        val failed = HandoffResult.Failed(tr(R.string.bring_failed))
        val chunks = chunksFor(source.size)
        link.deadline(TRANSFER_MS)
        if (!link.send(HMsg.BringBegin(source.size, source.sha256, chunks))) return failed
        when (val r = link.read()) {
            HMsg.Ok -> Unit
            is HMsg.Denied -> return HandoffResult.Failed(r.reason)
            null -> return failed
            else -> return HandoffResult.Failed(tr(R.string.didn_t_answer, them))
        }
        progress(0, source.size)
        var sent = 0L
        source.open().use { input ->
            val buf = ByteArray(CHUNK)
            for (n in 0 until chunks) {
                val want = minOf(CHUNK.toLong(), source.size - sent).toInt()
                var got = 0
                while (got < want) { val k = input.read(buf, got, want - got); if (k < 0) break; got += k }
                if (got != want) return failed
                if (!link.send(HMsg.BringChunk(n, Base64.getEncoder().encodeToString(if (got == CHUNK) buf else buf.copyOf(got))))) return failed
                when (val r = link.read()) {
                    is HMsg.BringGot -> if (r.n != n) return failed
                    is HMsg.Denied -> return HandoffResult.Failed(r.reason)
                    else -> return failed
                }
                sent += got
                progress(sent, source.size)
            }
        }
        if (!link.send(HMsg.BringEnd)) return failed
        // The TV checks it and merges it in before it answers.
        link.timeout(120_000)
        return when (val r = link.read()) {
            HMsg.Ok -> HandoffResult.Done()
            is HMsg.Denied -> HandoffResult.Failed(r.reason)
            else -> failed
        }
    }

    /**
     * The TV's side of [begin], from [them] ([fromTv]: the caller said it's a TV, which never brings its Prism). Nothing
     * reaches [local] but whole, checked bundles.
     */
    fun receive(link: HLink, begin: HMsg.BringBegin, local: HandoffLocal, them: String, fromTv: Boolean) {
        if (fromTv) { link.send(HMsg.Denied(tr(R.string.bring_from_a_phone))); return }
        if (begin.size > MAX_BYTES) { link.send(HMsg.Denied(tr(R.string.bring_too_big))); return }
        if (begin.size <= 0 || begin.chunks != chunksFor(begin.size) || LinkKeys.unhex(begin.sha256)?.size != 32) {
            link.send(HMsg.Denied(tr(R.string.both_devices_need_the_latest_prism))); return
        }
        local.bringStarting(them, begin.size)?.let { link.send(HMsg.Denied(it)); return }
        var ok = false
        val file = runCatching { local.bringScratch() }.getOrNull()
        try {
            if (file == null) { link.send(HMsg.Denied(tr(R.string.bring_failed))); return }
            val digest = MessageDigest.getInstance("SHA-256")
            var got = 0L
            link.deadline(TRANSFER_MS)
            link.send(HMsg.Ok)
            file.outputStream().buffered().use { out ->
                for (n in 0 until begin.chunks) {
                    // A dropped connection, a line that doesn't open, anything out of turn: the end of it, nothing kept.
                    val m = link.read() as? HMsg.BringChunk ?: return
                    if (m.n != n) return
                    val bytes = runCatching { Base64.getDecoder().decode(m.data) }.getOrNull() ?: return
                    if (bytes.size > CHUNK || got + bytes.size > begin.size) { link.send(HMsg.Denied(tr(R.string.bring_too_big))); return }
                    out.write(bytes)
                    digest.update(bytes)
                    got += bytes.size
                    local.bringProgress(them, got, begin.size)
                    if (!link.send(HMsg.BringGot(n))) return
                }
            }
            if (link.read() != HMsg.BringEnd) return
            if (got != begin.size || !LinkKeys.same(LinkKeys.hex(digest.digest()).toByteArray(), begin.sha256.lowercase().toByteArray())) {
                link.send(HMsg.Denied(tr(R.string.bring_didn_t_check_out)))
                return
            }
            val why = runCatching { local.bringArrived(file, them) }.getOrElse { tr(R.string.bring_failed) }
            ok = why == null
            link.send(why?.let { HMsg.Denied(it) } ?: HMsg.Ok)
        } finally {
            runCatching { file?.delete() }
            local.bringEnded(them, ok)
        }
    }
}

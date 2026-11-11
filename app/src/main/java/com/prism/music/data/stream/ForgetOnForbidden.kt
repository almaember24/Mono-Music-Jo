package com.prism.music.data.stream

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

/**
 * Tells [onForbidden] the stream's key (and the code) when YouTube refuses its link (403 / 410), then fails as usual: as
 * it opens, or later on (#408: a chunk after the first megabyte, which a download's retries otherwise asked for again
 * with the same refused link).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ForgetOnForbidden(private val inner: DataSource, private val onForbidden: (String, Int) -> Unit) : DataSource by inner {
    private var key: String? = null

    override fun open(dataSpec: DataSpec): Long {
        key = dataSpec.key
        return try {
            inner.open(dataSpec)
        } catch (e: IOException) {
            noteRefused(e)
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        inner.read(buffer, offset, length)
    } catch (e: IOException) {
        noteRefused(e)
        throw e
    }

    private fun noteRefused(e: IOException) {
        val code = refusedCode(e) ?: return
        key?.let { onForbidden(it, code) }
    }

    companion object {
        /** 403 or 410 when [e] (or what it wraps) is YouTube turning the link away; else null. */
        fun refusedCode(e: Throwable): Int? = generateSequence(e) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
            ?.takeIf { it == 403 || it == 410 }
    }
}

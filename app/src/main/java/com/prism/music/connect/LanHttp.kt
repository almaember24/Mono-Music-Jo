package com.prism.music.connect

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Plain HTTP to a device on the local network (GitHub #239), for what speaks UPnP: its description and its SOAP actions.
 *
 * UPnP has no https. Android refuses an app's plain http by default from target SDK 28 (a network security config can't
 * name "the local network", only domains), and OkHttp keeps to that: it failed with "CLEARTEXT communication … not
 * permitted", so no UPnP speaker's description could be read and none was listed. A socket of Prism's own isn't under
 * that rule, so this goes through one for addresses [Describe.isLocalLink] calls local, and hands everything else on to
 * OkHttp as before: the rest of Prism stays https only.
 */
object LanHttp : Interceptor {
    /** A description or a SOAP answer: small. Anything longer isn't what was asked for. */
    private const val MAX_BODY = 2 * 1024 * 1024
    private const val MAX_HEAD = 64 * 1024

    /** A call without its own time limit still ends (a device that trickles bytes would otherwise never let go). */
    private val DEFAULT_LIMIT_MS = TimeUnit.SECONDS.toMillis(10)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!Describe.isLocalLink(request.url.toString())) return chain.proceed(request)
        val limitNs = chain.call().timeout().timeoutNanos()
        val limitMs = if (limitNs > 0) TimeUnit.NANOSECONDS.toMillis(limitNs) else DEFAULT_LIMIT_MS
        val until = System.currentTimeMillis() + limitMs
        return exchange(request, chain.connectTimeoutMillis(), chain.readTimeoutMillis(), until) { chain.call().isCanceled() }
    }

    /** Asked before any other interceptor on the client: a device on the local network is nobody else's to answer. */
    fun OkHttpClient.Builder.lanFirst() = apply { interceptors().add(0, LanHttp) }

    private fun exchange(request: Request, connectMs: Int, readMs: Int, until: Long, cancelled: () -> Boolean): Response {
        val url = request.url
        Socket().use { socket ->
            val connect = minOf(if (connectMs > 0) connectMs.toLong() else DEFAULT_LIMIT_MS, left(until))
            socket.connect(InetSocketAddress(InetAddress.getByName(url.host), url.port), connect.toInt())
            socket.tcpNoDelay = true
            socket.soTimeout = minOf(if (readMs > 0) readMs.toLong() else DEFAULT_LIMIT_MS, left(until)).toInt()
            val out = socket.getOutputStream()
            out.write(head(request))
            request.body?.let { b -> Buffer().also(b::writeTo).let { out.write(it.readByteArray()) } }
            out.flush()
            val input = socket.getInputStream().buffered()
            val check = { if (cancelled()) throw IOException("Canceled"); if (left(until) <= 1) throw SocketTimeoutException("No whole answer from ${url.host}") }
            while (true) {
                check()
                val status = statusLine(line(input, check))
                val headers = headers(input, check)
                // 100 Continue and the like: the real answer follows.
                if (status.code in 100..199) continue
                val noBody = request.method == "HEAD" || status.code == 204 || status.code == 304
                val bytes = if (noBody) ByteArray(0) else body(input, headers, check)
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status.code)
                    .message(status.message)
                    .headers(headers)
                    .body(bytes.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull()))
                    .build()
            }
        }
    }

    private fun left(until: Long) = (until - System.currentTimeMillis()).coerceAtLeast(1)

    private fun head(request: Request): ByteArray {
        val url = request.url
        val host = (if (url.host.contains(':')) "[${url.host}]" else url.host) + (if (url.port != 80) ":${url.port}" else "")
        val query = url.encodedQuery?.let { "?$it" }.orEmpty()
        val b = StringBuilder("${request.method} ${url.encodedPath}$query HTTP/1.1\r\n")
        b.append("Host: ").append(host).append("\r\n")
        for ((name, value) in request.headers) {
            if (name.equals("Host", true) || name.equals("Connection", true)) continue
            b.append(name).append(": ").append(value).append("\r\n")
        }
        request.body?.let { body ->
            body.contentType()?.let { if (request.header("Content-Type") == null) b.append("Content-Type: ").append(it).append("\r\n") }
            b.append("Content-Length: ").append(body.contentLength()).append("\r\n")
        }
        b.append("Connection: close\r\n\r\n")
        return b.toString().toByteArray(Charsets.UTF_8)
    }

    private class Status(val code: Int, val message: String)

    private fun statusLine(line: String): Status {
        if (!line.startsWith("HTTP/1.", ignoreCase = true)) throw IOException("Not an HTTP answer")
        val parts = line.split(' ', limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 100..599 } ?: throw IOException("Not an HTTP answer")
        return Status(code, parts.getOrNull(2).orEmpty())
    }

    private fun headers(input: InputStream, check: () -> Unit): Headers {
        val b = Headers.Builder()
        var read = 0
        while (true) {
            val l = line(input, check)
            if (l.isEmpty()) return b.build()
            read += l.length
            if (read > MAX_HEAD) throw IOException("Answer's headers are too long")
            // A device's odd header isn't worth losing its answer for.
            runCatching { b.addLine(l) }
        }
    }

    /** One line, without its line end (some devices end lines with \n alone). A byte a second doesn't outlast the call's limit. */
    private fun line(input: InputStream, check: () -> Unit): String {
        val b = ByteArrayOutputStream()
        while (true) {
            check()
            val c = input.read()
            if (c < 0) { if (b.size() == 0) throw EOFException("The device closed the connection"); break }
            if (c == '\n'.code) break
            b.write(c)
            if (b.size() > MAX_HEAD) throw IOException("Answer's line is too long")
        }
        return b.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
    }

    private fun Headers.Builder.addLine(line: String) {
        val i = line.indexOf(':')
        if (i > 0) addUnsafeNonAscii(line.substring(0, i).trim(), line.substring(i + 1).trim())
    }

    private fun body(input: InputStream, headers: Headers, check: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val length = headers["Content-Length"]?.trim()?.toLongOrNull()
        when {
            headers["Transfer-Encoding"]?.contains("chunked", ignoreCase = true) == true -> {
                while (true) {
                    check()
                    val size = line(input, check).substringBefore(';').trim().toIntOrNull(16)?.takeIf { it >= 0 } ?: throw IOException("Bad chunk size")
                    if (size == 0) break
                    copy(input, out, size.toLong(), exact = true, check = check)
                    line(input, check)
                }
            }
            length != null -> copy(input, out, length, exact = true, check = check)
            // No length given: to the end of the connection.
            else -> copy(input, out, MAX_BODY.toLong(), exact = false, check = check)
        }
        return out.toByteArray()
    }

    private fun copy(input: InputStream, out: ByteArrayOutputStream, count: Long, exact: Boolean, check: () -> Unit) {
        if (exact && out.size() + count > MAX_BODY) throw IOException("Answer's too long")
        val buf = ByteArray(8192)
        var left = count
        while (left > 0) {
            check()
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) {
                if (exact) throw EOFException("The device's answer stopped short")
                return
            }
            out.write(buf, 0, n)
            left -= n
        }
    }
}

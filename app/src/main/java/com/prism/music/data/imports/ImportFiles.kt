package com.prism.music.data.imports

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.longOrNull
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

/**
 * Reading the files a data download is made of (1.8.1): a zip as it came, or the JSON files taken out of it, a whole
 * file never in memory at once (a ten-year history is 100,000+ plays). Anything unexpected in a file is passed over.
 */
internal object ImportFiles {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Each JSON file in [input] (a zip, or one file called [name]) with its name, as a stream to read; [block] must
     * read it there and then (a zip's next file follows straight after).
     */
    fun forEachFile(name: String, input: InputStream, block: (String, InputStream) -> Unit) {
        val buffered = PushbackInputStream(input.buffered(), 4)
        val head = ByteArray(4)
        val n = buffered.read(head).coerceAtLeast(0)
        if (n > 0) buffered.unread(head, 0, n)
        if (n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            val zip = ZipInputStream(buffered)
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) block(e.name.substringAfterLast('/'), Unclosable(zip))
                zip.closeEntry()
            }
        } else block(name.substringAfterLast('/'), buffered)
    }

    /** The elements of a JSON array, one at a time; stops quietly where a file is cut short. */
    @OptIn(ExperimentalSerializationApi::class)
    fun arrayItems(input: InputStream): Sequence<JsonObject> = sequence {
        val it = runCatching { json.decodeToSequence(withoutBom(input), JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).iterator() }.getOrNull() ?: return@sequence
        while (true) {
            val next = runCatching { if (it.hasNext()) it.next() else null }.getOrNull() ?: break
            (next as? JsonObject)?.let { o -> yield(o) }
        }
    }

    /** A whole (small) JSON file. */
    fun whole(input: InputStream): JsonElement? = runCatching { json.parseToJsonElement(withoutBom(input).readBytes().decodeToString()) }.getOrNull()

    private fun withoutBom(input: InputStream): InputStream {
        val p = PushbackInputStream(input, 3)
        val b = ByteArray(3)
        val n = p.read(b).coerceAtLeast(0)
        val bom = n == 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()
        if (!bom && n > 0) p.unread(b, 0, n)
        return p
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    /** A zip's file, which the reader of one file mustn't close (the zip goes on to the next one). */
    private class Unclosable(input: InputStream) : FilterInputStream(input) { override fun close() {} }
}

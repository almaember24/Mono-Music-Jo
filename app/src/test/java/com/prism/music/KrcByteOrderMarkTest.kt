package com.prism.music

import com.prism.music.data.lyrics.WordFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** KuGou's KRC files can start with a byte-order mark; it must not end up in the lyrics (seen on #403's KAIRO song). */
class KrcByteOrderMarkTest {
    private fun krc(text: String): String {
        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val zipped = out.toByteArray()
        val xored = ByteArray(zipped.size) { i -> (zipped[i].toInt() xor key[i % 16]).toByte() }
        return java.util.Base64.getEncoder().encodeToString("krc1".toByteArray(Charsets.US_ASCII) + xored)
    }

    @Test
    fun aByteOrderMarkIsLeftOut() {
        val text = "﻿[0,1000]<0,500,0>You <500,500,0>want"
        val decoded = WordFormat.decodeKrc(krc(text))
        assertFalse("no byte-order mark: $decoded", decoded!!.contains('﻿'))
        assertEquals("[0,1000]<0,500,0>You <500,500,0>want", decoded)
    }
}

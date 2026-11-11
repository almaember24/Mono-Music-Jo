package com.prism.music

import com.prism.music.data.lastfm.LastFm
import org.junit.Assert.assertEquals
import org.junit.Test

/** Q9 (#133): Last.fm's request signature, as its API documentation describes it. */
class LastFmTest {
    @Test fun signatureFollowsLastFmsRule() {
        // Every parameter but format and callback, sorted by name, "name" + "value" joined, then the secret, MD5.
        val params = mapOf("method" to "auth.getSession", "api_key" to "xxx", "token" to "yyy", "format" to "json")
        val expected = java.security.MessageDigest.getInstance("MD5")
            .digest("api_keyxxxmethodauth.getSessiontokenyyysecret".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, LastFm.signature(params, "secret"))
        // Batches use bracketed names, sorted as text: artist[0] before artist[1] before method.
        val batch = mapOf("method" to "track.scrobble", "artist[1]" to "B", "artist[0]" to "A", "api_key" to "k")
        val text = "api_keykartist[0]Aartist[1]Bmethodtrack.scrobbles"
        assertEquals(java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }, LastFm.signature(batch, "s"))
    }
}

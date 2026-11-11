package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.YouTubeSeen
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Live: YouTube's own word on whether a request counted as signed in is where [YouTubeSeen] reads it. Without an account
 * here, the answer asked anonymously has to say "not signed in"; on a signed-in phone, the diagnostics' line says the rest.
 */
@Category(LiveNetwork::class)
class AccountSeenLiveTest {
    @Test fun anAnonymousAnswerSaysItIsNotSignedIn() = runBlocking {
        val api = InnerTube(OkHttpClient(), { AppSettings() })
        val res = api.postAnonymous("browse", kotlinx.serialization.json.buildJsonObject {
            put("browseId", kotlinx.serialization.json.JsonPrimitive("FEmusic_home"))
        })
        val seen = YouTubeSeen.of(res)
        println("YouTube's answer: ${seen.text}; context keys ${(res["responseContext"] as? kotlinx.serialization.json.JsonObject)?.keys}")
        assertEquals(false, seen.signedIn)
    }
}

package com.prism.music

import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.stream.StreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub #260 (1.7.9.3): YouTube began answering the plain older TV app "The page needs to be reloaded" for most
 * songs, so account-only songs fell to the web app, whose links stop after a megabyte. The account's clients now
 * follow yt-dlp's for signed-in users, and the older TV app gets a second ask.
 */
class AccountClientsTest {
    private val order = StreamResolver.AccountClients.order

    @Test fun yt_dlpsSignedInClientsComeFirstAndTheWebAppLast() {
        assertEquals(InnerTube.PlayerClient.TV_SAMSUNG, order[0])
        assertEquals(InnerTube.PlayerClient.WEB_EMBEDDED, order[1])
        assertEquals(InnerTube.PlayerClient.WEB_REMIX, order.last())
        assertEquals("every client is asked, once", InnerTube.PlayerClient.entries.toSet(), order.toSet())
        assertEquals(order.size, order.distinct().size)
    }

    @Test fun theSamsungTvDescribesItselfAsYouTubesAppDoes() {
        val tv = InnerTube.PlayerClient.TV_SAMSUNG
        assertEquals("TVHTML5", tv.clientName)
        assertEquals(InnerTube.TV_OLD_CLIENT_VERSION, tv.version)
        assertEquals(mapOf("deviceMake" to "Samsung", "deviceModel" to "UKS9800", "osName" to "Tizen", "osVersion" to "2.4.0"), tv.extra)
        assertTrue(tv.userAgent.contains("Tizen"))
    }

    @Test fun onlyTheOlderTvAppIsAskedTwice() {
        assertEquals(1, StreamResolver.AccountClients.retries(InnerTube.PlayerClient.TV_SAMSUNG))
        assertEquals(1, StreamResolver.AccountClients.retries(InnerTube.PlayerClient.TV_OLD))
        assertEquals(0, StreamResolver.AccountClients.retries(InnerTube.PlayerClient.WEB_EMBEDDED))
        assertEquals(0, StreamResolver.AccountClients.retries(InnerTube.PlayerClient.WEB_REMIX))
    }
}

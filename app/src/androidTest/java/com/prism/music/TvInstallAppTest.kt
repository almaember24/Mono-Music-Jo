package com.prism.music

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Prism on a TV (1.8.0): searching "fire tv" finds the switch, switching it on hands out this very copy of Prism (the
 * whole file, as the TV's Downloader fetches it), and leaving the page stops it.
 */
@RunWith(AndroidJUnit4::class)
class TvInstallAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    @Test fun theSwitchHandsOutThisCopyUntilThePageCloses() {
        Fixture.ready()
        Fixture.launch()
        compose.openSettings()
        compose.onNode(hasSetTextAction()).performTextInput("fire tv")
        compose.clickWhenFound(hasText("Share Prism with a TV") and hasClickAction())
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty() }
        compose.clickWhenFound(hasText("Share Prism with a TV") and hasClickAction())
        val share = Fixture.container.tvInstall
        compose.waitUntil(5_000) { share.running }
        compose.waitForText("Downloader app")

        val apk = File(Fixture.context.applicationInfo.sourceDir)
        // A plain socket, as the TV's Downloader asks: Prism's own HTTP client may not use cleartext (network security
        // config), which says nothing about what a TV may fetch from it.
        val (status, length) = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            java.net.Socket("127.0.0.1", share.port).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:${share.port}\r\n\r\n".toByteArray())
                val input = java.io.BufferedInputStream(s.getInputStream())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) break; head.append(b.toChar()) }
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; total += n }
                head.lineSequence().first() to total
            }
        }
        assertEquals("HTTP/1.1 200 OK", status)
        assertEquals("the whole installed APK", apk.length(), length)
        compose.waitForText("Sent. Finish installing on the TV.")

        // On a freshly started emulator the keyboard, opening for the first time, can come up after the page has opened:
        // the first Back then only closes it (a screenshot from inside the app doesn't show it).
        repeat(2) { if (share.running) { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack(); Thread.sleep(2_000) } }
        compose.waitUntil(5_000) { !share.running }
        assertFalse(share.running)
    }
}

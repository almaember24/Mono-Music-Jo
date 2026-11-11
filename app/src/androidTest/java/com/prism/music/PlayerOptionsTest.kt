package com.prism.music

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.playback.QueueKind
import com.prism.music.playback.QueueSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.7.9.1, three player requests from Reddit, each an option off by default: the volume bar like the progress bar, the
 * grey handle at the player's top hidden, and the status bar hidden only while the player is open.
 */
@RunWith(AndroidJUnit4::class)
class PlayerOptionsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val shots = ScreenshotOnFailure()

    private var tone: android.net.Uri? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @After fun cleanUp() {
        runCatching { instrumentation.runOnMainSync { Fixture.container.player.stop() } }
        tone?.let { runCatching { Fixture.context.contentResolver.delete(it, null, null) } }
    }

    /** A test tone playing, the full player open (by the link a widget's cover uses), then paused so Compose goes idle. */
    private fun openPlayer() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism options tone", 120)
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism options tone" }.song
        instrumentation.runOnMainSync { Fixture.container.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Options test")) }
        compose.waitForText("Prism options tone", 20_000)
        Fixture.context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("prism://player"))
                .setClass(Fixture.context, PrismActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        Thread.sleep(2_000)
        instrumentation.runOnMainSync { Fixture.container.player.setPlaying(false) }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("progressBar")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun height(tag: String): Float = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().first().boundsInRoot.height

    @Test fun theVolumeBarCanMatchTheProgressBar() {
        runBlocking {
            Fixture.container.settings.setHideVolumeBar(false)
            Fixture.container.settings.setScrubberThickness(1.6f)
        }
        openPlayer()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("volumeBar")).fetchSemanticsNodes().isNotEmpty() }
        // Off (as before): its own height, not the thicker progress bar's.
        assertNotEquals("off: its own height", height("progressBar"), height("volumeBar"), 0.5f)

        runBlocking { Fixture.container.settings.setVolumeMatchesProgress(true) }
        compose.waitUntil(10_000) { kotlin.math.abs(height("progressBar") - height("volumeBar")) < 0.5f }
        assertEquals("on: the progress bar's height", height("progressBar"), height("volumeBar"), 0.5f)
    }

    @Test fun theHandleCanGo() {
        openPlayer()
        assertTrue("on by default", compose.onAllNodes(hasTestTag("playerHandle")).fetchSemanticsNodes().isNotEmpty())
        runBlocking { Fixture.container.settings.setPlayerHandle(false) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("playerHandle")).fetchSemanticsNodes().isEmpty() }
    }

    /** #250: the dark band behind three-button navigation on the player can go (the player shows through). */
    @Test fun theBandBehindTheNavigationButtonsCanGo() {
        openPlayer()
        if (android.os.Build.VERSION.SDK_INT < 35) {
            compose.waitUntil(5_000) { navBarColor() != android.graphics.Color.TRANSPARENT }
            runBlocking { Fixture.container.settings.setPlayerNavBand(false) }
            compose.waitUntil(10_000) { navBarColor() == android.graphics.Color.TRANSPARENT }
            return
        }
        // Android 15+ ignores a window's navigation bar color for apps targeting 35+ (edge to edge is enforced): the
        // player draws the band itself (1.8.1.1), only behind three-button navigation, never with gestures.
        fun band() = compose.onAllNodes(hasTestTag("playerNavBand"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        if (threeButtonNavigation()) compose.waitUntil(5_000) { band() }
        else {
            compose.waitForIdle()
            org.junit.Assert.assertFalse("gesture navigation: no band", band())
        }
        runBlocking { Fixture.container.settings.setPlayerNavBand(false) }
        compose.waitUntil(10_000) { !band() }
    }

    /** Three-button navigation: its bar takes taps (the gesture handle's doesn't, so its tappable inset is 0). */
    private fun threeButtonNavigation(): Boolean {
        var bottom = 0
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
            bottom = activity?.window?.decorView?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.tappableElement())?.bottom ?: 0
        }
        return bottom > 0
    }

    private fun navBarColor(): Int {
        var color = 0
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
            @Suppress("DEPRECATION")
            color = activity?.window?.navigationBarColor ?: 0
        }
        return color
    }

    @Test fun theStatusBarCanGoOnlyOnThePlayer() {
        runBlocking { Fixture.container.settings.setHideStatusBarPlayer(true) }
        openPlayer()
        compose.waitUntil(10_000) { !statusBarShown() }

        // Closing the player brings it back.
        instrumentation.runOnMainSync { Fixture.container.player.setPlaying(false) }
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
        compose.waitUntil(10_000) { statusBarShown() }
    }

    /**
     * The owner (1.8.0): hiding the status bar moved the whole player up. Now only the bar goes: the player's title and
     * cover stay where they were, for "Hide it only on the player" and for hiding it everywhere.
     */
    @Test fun hidingTheStatusBarLeavesThePlayerInPlace() {
        openPlayer()
        fun top(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.top
        val title = top("playerTitle")
        val handle = top("playerHandle")
        for (hide in listOf<suspend () -> Unit>({ Fixture.container.settings.setHideStatusBarPlayer(true) }, { Fixture.container.settings.setHideStatusBar(true) })) {
            runBlocking { hide() }
            compose.waitUntil(10_000) { !statusBarShown() }
            compose.waitForIdle(); Thread.sleep(600)
            assertEquals("the title stayed", title, top("playerTitle"), 1f)
            assertEquals("the handle stayed", handle, top("playerHandle"), 1f)
            runBlocking { Fixture.container.settings.setHideStatusBarPlayer(false); Fixture.container.settings.setHideStatusBar(false) }
            compose.waitUntil(10_000) { statusBarShown() }
        }
    }

    /** #231 (1.7.9.2): the split-light ring around play can go. Only the ring's own pixels should change. */
    @Test fun thePlayRingCanGo() {
        openPlayer()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("playDisc")).fetchSemanticsNodes().isNotEmpty() }
        // The song under way first (or given up on after a while), or the two pictures can differ by the glyph (play, then
        // pause), not the ring.
        val started = System.currentTimeMillis() + 8_000
        while (!Fixture.container.player.isPlaying.value && System.currentTimeMillis() < started) Thread.sleep(200)
        compose.waitForIdle(); Thread.sleep(800)
        val player = Fixture.container.player
        val playingOn = player.isPlaying.value
        val on = disc()
        runBlocking { Fixture.container.settings.setPlayRing(false) }
        // The change also brings up the Look's Undo bar, which must stay off the button: it used to cover its lower half.
        compose.waitForIdle(); Thread.sleep(600)
        val off = disc()
        // The glyph swaps with the state (play, pause): the same state in both pictures, or this says nothing about the ring.
        org.junit.Assume.assumeTrue("the song started or stopped between the pictures (playing $playingOn, then ${player.isPlaying.value})", playingOn == player.isPlaying.value)
        val w = on.width
        val ringR = (w - 14 * density()) / 2f - 1 * density()
        val ring = meanDiff(on, off, ringR)
        val inside = meanDiff(on, off, ringR * 0.75f)
        // What the two pictures hold across the disc's middle, for when this fails.
        val across = (0..8).joinToString(" ") { i ->
            val x = (on.width * i / 8).coerceAtMost(on.width - 1); val y = on.height / 2
            "%06x/%06x".format(on.getPixel(x, y) and 0xFFFFFF, off.getPixel(x, y) and 0xFFFFFF)
        }
        assertTrue("the ring's pixels changed ($ring vs $inside inside; playing $playingOn; ${on.width}x${on.height}, on/off across: $across)", ring > 40f && ring > inside * 3f)
    }

    /** #231: the mini player's Fill in your accent instead of the split light, as strong as asked. */
    @Test fun theMiniPlayersFillCanBeYourAccent() {
        Fixture.ready()
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(Fixture.context.packageName, perm)
        tone = Fixture.tone("Prism options tone", 120)
        runBlocking {
            Fixture.container.settings.setMiniProgress(com.prism.music.data.prefs.MiniProgress.FILL)
            Fixture.container.settings.setMiniFillStrength(0.9f)
        }
        Fixture.launch()
        val local = Fixture.container.localMusic
        runBlocking { local.scan() }
        val song = local.tracks.value.first { it.song.title == "Prism options tone" }.song
        instrumentation.runOnMainSync { Fixture.container.player.playQueue(listOf(song), 0, QueueSource(QueueKind.OTHER, "Options test")) }
        compose.waitUntil(20_000) { compose.onAllNodes(hasTestTag("miniPlayer")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_500)
        instrumentation.runOnMainSync { Fixture.container.player.seekTo(70_000); Fixture.container.player.setPlaying(false) }
        compose.waitForIdle(); Thread.sleep(800)
        val split = compose.onAllNodes(hasTestTag("miniPlayer")).onFirst().captureToImage().asAndroidBitmap()
        runBlocking { Fixture.container.settings.setMiniProgressColor(com.prism.music.data.prefs.ScrubberColor.ACCENT) }
        compose.waitForIdle(); Thread.sleep(800)
        val accent = compose.onAllNodes(hasTestTag("miniPlayer")).onFirst().captureToImage().asAndroidBitmap()
        // The played part (left, behind the cover's row) changes color; the part still to play doesn't.
        val played = columnDiff(split, accent, split.width / 2)
        val ahead = columnDiff(split, accent, split.width * 92 / 100)
        assertTrue("played part recolored ($played vs $ahead ahead)", played > 30f && played > ahead * 3f)
    }

    /**
     * #259 (1.7.9.2): with "Gestures anywhere on the player", the cover's double tap (set to play/pause here) works on
     * the blank space beside the controls too; off, the same double tap there does nothing.
     */
    @Test fun gesturesWorkAroundTheControlsWhenAsked() {
        runBlocking { Fixture.container.settings.setGesture("cover_double_tap", com.prism.music.data.prefs.PlayerGesture.PLAY_PAUSE) }
        openPlayer()
        val player = Fixture.container.player
        // Beside Previous, at the deck's very edge: no button or bar there.
        val prev = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(tr(R.string.widget_previous))).fetchSemanticsNodes().first().boundsInRoot
        val deck = compose.onAllNodes(hasTestTag("playerDeck")).fetchSemanticsNodes().first().boundsInRoot
        val at = androidx.compose.ui.geometry.Offset(3 * density(), prev.center.y - deck.top)
        fun doubleTapBeside() { compose.onAllNodes(hasTestTag("playerDeck")).onFirst().performTouchInput { doubleClick(at) }; compose.waitForIdle(); Thread.sleep(700) }

        doubleTapBeside()
        assertEquals("off: the controls' space ignores it", false, player.isPlaying.value)

        runBlocking { Fixture.container.settings.setGesturesEverywhere(true) }
        compose.waitForIdle(); Thread.sleep(500)
        doubleTapBeside()
        compose.waitUntil(5_000) { player.isPlaying.value }
        instrumentation.runOnMainSync { player.setPlaying(false) }
    }

    /**
     * #230 (1.7.9.2): "Lyrics and the queue: Small". With the queue open the controls stay, in less room: no volume
     * bar, and a shorter deck than "Always shown" gives.
     */
    @Test fun theControlsCanStaySmallOverTheQueue() {
        runBlocking {
            Fixture.container.settings.setHideVolumeBar(false)
            Fixture.container.settings.setPanelPlayer(com.prism.music.data.prefs.PanelPlayer.ALWAYS)
        }
        openPlayer()
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(tr(R.string.up_next))).onFirst().performClick()
        compose.waitForIdle(); Thread.sleep(800)
        val always = height("playerDeck")
        assertTrue("always: the volume bar's there", compose.onAllNodes(hasTestTag("volumeBar")).fetchSemanticsNodes().isNotEmpty())

        runBlocking { Fixture.container.settings.setPanelPlayer(com.prism.music.data.prefs.PanelPlayer.SMALL) }
        compose.waitForIdle(); Thread.sleep(800)
        assertTrue("small: no volume bar", compose.onAllNodes(hasTestTag("volumeBar")).fetchSemanticsNodes().isEmpty())
        val small = height("playerDeck")
        assertTrue("small ($small) is shorter than always ($always)", small < always * 0.8f)
        runBlocking { Fixture.container.settings.setPanelPlayer(com.prism.music.data.prefs.PanelPlayer.AUTO_HIDE) }
    }

    private fun density() = Fixture.context.resources.displayMetrics.density

    private fun disc() = compose.onAllNodes(hasTestTag("playDisc")).onFirst().captureToImage().asAndroidBitmap()

    private fun pixelDiff(a: Int, b: Int): Int =
        kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))

    /** The average change between [a] and [b] around a circle of radius [r] about their middle. */
    private fun meanDiff(a: android.graphics.Bitmap, b: android.graphics.Bitmap, r: Float): Float {
        val cx = a.width / 2f; val cy = a.height / 2f
        return (0 until 72).map { i ->
            val t = i * Math.PI * 2 / 72
            val x = (cx + r * kotlin.math.cos(t)).toInt().coerceIn(0, a.width - 1)
            val y = (cy + r * kotlin.math.sin(t)).toInt().coerceIn(0, a.height - 1)
            pixelDiff(a.getPixel(x, y), b.getPixel(x, y))
        }.average().toFloat()
    }

    /** The average change down column [x], skipping the edges. */
    private fun columnDiff(a: android.graphics.Bitmap, b: android.graphics.Bitmap, x: Int): Float =
        (a.height / 5 until a.height * 4 / 5).map { y -> pixelDiff(a.getPixel(x, y), b.getPixel(x, y)) }.average().toFloat()

    private fun statusBarShown(): Boolean {
        var shown = true
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
            val decor = activity?.window?.decorView
            shown = if (android.os.Build.VERSION.SDK_INT < 30) {
                // Before Android 11 the bar is hidden with the old full-screen flag, which insets don't report.
                @Suppress("DEPRECATION")
                decor == null || decor.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN == 0
            } else {
                val insets = decor?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it) }
                insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) ?: true
            }
        }
        return shown
    }
}

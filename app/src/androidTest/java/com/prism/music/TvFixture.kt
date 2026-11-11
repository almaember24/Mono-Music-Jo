package com.prism.music

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.prism.music.ui.tv.TvDevice
import org.junit.Assume.assumeTrue

/**
 * Prism TV's in-app tests (1.8.0, plan-1.8.0 §8.2). They run on the TV emulators only: phone runs leave them out
 * (build.gradle.kts `notAnnotation`) and `.claude/tv-1.8/tvsuite.ps1` picks them by this annotation. Their files end in
 * `Tests.kt`, so the phone suite's `insuite.ps1` (which lists `*Test.kt` classes) doesn't expect them either; on a
 * phone they skip themselves anyway ([TvFixture.assumeTv]).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class TvTest

/**
 * The TV tests' starting point and remote: Prism set up as [Fixture.ready] does, opened from the TV launcher, and
 * driven only by the remote's keys, sent through Android's input system like a real remote's.
 */
object TvFixture {
    /** A TV test on a phone: skipped. */
    fun assumeTv() = assumeTrue("a TV test (it runs on the TV emulators)", TvDevice.isTv(Fixture.context))

    /** Opens Prism TV as the TV launcher's banner does. */
    fun launch(): ActivityScenario<TvActivity> = ActivityScenario.launch(TvActivity::class.java)

    /** One press of a remote key (D-pad, OK = [KeyEvent.KEYCODE_DPAD_CENTER], Back, media keys). */
    fun press(key: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key)
    }

    /** A key pressed and let go [ms] later, as a hand on a remote does (key-down and key-up apart). */
    fun hold(key: Int, ms: Long) {
        val i = InstrumentationRegistry.getInstrumentation()
        val down = android.os.SystemClock.uptimeMillis()
        i.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, key, 0))
        Thread.sleep(ms)
        i.sendKeySync(KeyEvent(down, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
    }

    fun up() = press(KeyEvent.KEYCODE_DPAD_UP)
    fun down() = press(KeyEvent.KEYCODE_DPAD_DOWN)
    fun left() = press(KeyEvent.KEYCODE_DPAD_LEFT)
    fun right() = press(KeyEvent.KEYCODE_DPAD_RIGHT)
    fun ok() = press(KeyEvent.KEYCODE_DPAD_CENTER)
    fun back() = press(KeyEvent.KEYCODE_BACK)

    /** The node that has the remote's focus now, or null (focus rule 1: there always is one). */
    fun ComposeTestRule.focused(): SemanticsNode? = onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()

    /** Waits until something has focus, and returns it; fails the test when nothing does for [ms]. */
    fun ComposeTestRule.awaitFocus(ms: Long = 5_000): SemanticsNode {
        waitUntil(ms) { focused() != null }
        return focused()!!
    }

    /** Waits until a node matching [matcher] has focus. */
    fun ComposeTestRule.awaitFocusOn(matcher: SemanticsMatcher, ms: Long = 5_000) {
        try {
            waitUntil(ms) { onAllNodes(matcher and isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            // Says where focus is instead, so a failure on a slow TV shows what went wrong.
            val tag = runCatching { focused()?.config?.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } }.getOrNull()
            throw AssertionError("focus never reached ${matcher.description} in $ms ms; it is on ${tag ?: "nothing tagged"}", e)
        }
    }
}

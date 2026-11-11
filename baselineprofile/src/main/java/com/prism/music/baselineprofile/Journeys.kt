package com.prism.music.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app these journeys drive: the release build (and the build types the plugin derives from it). */
const val PACKAGE = "com.prism.music"

/**
 * What people do most in Prism, scripted with UI Automator: the screens whose code should be compiled ahead
 * ([BaselineProfileGenerator]) and whose frames are timed ([JankBenchmark]). Written to work signed out, on a fresh
 * install (it gets past the first-run screens itself) and with whatever Home YouTube serves.
 *
 * Nothing here presses Home or leaves Prism: on the Android 17 emulator, the task snapshot that takes restarts the
 * system (see .claude/emulator.ps1).
 */
object Journeys {
    private const val WAIT = 15_000L
    /** Home up with nothing to answer for this long: the tour comes 0.6 s after Home, Holiday Mode 1.5 s after it. */
    private const val SETTLED = 5_000L
    /** The longest pastOnboarding waits (a fresh install on a slow emulator: welcome, picker, tour, question). */
    private const val ONBOARDING = 90_000L

    /**
     * Past the welcome screen, the artist picker and the tour when they show (a fresh install), and Holiday Mode's
     * question (1.8.0): the first time Prism opens in a seasonal month after setting up, it asks once, 1.5 s after the
     * tour closes; the 1.8.0 profile run stalled on it. Done once Home has been up with nothing to answer for
     * [SETTLED] (so a question that comes up late is still caught), or after [ONBOARDING] at most.
     */
    fun MacrobenchmarkScope.pastOnboarding() {
        val end = SystemClock.uptimeMillis() + ONBOARDING
        var quietSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < end) {
            if (answerFirstRun() || answerPrompts()) {
                quietSince = SystemClock.uptimeMillis()
                continue
            }
            val home = device.hasObject(By.desc("Home").pkg(PACKAGE))
            if (!home) quietSince = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - quietSince >= SETTLED) return
            SystemClock.sleep(500)
        }
    }

    /** The first-run screens' ways past: the welcome screen and Prism Account, then the artist picker. */
    private fun MacrobenchmarkScope.answerFirstRun(): Boolean {
        for (label in listOf("Skip for now", "Skip")) {
            val button = device.findObject(By.text(label).pkg(PACKAGE)) ?: continue
            if (button.tap()) {
                device.waitForIdle(1_000)
                return true
            }
        }
        return false
    }

    /**
     * What Prism may put over any page once, answered so a journey carries on: the tour (Skip tour: new installs see
     * all of it, an update the pages since the last one, 1.8.1's five included) and Holiday Mode's question ("No
     * thanks", only inside its own dialog, as .claude/release-check/tools/lib.ps1 answers it). True when it tapped.
     */
    private fun MacrobenchmarkScope.answerPrompts(): Boolean {
        val holiday = device.hasObject(By.text("Holiday Mode").pkg(PACKAGE))
        val button = (if (holiday) device.findObject(By.text("No thanks").pkg(PACKAGE)) else null)
            ?: device.findObject(By.text("Skip tour").pkg(PACKAGE))
            ?: return false
        if (!button.tap()) return false
        device.waitForIdle(1_000)
        return true
    }

    /** Down Home a screen at a time and back up: covers, shelves and Prism's own sections compose as they come in. */
    fun MacrobenchmarkScope.scrollHome() {
        tab("Home")
        device.wait(Until.hasObject(By.textContains("Quick picks").pkg(PACKAGE)), 8_000)
        flingPage(down = true, times = 5)
        flingPage(down = false, times = 5)
    }

    /**
     * Opens a playlist or mix from Home and goes back (#228, 1.7.9: the first open of a mix and the way back to Home
     * stalled for a few hundred milliseconds; their code wasn't in the profile). Signed out, YouTube's own shelves
     * hold playlists ("Playlist • …" under them); without one on screen it does nothing.
     */
    fun MacrobenchmarkScope.openAPlaylist() {
        tab("Home")
        device.wait(Until.hasObject(By.textContains("Quick picks").pkg(PACKAGE)), 8_000)
        var item: UiObject2? = null
        repeat(6) {
            item = device.findObject(By.textStartsWith("Playlist").pkg(PACKAGE)) ?: device.findObject(By.textContains("Mix").pkg(PACKAGE))
            if (item != null) return@repeat
            flingPage(down = true, times = 1)
        }
        if (item?.tap() != true) return
        // The page's song rows have a "More" button. No page opened (the text tapped wasn't a card): no Back, which
        // would leave Prism and send every later journey's taps to the launcher (the 1.8.1 profile run, 2026-11-03).
        if (device.wait(Until.hasObject(By.desc("More")), WAIT) != true) return
        flingPage(down = true, times = 2)
        device.pressBack()
        device.waitForIdle(2_000)
        flingPage(down = false, times = 6)
    }

    /** Search for [query], then scroll through the results. */
    fun MacrobenchmarkScope.search(query: String) {
        tab("Search")
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT) ?: return
        field.click()
        field.text = query
        // One of Prism's own suggestions under the box runs the search. (An injected Enter goes to the on-screen
        // keyboard, which took it as "accept the word suggestion" and never searched.)
        // 1.7.6: the first one is "Search for “<query>”".
        val suggestion = device.wait(Until.findObject(By.textStartsWith("Search for").pkg(PACKAGE)), WAIT)
            ?: device.wait(Until.findObjects(By.textStartsWith(query).pkg(PACKAGE)), WAIT)
                ?.firstOrNull { it.className != "android.widget.EditText" }
        if (suggestion == null || !suggestion.tap()) device.pressEnter()
        // The results' first song row (rows have a "More" button).
        device.wait(Until.hasObject(By.desc("More")), WAIT)
        flingPage(down = true, times = 3)
        flingPage(down = false, times = 3)
    }

    /**
     * Opens an album from the results on screen and comes back. Album and playlist pages are one screen, and signed out
     * YouTube's Home doesn't always hold a playlist for [openAPlaylist] (the 1.8.2 profile run lost the whole page that way).
     */
    fun MacrobenchmarkScope.openAnAlbumFromResults() {
        // The journey searches Daft Punk: their albums by name (the text under a card varies by shelf, and "Album…" also
        // matches the "Albums" heading, which opens the whole list, where the next journey finds nothing to play).
        val line = java.util.regex.Pattern.compile("Discovery|Random Access Memories|Homework|Human After All|Album\\s+•.*")
        var album: UiObject2? = null
        var flung = 0
        while (flung < 4) {
            album = device.findObject(By.text(line).pkg(PACKAGE))
            if (album != null) break
            flingPage(down = true, times = 1)
            flung++
        }
        // As in openAPlaylist: no page opened, no Back.
        if (album?.tap() == true && device.wait(Until.hasObject(By.desc("More")), WAIT) == true) {
            flingPage(down = true, times = 2)
            device.pressBack()
            device.waitForIdle(2_000)
        }
        // Back to the first song rows for the next journey.
        flingPage(down = false, times = 5)
    }

    /** Plays the first song row on screen, opens the player, looks at the lyrics and puts the player away. */
    fun MacrobenchmarkScope.playAndOpenPlayer() {
        val more = device.wait(Until.findObject(By.desc("More")), WAIT) ?: return
        // The row's title, well left of its ⋯ button (which opens the song's menu instead).
        device.click(device.displayWidth / 3, more.visibleBounds.centerY())
        val play = device.wait(Until.findObject(By.desc("Play/pause")), WAIT) ?: return
        // The mini player: tap its title area, left of the buttons.
        val r = play.visibleBounds
        device.click(device.displayWidth / 3, r.centerY())
        device.wait(Until.hasObject(By.desc("Lyrics")), WAIT)
        device.waitForIdle(2_000)
        device.findObject(By.desc("Lyrics"))?.tap()
        device.waitForIdle(3_000)
        device.findObject(By.desc("Lyrics"))?.tap()
        device.waitForIdle(1_000)
        // Swipe the player down.
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, h / 5, w / 2, h * 9 / 10, 25)
        device.waitForIdle(1_500)
        // Still in the player (the lyrics took the swipe, or it didn't count)? Back closes the lyrics, then the player.
        // While the player is open the tab bar is hidden from accessibility, so the Library tab shows it's closed.
        repeat(3) {
            if (device.hasObject(By.desc("Library").pkg(PACKAGE))) return
            device.pressBack()
            device.waitForIdle(1_500)
        }
    }

    /** Library: its tiles and lists. */
    fun MacrobenchmarkScope.library() {
        tab("Library")
        device.waitForIdle(2_000)
        flingPage(down = true, times = 2)
        flingPage(down = false, times = 2)
    }

    /** Settings (its tab), and the Customize Prism page. */
    fun MacrobenchmarkScope.settings() {
        tab("Settings")
        device.wait(Until.findObject(By.text("Customize Prism")), WAIT)?.tap() ?: return
        device.waitForIdle(2_000)
        flingPage(down = true, times = 2)
        device.pressBack()
        device.waitForIdle(1_000)
        device.pressBack()
        device.waitForIdle(1_000)
    }

    /** A click that tolerates the view being redrawn between finding and clicking it (the lyrics button does). */
    private fun UiObject2.tap(): Boolean = try {
        click(); true
    } catch (_: StaleObjectException) {
        false
    }

    private fun MacrobenchmarkScope.tab(name: String) {
        // Prism no longer in front (a Back too many in an earlier journey): open it again, so this journey still runs.
        if (!device.hasObject(By.desc("Home").pkg(PACKAGE)) && !device.hasObject(By.desc("Library").pkg(PACKAGE))) {
            device.pressBack()
            device.waitForIdle(1_000)
            if (!device.hasObject(By.pkg(PACKAGE))) {
                startActivityAndWait()
                pastOnboarding()
            }
        }
        // A question that came up after pastOnboarding (the tour or Holiday Mode) would take the tap: answer it first.
        for (i in 1..3) if (!answerPrompts()) break
        // The tab bar's buttons carry their names as descriptions; Home's top bar has others, so take the lowest one.
        val tabs = device.findObjects(By.desc(name).pkg(PACKAGE))
        tabs.maxByOrNull { it.visibleBounds.centerY() }?.click()
        device.waitForIdle(1_500)
    }

    /** A page-long fling, [times] over: the way a thumb scrolls a list. */
    private fun MacrobenchmarkScope.flingPage(down: Boolean, times: Int) {
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(times) {
            if (down) device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 18) else device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 18)
            device.waitForIdle(700)
        }
    }
}

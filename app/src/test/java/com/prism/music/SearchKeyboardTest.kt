package com.prism.music

import com.prism.music.ui.screens.SearchKeyboard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #395: Search's typed list stays when a result's menu (a sheet in its own window) takes the keyboard away. */
class SearchKeyboardTest {
    /** The steps one after another, as Search's effect runs them; true if any of them showed the results. */
    private fun run(vararg states: Triple<Boolean, Boolean, Boolean>): Boolean {
        var wasUp = false
        var shown = false
        for ((focused, imeUp, window) in states) {
            val s = SearchKeyboard.step(focused, imeUp, wasUp, window)
            wasUp = s.imeWasUp
            shown = shown || s.showResults
        }
        return shown
    }

    @Test fun theKeyboardPutAwayShowsTheResults() {
        // Typing with the keyboard up, then Back puts it away: the results.
        assertTrue(run(Triple(true, true, true), Triple(true, false, true)))
    }

    @Test fun aHardwareKeyboardKeepsTheSuggestions() {
        assertFalse(run(Triple(true, false, true), Triple(true, false, true)))
    }

    @Test fun aMenuOpenedFromTheTypedListKeepsIt() {
        // ⋯ or a long press: the sheet's window takes the focus, then the keyboard goes down.
        assertFalse(run(Triple(true, true, true), Triple(true, true, false), Triple(true, false, false)))
        // The keyboard reported down in the same frame as the window's focus.
        assertFalse(run(Triple(true, true, true), Triple(true, false, false)))
    }

    @Test fun closingTheMenuKeepsTheListToo() {
        // The sheet closes, the window has the focus back before the keyboard comes up again (or it doesn't).
        assertFalse(run(Triple(true, true, true), Triple(true, false, false), Triple(true, false, true)))
        // And with the keyboard back up, putting it away again shows the results as before.
        assertTrue(run(Triple(true, true, true), Triple(true, false, false), Triple(true, false, true), Triple(true, true, true), Triple(true, false, true)))
    }

    @Test fun leavingTheFieldForgetsTheKeyboard() {
        assertFalse(run(Triple(true, true, true), Triple(false, false, true), Triple(true, false, true)))
    }
}

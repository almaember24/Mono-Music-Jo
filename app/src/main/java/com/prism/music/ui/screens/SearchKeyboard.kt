package com.prism.music.ui.screens

/**
 * Search's typed list and the keyboard: the keyboard put away (Back, its own button) means "show me the results", so
 * the suggestions give way to them. Only once the keyboard has been up, so a hardware keyboard keeps the suggestions.
 */
internal object SearchKeyboard {
    /** What's known after a change: whether the keyboard has been up, and whether to show the results now. */
    data class Step(val imeWasUp: Boolean, val showResults: Boolean)

    fun step(focused: Boolean, imeUp: Boolean, imeWasUp: Boolean, windowFocused: Boolean): Step = when {
        !focused -> Step(imeWasUp = false, showResults = false)
        imeUp -> Step(imeWasUp = true, showResults = false)
        // #395: a window of its own (a result's menu, a sheet) took the focus and with it the keyboard. That isn't
        // "show me the results", and the keyboard's having been up is forgotten, so the window's focus coming back
        // before the keyboard does isn't either.
        !windowFocused -> Step(imeWasUp = false, showResults = false)
        imeWasUp -> Step(imeWasUp = false, showResults = true)
        else -> Step(imeWasUp = false, showResults = false)
    }
}

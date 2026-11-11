package com.prism.music.data.imports

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live history source's kill switch (1.8.1, as Spotify's features have theirs): a failure counts, a success clears
 * the count, and [LIMIT] failures running pause the source for a day (written to diagnostics), so a YouTube that has
 * stopped answering is never asked again and again. Kept in the source's own preferences, so a restart doesn't reset it.
 */
class LiveGuard(
    private val prefs: SharedPreferences,
    private val name: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    enum class State { OK, NOT_ANSWERING, PAUSED }

    private val failuresKey = FAILURES
    private val pausedKey = PAUSED_UNTIL

    private val _state = MutableStateFlow(stateNow())
    /** What the page says. */
    val state: StateFlow<State> = _state

    val failures: Int get() = prefs.getInt(failuresKey, 0)
    val pausedUntil: Long get() = prefs.getLong(pausedKey, 0)

    /** The source may ask now. */
    fun mayRun(): Boolean = pausedUntil <= now()

    private fun stateNow() = when {
        pausedUntil > now() -> State.PAUSED
        failures > 0 -> State.NOT_ANSWERING
        else -> State.OK
    }

    fun succeeded() {
        if (failures != 0 || pausedUntil != 0L) prefs.edit().remove(failuresKey).remove(pausedKey).apply()
        _state.value = State.OK
    }

    /** A run failed with [e]. Returns true when that paused the source. */
    fun failed(e: Throwable): Boolean {
        val n = failures + 1
        val edit = prefs.edit()
        val paused = n >= LIMIT
        if (paused) {
            edit.putLong(pausedKey, now() + DAY_MS).putInt(failuresKey, 0)
            log("$name: $n failures running, paused for a day (last: ${e.javaClass.simpleName}: ${e.message})")
        } else {
            edit.putInt(failuresKey, n)
            log("$name: failed ($n of $LIMIT): ${e.javaClass.simpleName}: ${e.message}")
        }
        edit.commit()
        _state.value = stateNow().let { if (it == State.OK) State.NOT_ANSWERING else it }
        return paused
    }

    /** Turned off and on again, or signed out. */
    fun reset() {
        prefs.edit().remove(failuresKey).remove(pausedKey).apply()
        _state.value = State.OK
    }

    companion object {
        const val LIMIT = 5
        const val FAILURES = "guard_failures"
        const val PAUSED_UNTIL = "guard_paused_until"
        const val DAY_MS = 86_400_000L
    }
}

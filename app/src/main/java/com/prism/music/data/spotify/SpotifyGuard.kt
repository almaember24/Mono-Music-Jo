package com.prism.music.data.spotify

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Spotify features, each with its own switch, its own guard and its own word on the page. */
enum class SpotifyFeature(val key: String) {
    RECENT("recent"), TOP("top"), PLAYLISTS("playlists"), LIKES("likes");
}

/**
 * One feature's kill switch: a failure counts, a success clears the count, and [LIMIT] failures running pause the
 * feature for a day (written to diagnostics), so a broken Spotify is never asked again and again. A 429 pauses it until
 * Spotify's Retry-After. Kept in the "spotify" preferences, so a restart doesn't reset it.
 */
class SpotifyGuard(
    private val prefs: SharedPreferences,
    val feature: SpotifyFeature,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    enum class State { OK, NOT_ANSWERING, PAUSED }

    private fun k(name: String) = "guard_${feature.key}_$name"

    private val _state = MutableStateFlow(stateNow())
    /** What the page says for this feature. */
    val state: StateFlow<State> = _state

    val failures: Int get() = prefs.getInt(k("failures"), 0)
    val pausedUntil: Long get() = prefs.getLong(k("paused_until"), 0)

    /** The feature may ask Spotify now. */
    fun mayRun(): Boolean = pausedUntil <= now()

    private fun stateNow() = when {
        pausedUntil > now() -> State.PAUSED
        failures > 0 -> State.NOT_ANSWERING
        else -> State.OK
    }

    fun succeeded() {
        if (failures != 0 || pausedUntil != 0L) prefs.edit().remove(k("failures")).remove(k("paused_until")).apply()
        _state.value = State.OK
    }

    /** A run failed with [e]. Returns true when that paused the feature. */
    fun failed(e: Throwable): Boolean {
        val edit = prefs.edit()
        val paused = when (e) {
            is SpotifyError.RateLimited -> {
                edit.putLong(k("paused_until"), now() + e.retryAfterMs)
                log("${feature.key}: rate limited, quiet for ${e.retryAfterMs / 1000} s${if (e.quota) " (quota)" else ""}")
                true
            }
            else -> {
                val n = failures + 1
                edit.putInt(k("failures"), n)
                if (n >= LIMIT) {
                    edit.putLong(k("paused_until"), now() + DAY_MS).putInt(k("failures"), 0)
                    log("${feature.key}: $n failures running, paused for a day (last: ${e.javaClass.simpleName}: ${e.message})")
                    true
                } else {
                    log("${feature.key}: failed ($n of $LIMIT): ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
        }
        edit.commit()
        _state.value = stateNow().let { if (it == State.OK) State.NOT_ANSWERING else it }
        return paused
    }

    /** Disconnect, or the listener turning the feature off and on again. */
    fun reset() {
        prefs.edit().remove(k("failures")).remove(k("paused_until")).apply()
        _state.value = State.OK
    }

    companion object {
        const val LIMIT = 5
        const val DAY_MS = 86_400_000L
    }
}

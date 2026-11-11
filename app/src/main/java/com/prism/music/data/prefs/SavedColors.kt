package com.prism.music.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The color picker's memory (1.7.2): the last colors picked anywhere in Prism, and the ones starred to keep.
 * Shared preferences "colors" (in backups like the rest of shared_prefs).
 */
class SavedColors(context: Context) {
    private val prefs = context.getSharedPreferences("colors", Context.MODE_PRIVATE)

    val recent = MutableStateFlow(read("recent"))
    val saved = MutableStateFlow(read("saved"))

    private fun read(key: String): List<Int> =
        prefs.getString(key, null)?.split(',')?.mapNotNull { it.trim().toLongOrNull(16)?.toInt() }.orEmpty()

    private fun write(key: String, list: List<Int>) =
        prefs.edit().putString(key, list.joinToString(",") { "%08X".format(it) }).apply()

    /** A color was picked: it goes to the front of the recent ones (once), keeping [MAX_RECENT]. */
    fun used(color: Int) {
        val next = (listOf(color) + recent.value.filter { it != color }).take(MAX_RECENT)
        recent.value = next
        write("recent", next)
    }

    fun isSaved(color: Int) = color in saved.value

    fun setSaved(color: Int, on: Boolean) {
        val next = if (on) (listOf(color) + saved.value.filter { it != color }).take(MAX_SAVED) else saved.value - color
        saved.value = next
        write("saved", next)
    }

    companion object {
        const val MAX_RECENT = 12
        const val MAX_SAVED = 24
    }
}

package com.prism.music.data.prefs

/**
 * One look setting's value as text, with its kind in front: "b:1" (on/off), "i:42", "l:42", "f:0.35", "s:SHARP". That's
 * how Looks keep them (and how a shared Look's code carries them), so reading one back never guesses a kind.
 */
object LookValue {
    fun tag(v: Any): String? = when (v) {
        is Boolean -> if (v) "b:1" else "b:0"
        is Int -> "i:$v"
        is Long -> "l:$v"
        is Float -> "f:$v"
        is String -> "s:$v"
        else -> null
    }

    /** [tagged]'s value, if it's of [kind] (b, i, l, f, s); null for anything else, so a wrong kind is never stored. */
    fun untag(tagged: String, kind: Char): Any? {
        if (tagged.length < 2 || tagged[1] != ':' || tagged[0] != kind) return null
        val v = tagged.substring(2)
        return when (kind) {
            'b' -> when (v) { "1" -> true; "0" -> false; else -> null }
            'i' -> v.toIntOrNull()
            'l' -> v.toLongOrNull()
            'f' -> v.toFloatOrNull()?.takeIf { it.isFinite() }
            's' -> v.take(MAX_TEXT)
            else -> null
        }
    }

    /** The text inside [tagged], whatever its kind ("SHARP" for "s:SHARP"). */
    fun text(tagged: String?): String? = tagged?.takeIf { it.length >= 2 && it[1] == ':' }?.substring(2)

    /** Longest text a Look may set (backgrounds' list is the longest real one). */
    const val MAX_TEXT = 4000
}

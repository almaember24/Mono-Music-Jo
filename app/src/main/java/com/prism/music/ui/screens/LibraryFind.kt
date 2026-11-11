package com.prism.music.ui.screens

/** From how many entries a Library tab gets its search field (#357): a short list is read at a glance. */
internal const val LIBRARY_SEARCH_FROM = 8

/**
 * Whether what's typed is in these names, for filtering a Library tab as you type (#357): every word, anywhere, in any
 * order, without minding capitals or accents ("beyonce" finds "Beyoncé", "ac dc" finds "AC/DC"). A search of only
 * punctuation ("!!!" is a band) goes by the characters themselves.
 */
internal fun libraryMatches(query: String, vararg names: String): Boolean {
    val words = phoneNorm(query).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return names.any { it.contains(query.trim(), ignoreCase = true) }
    val hay = names.joinToString(" ") { phoneNorm(it) }
    return words.all { it in hay }
}

/** The names of an album's subtitle, without the kind it starts with ("Album • An artist • 2024": searching "album" shouldn't find them all). */
internal fun albumNames(subtitle: String, single: String): List<String> =
    subtitle.split(" • ").filter { it != "Album" && it != "Single" && it != "EP" && it != single }

/** What the slider's bubble says for a name: its first letter (a whole character, whatever the script). */
internal fun scrollLetter(name: String): String? {
    val t = name.trim()
    if (t.isEmpty()) return null
    return String(Character.toChars(t.codePointAt(0))).uppercase()
}

/**
 * What the slider's bubble says for each row, by the row's key: the first letter of the [name] the list goes by, or how far
 * down the list it is when it has none (not going by a name, or that row has no album, say).
 */
internal fun <T> scrollMarks(list: List<T>, key: (T) -> String, name: (T) -> String?): Map<String, String> =
    list.withIndex().associate { (i, t) -> key(t) to (name(t)?.let(::scrollLetter) ?: "${i + 1}") }

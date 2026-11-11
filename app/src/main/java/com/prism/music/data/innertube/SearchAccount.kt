package com.prism.music.data.innertube

/**
 * Searches and the account (#396). They go without it, so they stay out of the account's YouTube search history (#27).
 * But where YouTube Music needs Premium (South Korea), its song and video searches come back empty without the account.
 * So, signed in, such a search that comes back empty is asked once more with the account; if that finds them, the
 * searches after it go with the account for the rest of the session. Typing suggestions always go without it.
 */
internal object SearchAccount {
    /** What YouTube Music leaves empty without Premium there; [SearchFilter.TOP] is its unfiltered search. */
    private val ASKED_AGAIN = setOf(SearchFilter.SONGS, SearchFilter.VIDEOS, SearchFilter.TOP)

    /** A search asked without the account that found nothing: ask it again with the account? */
    fun askAgain(signedIn: Boolean, query: String, filter: SearchFilter, foundNothing: Boolean, nextPage: Boolean): Boolean =
        signedIn && foundNothing && !nextPage && query.isNotBlank() && filter in ASKED_AGAIN

    /** Searches go straight with the account once the account found what the search without it didn't. */
    fun withAccount(signedIn: Boolean, accountFoundMore: Boolean): Boolean = signedIn && accountFoundMore
}

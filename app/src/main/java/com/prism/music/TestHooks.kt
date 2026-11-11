package com.prism.music

/**
 * What Prism's in-app tests (app/src/androidTest, `PrismTestRunner`) set before Prism starts. Nothing sets them in a
 * real run, where they stay null.
 */
object TestHooks {
    /** Answers Prism's network requests in place of the internet (recorded YouTube and lyrics answers). */
    @Volatile var network: okhttp3.Interceptor? = null

    /**
     * What goes between a phone and a Prism TV playing along of a song (normally only YouTube songs:
     * [com.prism.music.playback.together.fromPeer]): the tests' silent tones, files on the test device, stand in for them.
     */
    @Volatile var prismTvSong: ((com.prism.music.data.model.Song) -> com.prism.music.data.model.Song?)? = null

    /** Sing's engine fails as it loads (as on a TV it doesn't run on): Sing's fallback, tested. */
    @Volatile var singEngineFails = false

    /**
     * Sing's work on a song holds on till it's let go, holding meanwhile what the player needs to load a song (as Sing held
     * a TV's only audio decoder): a song starting then sits loading ([songLoads]).
     */
    @Volatile var singPassHangs = false
    /** Sing's held work holds the player's loading now ([singPassHangs]). */
    @Volatile var singHolding = false

    /** Called as the player opens a song (its loading thread): a test holds it here. */
    fun songLoads() { while (singHolding) Thread.sleep(50) }
}

package com.prism.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.prism.music.data.prefs.AppSettings
import com.prism.music.data.prefs.BehaviorPrefs
import com.prism.music.data.prefs.PanelPlayer
import com.prism.music.ui.player.ControlsOnScroll
import com.prism.music.ui.player.scrollReveals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.9's fixes that are plain logic (the rest have in-app tests in app/src/androidTest). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class IssueFixes179Test {
    /**
     * #213: a song Load ahead saved whole counts as whole, so it plays offline. The cache learns a song's length only from
     * a request without one; Load ahead's has one, so without [AppContainer.markWhole] it stayed "not whole".
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun aSongLoadedAheadWholeCountsAsWhole() {
        val dir = java.io.File(org.robolectric.RuntimeEnvironment.getApplication().cacheDir, "cache-${System.nanoTime()}")
        val db = androidx.media3.database.StandaloneDatabaseProvider(org.robolectric.RuntimeEnvironment.getApplication())
        val cache = androidx.media3.datasource.cache.SimpleCache(dir, androidx.media3.datasource.cache.NoOpCacheEvictor(), db)
        try {
            val song = ByteArray(50_000) { (it % 251).toByte() }
            val spec = androidx.media3.datasource.DataSpec.Builder().setUri("https://example.invalid/a").setKey("song1")
                .setPosition(0).setLength(song.size.toLong()).build()
            val source = androidx.media3.datasource.cache.CacheDataSource(cache, androidx.media3.datasource.ByteArrayDataSource(song))
            androidx.media3.datasource.cache.CacheWriter(source, spec, null, null).cache()
            fun length() = androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata("song1"))
            assertEquals("all the bytes are there", song.size.toLong(), cache.getCachedBytes("song1", 0, song.size.toLong()))
            assertEquals("but the cache doesn't know it's the whole song", androidx.media3.common.C.LENGTH_UNSET.toLong(), length())
            AppContainer.markWhole(cache, "song1", song.size.toLong())
            assertEquals(song.size.toLong(), length())
            // Not all there: not marked.
            AppContainer.markWhole(cache, "song2", 1_000)
            assertEquals(androidx.media3.common.C.LENGTH_UNSET.toLong(), androidx.media3.datasource.cache.ContentMetadata.getContentLength(cache.getContentMetadata("song2")))
        } finally {
            cache.release()
        }
    }

    /** #214: scrolling back up the lyrics brings the controls only when the listener wants it, never in Full screen. */
    @Test fun scrollingUpShowsTheControlsOnlyWhenWanted() {
        var shown = 0
        var hidden = 0
        val scroll = ControlsOnScroll(onHide = { hidden++ }, onReveal = { shown++ })
        fun drag(dy: Float) {
            repeat(3) { scroll.onPreScroll(Offset(0f, dy), NestedScrollSource.UserInput) }
            kotlinx.coroutines.runBlocking { scroll.onPreFling(androidx.compose.ui.unit.Velocity.Zero) }
        }
        drag(30f) // the finger down: back up the lyrics
        assertEquals("on (as before): the controls come", 1, shown)
        scroll.reveals = false
        drag(30f)
        assertEquals("off: they stay away", 1, shown)
        drag(-30f)
        assertEquals("scrolling on down still hides them", 1, hidden)

        val base = AppSettings()
        assertTrue("on by default", scrollReveals(base))
        assertFalse(scrollReveals(base.copy(behavior = BehaviorPrefs(scrollShowsControls = false))))
        assertFalse("Full screen keeps the screen", scrollReveals(base.copy(view = base.view.copy(panelPlayer = PanelPlayer.HIDDEN))))
    }
}

package com.prism.music

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.SuccessResult
import coil3.decode.DataSource
import com.prism.music.ui.player.playerCoverRequest
import com.prism.music.ui.player.warmPlayerArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #354: "a noticeable single stutter" opening the player, as the artwork loads. The cover is got ready while the player
 * is closed, so the request the player makes finds it in memory in its first frame.
 */
@RunWith(AndroidJUnit4::class)
class PlayerCoverAheadTest {
    private fun picture(name: String): String {
        val file = File(Fixture.context.cacheDir, name)
        val bmp = Bitmap.createBitmap(1400, 1400, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return android.net.Uri.fromFile(file).toString()
    }

    private suspend fun whereFrom(url: String, fullBleed: Boolean): DataSource? {
        val context = Fixture.context
        val request = playerCoverRequest(context, url, fullBleed).newBuilder()
            .diskCachePolicy(CachePolicy.DISABLED).build()
        return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.dataSource
    }

    @Test fun theCoverIsInMemoryBeforeThePlayerOpens() = runBlocking {
        Fixture.ready()
        val url = picture("cover-ahead-${System.nanoTime()}.jpg")
        val context = Fixture.context
        // Not there at first: the first look reads the file.
        withContext(Dispatchers.Main) { warmPlayerArt(context, url, fullBleed = false, meshToo = true, blurStrength = 1f) }
        assertEquals("the player's own request finds it in memory", DataSource.MEMORY_CACHE, whereFrom(url, fullBleed = false))
    }

    @Test fun aSecondPictureNotGotReadyIsNotInMemory() = runBlocking {
        Fixture.ready()
        val url = picture("cover-cold-${System.nanoTime()}.jpg")
        val from = whereFrom(url, fullBleed = false)
        assertTrue("got: $from", from != DataSource.MEMORY_CACHE)
    }
}

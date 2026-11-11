package com.prism.music

import com.prism.music.data.db.GenreEntity
import com.prism.music.data.db.MetaDao
import com.prism.music.data.db.MotionArtEntity
import com.prism.music.data.meta.MetaRepository
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * #228 (1.8.3): opening a mix with Show genres on looked genres up from the screen's own thread, and some lookups waited
 * on Deezer and iTunes over the network there (half-second stalls on a vivo). Every network call now happens off the
 * caller's thread, and the batches still come back on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenreThreadTest {
    private val dao = object : MetaDao {
        val g = Collections.synchronizedMap(HashMap<String, GenreEntity>())
        override suspend fun genre(key: String) = g[key]
        override suspend fun genres(keys: List<String>) = keys.mapNotNull { g[it] }
        override suspend fun putGenre(e: GenreEntity) { g[e.key] = e }
        override suspend fun clearGenresBefore(before: Long) {}
        override suspend fun motion(key: String): MotionArtEntity? = null
        override suspend fun putMotion(e: MotionArtEntity) {}
    }

    @Test
    fun lookupsNeverRunOnTheCallersThread() = runBlocking {
        val main = newSingleThreadContext("fake-main")
        val mainThread = withContext(main) { Thread.currentThread() }
        val netThreads = Collections.synchronizedList(ArrayList<Thread>())
        // A slow catalogue that knows nothing: every lookup goes to the network, and each answer takes a while.
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            netThreads += Thread.currentThread()
            Thread.sleep(50)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[],"results":[]}""".toResponseBody()).build()
        }.build()
        val meta = MetaRepository(http, dao)
        val songs = listOf("Artist A", "Artist B").mapIndexed { i, a -> Song("id$i", "Song $i", listOf(ArtistRef(a))) }
        val batchThreads = ArrayList<Thread>()
        val got = HashMap<String, String>()
        withContext(main) {
            meta.resolveGenres(songs) { b -> batchThreads += Thread.currentThread(); got.putAll(b) }
            meta.resolveExtraGenres(songs, got.toMap()) { batchThreads += Thread.currentThread() }
        }
        main.close()
        assertTrue("the lookups asked the network", netThreads.isNotEmpty())
        assertTrue("no network call on the caller's thread: $netThreads", netThreads.none { it === mainThread })
        assertTrue("batches came back", batchThreads.isNotEmpty())
        assertTrue("every batch on the caller's thread", batchThreads.all { it === mainThread })
        assertEquals("every song still gets a category", songs.size, got.size)
    }
}

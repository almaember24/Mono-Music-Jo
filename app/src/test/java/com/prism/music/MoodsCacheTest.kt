package com.prism.music

import com.prism.music.data.MoodsCache
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * D40 (1.8.3): the Moods page was asked of YouTube again on every visit. It's kept for a day (memory and disk), per page
 * language, and an old one still shows when YouTube can't be reached.
 */
class MoodsCacheTest {
    @get:Rule val dir = TemporaryFolder()
    private var clock = 1_000_000L
    private var asked = 0
    private fun page(v: String) = JsonObject(mapOf("v" to JsonPrimitive(v)))
    private val parse: (JsonObject) -> List<String> = { listOf((it["v"] as JsonPrimitive).content) }
    private fun fetch(v: String): suspend () -> JsonObject = { asked++; page(v) }

    @Test fun theSecondOpenUsesTheKeptPage() = runBlocking {
        val cache = MoodsCache(dir.root) { clock }
        assertEquals(listOf("a"), cache.get("en-US", fetch("a"), parse))
        clock += 60_000
        assertEquals(listOf("a"), cache.get("en-US", fetch("b"), parse))
        assertEquals(1, asked)
        // After a restart (a new cache, same folder): from the phone, still not asked.
        assertEquals(listOf("a"), MoodsCache(dir.root) { clock }.get("en-US", fetch("b"), parse))
        assertEquals(1, asked)
    }

    @Test fun expiredOrAnotherLanguageIsFetched() = runBlocking {
        val cache = MoodsCache(dir.root) { clock }
        cache.get("en-US", fetch("a"), parse)
        assertEquals(listOf("de"), cache.get("de-DE", fetch("de"), parse))
        clock += MoodsCache.MAX_AGE_MS + 1
        assertEquals(listOf("new"), cache.get("de-DE", fetch("new"), parse))
        assertEquals(3, asked)
    }

    @Test fun offlineAnOldPageStillShows() = runBlocking {
        val cache = MoodsCache(dir.root) { clock }
        cache.get("en-US", fetch("a"), parse)
        clock += MoodsCache.MAX_AGE_MS + 1
        assertEquals(listOf("a"), cache.get("en-US", { throw IOException("offline") }, parse))
    }
}

package com.prism.music

import com.prism.music.data.Uploads
import com.prism.music.data.innertube.InnerTube
import com.prism.music.data.innertube.InnerTubeException
import com.prism.music.data.innertube.Parser
import com.prism.music.data.innertube.YouTubeMusic
import com.prism.music.data.prefs.AppSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Uploads: delete and upload (1.8.3, D49, Reddit). The requests are ytmusicapi's (mixins/uploads.py: delete_upload_entity,
 * upload_song) and the song row is shaped as its parsers read it: recorded answers, not a real account's (none was at hand;
 * the owner checks one before release). Both write to the account only from a tap, which these calls stand for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UploadWritesTest {
    /** An uploaded song's row as ytmusicapi reads it: the menu's last item is "Delete", a confirm holding the entity id. */
    private val row = """
    {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
      {"musicResponsiveListItemRenderer":{
        "flexColumns":[
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test upload"}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test artist","navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_library_privately_owned_artist_detailXYZ"}}}]}}},
          {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Test album"}]}}}
        ],
        "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:21"}]}}}],
        "menu":{"menuRenderer":{"items":[
          {"menuServiceItemRenderer":{"serviceEndpoint":{"queueAddEndpoint":{"queueTarget":{"videoId":"upVid123"}}}}},
          {"menuNavigationItemRenderer":{"text":{"runs":[{"text":"Delete song"}]},"navigationEndpoint":{"confirmDialogEndpoint":{"content":{"confirmDialogRenderer":{
            "confirmButton":{"buttonRenderer":{"command":{"musicDeletePrivatelyOwnedEntityCommand":{"entityId":"t_po_ENTITY42"}}}}}}}}}}
        ]}}
      }}
    ]}}]}}}}]}}}
    """.trimIndent()

    @Test fun aRowGivesItsDeleteId() {
        val page = Parser.uploadSongRows(InnerTube.json.parseToJsonElement(row))
        assertEquals("upVid123", page.items.single().id)
        assertEquals(mapOf("upVid123" to "t_po_ENTITY42"), page.entities)
        // A row without the Delete item still lists, with nothing to delete it by.
        val bare = row.replace("musicDeletePrivatelyOwnedEntityCommand", "somethingElse")
        val p2 = Parser.uploadSongRows(InnerTube.json.parseToJsonElement(bare))
        assertEquals(1, p2.items.size)
        assertTrue(p2.entities.isEmpty())
    }

    @Test fun deleteAsksAsYtmusicapiDoes() = runBlocking {
        val yt = FakeYouTube { _, _ -> 200 to "{}" }
        yt.ytm.deleteUpload("t_po_ENTITY42")
        assertEquals("t_po_ENTITY42", yt.sentTo("music/delete_privately_owned_entity").single()["entityId"]!!.jsonPrimitive.content)
        // An uploaded album by its page id: the page part comes off.
        yt.ytm.deleteUpload("FEmusic_library_privately_owned_release_detailALBUM7")
        assertEquals("ALBUM7", yt.sentTo("music/delete_privately_owned_entity").last()["entityId"]!!.jsonPrimitive.content)
    }

    @Test fun aRefusedDeleteSaysSo() {
        val yt = FakeYouTube { _, _ -> 200 to """{"error":{"message":"Not yours"}}""" }
        val e = runCatching { runBlocking { yt.ytm.deleteUpload("x") } }.exceptionOrNull()
        assertTrue("$e", e is InnerTubeException && e.message == "Not yours")
        val yt2 = FakeYouTube { _, _ -> 403 to """{"error":{"message":"Forbidden"}}""" }
        assertTrue(runCatching { runBlocking { yt2.ytm.deleteUpload("x") } }.isFailure)
    }

    private class Sent(val request: Request, val body: ByteArray)

    private fun uploader(signedIn: Boolean = true, startCode: Int = 200, address: String? = "https://upload.youtube.com/upload/usermusic/http?upload_id=abc", sendCode: Int = 200): Pair<YouTubeMusic, List<Sent>> {
        val sent = mutableListOf<Sent>()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val buf = okio.Buffer().also { req.body?.writeTo(it) }
            sent += Sent(req, buf.readByteArray())
            val start = req.header("X-Goog-Upload-Command") == "start"
            val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (start) startCode else sendCode).message("fake")
                .body("".toResponseBody("text/plain".toMediaType()))
            if (start && address != null) b.header("X-Goog-Upload-URL", address)
            b.build()
        }).build()
        val settings = AppSettings(cookie = if (signedIn) "SAPISID=abc; __Secure-3PAPISID=abc" else "", authUser = 1)
        return YouTubeMusic(InnerTube(http, { settings })) to sent
    }

    @Test fun uploadAsksAsYtmusicapiDoes() = runBlocking {
        val (ytm, sent) = uploader()
        val bytes = ByteArray(2048) { (it % 251).toByte() }
        ytm.uploadSong("My Song.flac", bytes.size.toLong(), bytes.toRequestBody("application/x-www-form-urlencoded;charset=utf-8".toMediaType()))
        assertEquals(2, sent.size)
        val (start, send) = sent
        assertEquals("https://upload.youtube.com/upload/usermusic/http?authuser=1", start.request.url.toString())
        assertEquals("start", start.request.header("X-Goog-Upload-Command"))
        assertEquals("2048", start.request.header("X-Goog-Upload-Header-Content-Length"))
        assertEquals("resumable", start.request.header("X-Goog-Upload-Protocol"))
        assertEquals("filename=My Song.flac", String(start.body))
        // As the account: its cookie and the browser's authorization, as ytmusicapi's browser auth.
        assertTrue(start.request.header("Authorization")!!.startsWith("SAPISIDHASH "))
        assertNotNull(start.request.header("Cookie"))
        assertEquals("1", start.request.header("X-Goog-AuthUser"))
        // Then the file, to the address the first answer gave.
        assertEquals("https://upload.youtube.com/upload/usermusic/http?upload_id=abc", send.request.url.toString())
        assertEquals("upload, finalize", send.request.header("X-Goog-Upload-Command"))
        assertEquals("0", send.request.header("X-Goog-Upload-Offset"))
        assertTrue(bytes.contentEquals(send.body))
    }

    @Test fun aRefusedUploadSaysSoAndSendsNoFile() {
        val body = ByteArray(10).toRequestBody("application/octet-stream".toMediaType())
        val (refused, sent) = uploader(startCode = 401)
        assertTrue(runCatching { runBlocking { refused.uploadSong("a.mp3", 10, body) } }.isFailure)
        assertEquals("no file after a refusal", 1, sent.size)
        val (noAddress, sent2) = uploader(address = null)
        assertTrue(runCatching { runBlocking { noAddress.uploadSong("a.mp3", 10, body) } }.isFailure)
        assertEquals(1, sent2.size)
        val (badSend, _) = uploader(sendCode = 500)
        assertTrue(runCatching { runBlocking { badSend.uploadSong("a.mp3", 10, body) } }.isFailure)
        val (signedOut, sent3) = uploader(signedIn = false)
        assertTrue(runCatching { runBlocking { signedOut.uploadSong("a.mp3", 10, body) } }.isFailure)
        assertTrue("nothing goes out signed out", sent3.isEmpty())
    }

    @Test fun onlyWhatYouTubeMusicTakes() {
        assertNull(Uploads.uploadable("song.mp3", 5_000_000))
        assertNull(Uploads.uploadable("Song.FLAC", 299_000_000))
        assertNull(Uploads.uploadable("a.m4a", 1))
        assertNotNull("not a type it takes", Uploads.uploadable("song.wav", 5_000_000))
        assertNotNull("no extension", Uploads.uploadable("song", 5_000_000))
        assertNotNull("300 MB and over", Uploads.uploadable("song.flac", Uploads.UPLOAD_MAX_BYTES))
        assertNotNull("size unknown", Uploads.uploadable("song.mp3", -1))
    }
}

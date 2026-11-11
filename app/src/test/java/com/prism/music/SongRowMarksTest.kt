package com.prism.music

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.download.DownloadInfo
import com.prism.music.download.VideoDownload
import com.prism.music.ui.components.LocalRowMarks
import com.prism.music.ui.components.SongRow
import com.prism.music.ui.components.rememberRowMarks
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.PrismTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.8.1's long-playlist fling: every song row coming into view started its own collectors of the likes, the downloads,
 * #315's stand-ins and the saved videos (four per row). They're collected once for the app now ([LocalRowMarks]), and
 * each row still shows its own marks the moment they change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SongRowMarksTest {
    @get:Rule val compose = createComposeRule()

    private val songs = (0 until 1_100).map { Song("s$it", "Song $it", listOf(ArtistRef("Artist ${it % 40}"))) }
    private val liked = MutableStateFlow<Set<String>>(emptySet())
    private val downloads = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val standIns = MutableStateFlow<Map<String, String>>(emptyMap())
    private val videos = MutableStateFlow<Map<String, VideoDownload>>(emptyMap())
    private lateinit var list: LazyListState

    /** A 1,100-song page; [perRow]: each row collects the marks itself (as every row did before). */
    private fun page(perRow: Boolean) {
        compose.setContent {
            CompositionLocalProvider(LocalAppSettings provides com.prism.music.data.prefs.AppSettings()) {
                PrismTheme(com.prism.music.data.prefs.AppSettings(), artworkColor = null) {
                    val shared = rememberRowMarks(liked, downloads, standIns, videos)
                    CompositionLocalProvider(LocalRowMarks provides shared) {
                        list = rememberLazyListState()
                        LazyColumn(Modifier.size(360.dp, 640.dp).testTag("page"), state = list) {
                            itemsIndexed(songs, key = { i, s -> "${s.id}:$i" }) { _, s ->
                                if (perRow) OwnMarks { SongRow(s) {} } else SongRow(s) {}
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun OwnMarks(content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalRowMarks provides rememberRowMarks(liked, downloads, standIns, videos), content = content)

    private fun fling(times: Int) {
        repeat(times) { compose.onNodeWithTag("page").performTouchInput { swipeUp() }; compose.waitForIdle() }
        repeat(times) { compose.onNodeWithTag("page").performTouchInput { swipeDown() }; compose.waitForIdle() }
    }

    private fun collectors() = listOf(liked, downloads, standIns, videos).map { it.subscriptionCount.value }

    @Test fun rowsCollectingTheirOwnMarksStartCollectorsPerRow() {
        // The measure itself: rows that collect for themselves hold one collector of each flow per row in view.
        page(perRow = true)
        fling(6)
        val shown = list.layoutInfo.visibleItemsInfo.size
        assertTrue("several rows in view ($shown)", shown > 5)
        collectors().forEach { assertTrue("one collector per row and the page's own: $it", it > shown) }
    }

    @Test fun aLongListCollectsTheMarksOnceWhileFlungThrough() {
        page(perRow = false)
        fling(6)
        assertTrue("the list moved and came back", list.firstVisibleItemIndex == 0)
        assertEquals("one collector of each, whatever the rows", listOf(1, 1, 1, 1), collectors())
    }

    @Test fun eachRowStillShowsItsOwnMarksAtOnce() {
        page(perRow = false)
        val likedMark = com.prism.music.tr(R.string.liked)
        val downloadedMark = com.prism.music.tr(R.string.downloaded)
        val videoMark = com.prism.music.tr(R.string.video_saved)
        compose.onAllNodesWithContentDescription(likedMark).assertCountEquals(0)
        liked.value = setOf("s1", "s3")
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(likedMark).assertCountEquals(2)
        downloads.value = mapOf("s2" to DownloadInfo(songs[2], Download.STATE_COMPLETED, 100f, 1L), "s5" to DownloadInfo(songs[5], Download.STATE_DOWNLOADING, 40f, 1L))
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(downloadedMark).assertCountEquals(1)
        // #315: a video row downloaded as its song (s0 stands in for s2) shows as downloaded too.
        standIns.value = mapOf("s0" to "s2")
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(downloadedMark).assertCountEquals(2)
        videos.value = mapOf("s4" to VideoDownload("v4", Download.STATE_COMPLETED, 100f, 1L))
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(videoMark).assertCountEquals(1)
        liked.value = emptySet()
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription(likedMark).assertCountEquals(0)
    }
}

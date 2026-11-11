package com.prism.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.prism.music.data.model.ArtistRef
import com.prism.music.data.model.Song
import com.prism.music.data.seasonal.HolidayMixes
import com.prism.music.data.seasonal.SeasonalMixes
import com.prism.music.ui.tv.TvHolidayAhead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections

/** The TV's Home (1.8.0): its holiday row's mixes are made ahead while the row is shown, as the phone's are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TvHolidayAheadTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.systemDefault()
    private val december = Clock.fixed(LocalDateTime.of(2026, 12, 10, 12, 0).atZone(zone).toInstant(), zone)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shownMixes = HolidayMixes.on(december.instant().atZone(zone).toLocalDate())

    @After fun stop() = scope.cancel()

    @Test fun theRowsMixesAreMadeAheadOnlyWhileItsShown() {
        val made = Collections.synchronizedList(ArrayList<String>())
        val seasonal = SeasonalMixes(
            tmp.root, scope,
            { m, _ -> made += m.key; (1..45).map { Song("${m.key}-$it", "Song $it", listOf(ArtistRef("Artist")), durationSec = 200) } },
            {}, { true }, aheadAfterMs = 0,
        ).also { it.clock = december }
        var shown by mutableStateOf(false)
        compose.setContent { TvHolidayAhead(seasonal, shown, shownMixes) }
        compose.waitForIdle()
        Thread.sleep(300)
        assertTrue("not while the row isn't shown", made.isEmpty())
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        runCatching { compose.waitUntil(10_000) { made.size == shownMixes.size } }
            .onFailure { throw AssertionError("made ${made.toList()} of ${shownMixes.map { it.key }}", it) }
        assertEquals("in the row's order, one each", shownMixes.map { it.key }, made.toList())
    }
}

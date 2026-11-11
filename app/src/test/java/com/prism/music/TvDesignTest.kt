package com.prism.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.prism.music.data.lyrics.LyricLine
import com.prism.music.data.lyrics.LyricWord
import com.prism.music.data.prefs.AppSettings
import com.prism.music.ui.theme.PrismIcons
import com.prism.music.ui.tv.TvAmbientScreen
import com.prism.music.ui.tv.TvArtistScreen
import com.prism.music.ui.tv.TvCollectionScreen
import com.prism.music.ui.tv.TvHomeScreen
import com.prism.music.ui.tv.TvInfoCard
import com.prism.music.ui.tv.TvItem
import com.prism.music.ui.tv.TvLibraryScreen
import com.prism.music.ui.tv.TvLightChoice
import com.prism.music.ui.tv.TvLyricsStyleChoice
import com.prism.music.ui.tv.TvLyrics
import com.prism.music.ui.tv.TvLyricsScreen
import com.prism.music.ui.tv.TvNavFrame
import com.prism.music.ui.tv.TvNow
import com.prism.music.ui.tv.TvNowPlayingScreen
import com.prism.music.ui.tv.TvPairScreen
import com.prism.music.ui.tv.TvQueuePanel
import com.prism.music.ui.tv.TvSearchScreen
import com.prism.music.ui.tv.TvSection
import com.prism.music.ui.tv.TvSetting
import com.prism.music.ui.tv.TvSettingGroup
import com.prism.music.ui.tv.TvSettingsScreen
import com.prism.music.ui.tv.TvShelf
import com.prism.music.ui.tv.TvSingScreen
import com.prism.music.ui.tv.TvSong
import com.prism.music.ui.tv.TvTheme
import com.prism.music.ui.tv.TvUpdateBanner
import com.prism.music.ui.tv.TvVideoControls
import com.prism.music.ui.tv.TvWelcomeScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Prism TV's design (1.8.0 slice 2, for the owner's approval): every TV screen drawn at 1920×1080 with sample music,
 * focus where it opens, and the remote's path through it, into build/tv-design/ (PNGs + design.json with each focus
 * step's place on the screen, for the review page). Covers are made up (gradients), as are the songs and artists.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class TvDesignTest {
    @get:Rule val compose = createComposeRule()

    private val out = File("build/tv-design").apply { mkdirs() }

    /** A focus step for the review page: the node [tag], the key that gets there from the step before, and a note. */
    private class Step(val tag: String, val key: String, val note: String)

    private val shots = ArrayList<JsonObject>()

    // ------------------------------------------------------------------ made-up covers

    private val palettes = listOf(
        intArrayOf(0xFFFF4D5E.toInt(), 0xFF7C5CFF.toInt()), intArrayOf(0xFF36B8FF.toInt(), 0xFF3DDC97.toInt()),
        intArrayOf(0xFFFFD84D.toInt(), 0xFFFF9F43.toInt()), intArrayOf(0xFF1F2A44.toInt(), 0xFF5A8BFF.toInt()),
        intArrayOf(0xFF3DDC97.toInt(), 0xFF0E3B2E.toInt()), intArrayOf(0xFFFF9F43.toInt(), 0xFF40122A.toInt()),
        intArrayOf(0xFFB06CFF.toInt(), 0xFF101828.toInt()), intArrayOf(0xFFE8E2D0.toInt(), 0xFF8A6A4A.toInt()),
    )

    /** A cover for "design://<n>": two colors, a diagonal light and a ring or stripes, so rows look like music. */
    private fun cover(url: String): Bitmap {
        val n = url.substringAfterLast('/').toIntOrNull() ?: url.hashCode()
        val (a, b) = palettes[Math.floorMod(n, palettes.size)].let { it[0] to it[1] }
        val s = 360
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(), a, b, Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (Math.floorMod(n, 3)) {
            0 -> { p.style = Paint.Style.STROKE; p.strokeWidth = 18f; p.color = 0x66FFFFFF; c.drawCircle(s * 0.62f, s * 0.42f, s * 0.26f, p) }
            1 -> { p.color = 0x33FFFFFF; for (i in 0 until 6) c.drawRect(0f, s * (0.12f + i * 0.14f), s.toFloat(), s * (0.17f + i * 0.14f), p) }
            else -> { p.shader = RadialGradient(s * 0.3f, s * 0.7f, s * 0.5f, 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p) }
        }
        return bmp
    }

    private fun fakeCovers() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val loader = ImageLoader.Builder(ctx)
            .components {
                add(Interceptor { chain: Interceptor.Chain ->
                    SuccessResult(cover(chain.request.data.toString()).asImage(), chain.request, DataSource.MEMORY) as ImageResult
                })
            }
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    // ------------------------------------------------------------------ sample music

    private fun art(n: Int) = "design://$n"
    private val songs = listOf(
        TvSong("1", "Glass Horizon", "Mira Vale", "3:41", art(1)), TvSong("2", "Northern Static", "The Lanterns", "4:05", art(2)),
        TvSong("3", "Paper Moons", "Ines Calder", "2:58", art(3)), TvSong("4", "Slow Signal", "Harbor Lights", "3:22", art(4)),
        TvSong("5", "Velvet Hours", "Mira Vale", "3:57", art(5)), TvSong("6", "Coastline", "Oslo Rain", "4:31", art(6)),
        TvSong("7", "Afterglow Drive", "Kite Theory", "3:10", art(7)), TvSong("8", "Low Tide", "Ines Calder", "3:48", art(8)),
        TvSong("9", "Satellites", "The Lanterns", "4:12", art(9)), TvSong("10", "Ember", "Juno Park", "2:49", art(10)),
    )
    private fun items(from: Int, kind: TvItem.Kind, vararg names: Pair<String, String?>) = names.mapIndexed { i, (t, s) -> TvItem("$from$i", t, s, art(from + i), kind) }
    private val shelves = listOf(
        TvShelf("Quick picks", items(1, TvItem.Kind.SONG, "Glass Horizon" to "Mira Vale", "Northern Static" to "The Lanterns", "Paper Moons" to "Ines Calder", "Slow Signal" to "Harbor Lights", "Velvet Hours" to "Mira Vale", "Coastline" to "Oslo Rain", "Ember" to "Juno Park")),
        TvShelf("Speed dial", items(20, TvItem.Kind.MIX, "My Supermix" to "Mix", "Late night" to "Playlist", "Mira Vale" to "Artist", "Road trip" to "Playlist", "Focus" to "Mix", "Liked songs" to "Playlist")),
        TvShelf("Your mixes", items(30, TvItem.Kind.MIX, "Mix 1" to "Mira Vale, The Lanterns and more", "Mix 2" to "Oslo Rain, Juno Park and more", "Discover mix" to "New to you", "Replay mix" to "Your favorites", "Chill mix" to "Kite Theory and more")),
        TvShelf("Recently played", items(40, TvItem.Kind.ALBUM, "Tidewater" to "Album · Oslo Rain", "Mira Vale" to "Artist", "Bright Static" to "Album · The Lanterns", "Weekend" to "Playlist", "Ember" to "Single · Juno Park")),
    )
    private val now = TvNow(
        "Glass Horizon", "Mira Vale", "Tidewater", art(1), 0.38f, "1:24", "3:41", playing = true, liked = true,
        hasLyrics = true, hasVideo = true, next = "Northern Static · The Lanterns",
    )
    private val lyrics = run {
        fun line(t: Long, text: String) = LyricLine(t, text, t + 3600, text.split(" ").let { w ->
            var at = t; w.map { word -> LyricWord(at, at + 3600 / w.size, "$word ").also { at += 3600 / w.size } }
        })
        TvLyrics(
            listOf(
                line(70_000, "We drew the line where the water ends"), line(76_000, "Counting the lights on the far side"),
                line(82_000, "Every wave a little closer now"), line(88_000, "Glass horizon, hold me steady"),
                line(94_000, "I can see the morning from here"), line(100_000, "And it's brighter than we said"),
                line(106_000, "So we wait for the tide to turn"),
            ),
            current = 2, positionMs = 83_800, source = "LRCLIB",
        )
    }

    // ------------------------------------------------------------------ drawing

    /** The screen being drawn: the rule takes one setContent per test, so screens are swapped through this. */
    private var screen by mutableStateOf<Pair<String, @Composable () -> Unit>?>(null)
    /** The settings drawn with (Settings → Colors' White light is drawn with its own). */
    private var look by mutableStateOf(AppSettings())
    private var started = false

    private fun shoot(id: String, title: String, about: String, steps: List<Step>, content: @Composable () -> Unit) {
        if (!started) {
            started = true
            // As the TV's shell draws them: on the Aura of what's playing (here the made-up cover of "Glass Horizon").
            compose.setContent {
                TvTheme(look) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF060609))) {
                        com.prism.music.ui.tv.TvAuraFrom(art(1))
                        screen?.let { (key, draw) -> androidx.compose.runtime.key(key) { draw() } }
                    }
                }
            }
        }
        screen = id to content
        repeat(12) { compose.mainClock.advanceTimeBy(120); Thread.sleep(40) }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$id.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val placed = steps.mapIndexed { i, s ->
            val node = compose.onAllNodes(hasTestTag(s.tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            val r = node?.boundsInRoot
            JsonObject(
                mapOf(
                    "n" to JsonPrimitive(i + 1), "tag" to JsonPrimitive(s.tag), "key" to JsonPrimitive(s.key), "note" to JsonPrimitive(s.note),
                    "x" to JsonPrimitive(r?.left ?: -1f), "y" to JsonPrimitive(r?.top ?: -1f), "w" to JsonPrimitive(r?.width ?: 0f), "h" to JsonPrimitive(r?.height ?: 0f),
                ),
            )
        }
        shots += JsonObject(mapOf("id" to JsonPrimitive(id), "title" to JsonPrimitive(title), "about" to JsonPrimitive(about), "steps" to JsonArray(placed)))
    }

    @Composable
    private fun inShell(section: TvSection, content: @Composable () -> Unit) = TvNavFrame(section, {}, focusNav = false, content = content)

    @Test fun design() {
        fakeCovers()
        val clock = mutableLongStateOf(lyrics.positionMs)
        val noop = {}

        shoot(
            "home", "Home, while music plays",
            "The side navigation stays small at the left edge; Left from any row's first card opens it. What's playing sits on top; each row remembers its place.",
            listOf(
                Step("tv_home/player", "opens here", "Now playing opens the player"),
                Step("tv_home/lyrics", "→", "straight to big lyrics"),
                Step("tv_home/row0/0", "↓", "rows below; ← → along a row"),
                Step("tv_home/row1/0", "↓", "Speed dial"),
                Step("tv_nav/HOME", "← (from a row's start)", "the side navigation opens"),
            ),
        ) { inShell(TvSection.HOME) { TvHomeScreen("Good evening", now, shelves, noop, noop, noop, { _, _ -> }, accent = Color(0xFF7C5CFF)) } }

        shoot(
            "home-idle", "Home, nothing playing, an update ready",
            "The update banner comes first when there is one; with nothing playing, focus opens on the first Quick pick.",
            listOf(
                Step("tv_home/row0/0", "opens here", "OK plays it"),
                Step("tv_home/update", "↑", "Update downloads and installs Prism (Android asks once)"),
            ),
        ) { inShell(TvSection.HOME) { TvHomeScreen("Good evening", null, shelves, noop, noop, noop, { _, _ -> }, banner = { TvUpdateBanner("1.8.1", noop) }) } }

        shoot(
            "nav", "The side navigation, open",
            "Opens wide while it has focus: Home, Search, Library, Settings. Right goes back into the page; Back on Home leaves Prism (the music keeps playing).",
            listOf(
                Step("tv_nav/HOME", "←", "from the page"),
                Step("tv_nav/SEARCH", "↓", ""), Step("tv_nav/LIBRARY", "↓", ""), Step("tv_nav/SETTINGS", "↓", ""),
            ),
        ) { TvNavFrame(TvSection.HOME, {}, focusNav = true) { TvHomeScreen("Good evening", null, emptyList(), noop, noop, noop, { _, _ -> }) } }

        shoot(
            "player", "Now playing",
            "Full screen. Focus opens on play/pause; the remote's media keys work everywhere. Back returns to where it was opened from.",
            listOf(
                Step("tv_player/playpause", "opens here", "OK pauses / plays"),
                Step("tv_player/next", "→", "next song (previous and shuffle to the left)"),
                Step("tv_player/repeat", "→", ""),
                Step("tv_player/lyrics", "↓", "the second row: lyrics, queue, video, like, more"),
                Step("tv_player/queue", "→", ""),
                Step("tv_player/video", "→", "the music video, full screen"),
                Step("tv_player/more", "→ →", "Sing, sleep timer, song info, go to album / artist"),
            ),
        ) { TvNowPlayingScreen(now, noop, noop, noop, noop, noop, noop, noop, noop, noop, noop, accent = Color(0xFFFF4D5E)) }

        shoot(
            "lyrics", "Lyrics",
            "Big, in the listener's lyrics style (here Prism's own, word by word). Up/Down pick a line, OK plays from it; Back to the player.",
            listOf(Step("tv_lyrics/lines", "opens here", "the lyrics are one focus: ↑ ↓ pick a line, OK plays from there")),
        ) { TvLyricsScreen(now, lyrics, clock, accent = Color(0xFFFF4D5E)) }

        shoot(
            "queue", "The queue, over the player",
            "Opens on the next song. OK plays it; hold OK for play next / remove; Back or ← closes it.",
            listOf(Step("tv_queue/1", "opens here", "the next song"), Step("tv_queue/2", "↓", "")),
        ) {
            Box(Modifier.fillMaxSize()) {
                TvNowPlayingScreen(now, noop, noop, noop, noop, noop, noop, noop, noop, noop, noop, accent = Color(0xFFFF4D5E))
                TvQueuePanel("Mix 1", songs, 0, {})
            }
        }

        shoot(
            "video", "A music video",
            "The video fills the screen; any key shows these controls for a few seconds, every one reachable: the seek line (← → move 10 s), previous and next, Lyrics, the queue, back to the song, ⋯.",
            listOf(
                Step("tv_video/playpause", "opens here", ""), Step("tv_video/seek", "↑", "← → seek 10 s"), Step("tv_video/next", "↓ →", ""),
                Step("tv_video/queue", "→", "the queue, over the video"), Step("tv_video/song", "→", "back to the song"), Step("tv_video/more", "→", "album, artist, Sing"),
            ),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF203A43), Color(0xFF2C5364), Color(0xFF0F2027))))) {
                TvVideoControls(now, noop, noop, noop, noop, Modifier.align(Alignment.BottomCenter), onSeek = {}, onLyrics = noop, onQueue = noop, onMore = noop)
            }
        }

        shoot(
            "sing", "Sing (karaoke night)",
            "Vocals out, lyrics as large as they go, the key beside them. Only on TVs that pass Sing's speed check; on slower ones Sing says the TV isn't fast enough.",
            listOf(Step("tv_lyrics/lines", "opens here", "as on the lyrics screen")),
        ) { TvSingScreen(now, lyrics, clock, "+2", accent = Color(0xFF7C5CFF)) }

        shoot(
            "search", "Search, with results",
            "Focus opens on the search line (OK opens the TV's keyboard). The microphone, and the remote's own voice button, search by voice.",
            listOf(
                Step("tv_search/field", "opens here", "OK: the TV keyboard"),
                Step("tv_search/voice", "→", "search by voice"),
                Step("tv_search/top", "↓", "the top result"),
                Step("tv_search/song/0", "→", "songs"),
                Step("tv_search/row0/0", "↓ …", "albums, artists, playlists, videos in rows"),
            ),
        ) {
            inShell(TvSection.SEARCH) {
                TvSearchScreen(
                    "mira", noop, noop, emptyList(), {}, TvItem("a", "Mira Vale", "Artist", art(11), TvItem.Kind.ARTIST), songs.take(5),
                    listOf(
                        TvShelf("Albums", items(50, TvItem.Kind.ALBUM, "Tidewater" to "Mira Vale · 2025", "Glass" to "Mira Vale · 2023", "Velvet Hours" to "Single · 2026", "Live at the Pier" to "Mira Vale · 2024")),
                        TvShelf("Artists", items(60, TvItem.Kind.ARTIST, "Mira Vale" to null, "Mira & The Tide" to null, "Vale Collective" to null)),
                    ),
                    {}, {},
                )
            }
        }

        shoot(
            "search-empty", "Search, before typing",
            "Recent searches under the search line; OK runs one again.",
            listOf(Step("tv_search/field", "opens here", ""), Step("tv_search/recent/0", "↓", "a recent search")),
        ) { inShell(TvSection.SEARCH) { TvSearchScreen("", noop, noop, listOf("mira vale", "lo-fi", "the lanterns", "road trip songs"), {}, null, emptyList(), emptyList(), {}, {}) } }

        shoot(
            "library", "Library",
            "Tabs along the top; moving along them switches the grid. Down enters the grid, which remembers its place per tab.",
            listOf(Step("tv_library/tab/0", "opens here", "Playlists"), Step("tv_library/tab/1", "→", "Albums"), Step("tv_library/grid/0", "↓", "the grid")),
        ) {
            inShell(TvSection.LIBRARY) {
                TvLibraryScreen(
                    listOf("Playlists", "Albums", "Artists", "Liked songs", "History"), 0, {},
                    items(70, TvItem.Kind.PLAYLIST, "Liked songs" to "1,253 songs", "Everything I like" to "1,100 songs", "Road trip" to "86 songs", "Late night" to "41 songs", "Focus" to "120 songs", "Weekend" to "33 songs", "Running" to "58 songs", "Rainy day" to "27 songs", "Party" to "90 songs", "Sunday" to "18 songs"),
                    {},
                )
            }
        }

        shoot(
            "album", "An album or playlist",
            "Big cover, Play and Shuffle; the songs on the right. View and play only (editing stays on the phone).",
            listOf(Step("tv_collection/play", "opens here", ""), Step("tv_collection/shuffle", "→", ""), Step("tv_collection/song/0", "→", "the songs; ↑ ↓ through them")),
        ) { TvCollectionScreen("Tidewater", "Mira Vale · Album · 2025 · 10 songs, 37 min", art(1), songs, noop, noop, {}, playing = 0, accent = Color(0xFFFF4D5E)) }

        shoot(
            "artist", "An artist",
            "Picture and name with Play, Shuffle and Radio; then popular songs, albums, singles, videos and artists fans also like.",
            listOf(Step("tv_artist/play", "opens here", ""), Step("tv_artist/radio", "→ →", ""), Step("tv_artist/row0/0", "↓", "rows below")),
        ) {
            TvArtistScreen(
                "Mira Vale", "2.4M monthly listeners", art(11),
                listOf(
                    TvShelf("Popular", items(80, TvItem.Kind.SONG, "Glass Horizon" to "312M plays", "Velvet Hours" to "98M plays", "Low Tide" to "54M plays", "Paper Moons" to "40M plays", "Coastline" to "22M plays")),
                    TvShelf("Albums", items(50, TvItem.Kind.ALBUM, "Tidewater" to "2025", "Glass" to "2023", "Live at the Pier" to "2024")),
                ),
                noop, noop, noop, { _, _ -> }, accent = Color(0xFF7C5CFF),
            )
        }

        shoot(
            "ambient", "Ambient screen",
            "After a few minutes without the remote while music plays, the screen dims to this instead of the TV's own screensaver covering the lyrics. Everything drifts slowly (burn-in safe); any key brings back where you were. Also Prism's system screensaver, and the phone's desk mode (#265, off by default).",
            emptyList(),
        ) { TvAmbientScreen(now, "Every wave a little closer now", "21:47", drift = DpOffset(6.dp, -4.dp), accent = Color(0xFFFF4D5E)) }

        val groups = listOf(
            TvSettingGroup("Account", PrismIcons.Person, listOf(TvSetting("Signed in as Alex", "alex@example.com"), TvSetting("Sign in with your phone", "Send your account from Prism on your phone"), TvSetting("Use your phone as a remote", "Pair a phone"), TvSetting("Sign out"))),
            TvSettingGroup("Language", PrismIcons.Translate, emptyList()),
            TvSettingGroup("Sound", PrismIcons.Equalizer, listOf(TvSetting("Sound quality", "High"), TvSetting("Same loudness for every song", on = true, info = "Songs play at about the same loudness, so a quiet one doesn't make you reach for the remote."))),
            TvSettingGroup("Lyrics", PrismIcons.Lyrics, emptyList()),
            TvSettingGroup("Sing", PrismIcons.Mic, emptyList()),
            TvSettingGroup("Ambient screen", PrismIcons.Moon, emptyList()),
            TvSettingGroup("Text size", PrismIcons.Visible, emptyList()),
            TvSettingGroup("About and updates", PrismIcons.Info, emptyList()),
        )
        shoot(
            "settings", "Settings",
            "The short TV set, on the phone's new settings layout: groups on the left, a group's settings on the right. Explanations are behind ⓘ, one step Right of their setting.",
            listOf(
                Step("tv_settings/group/2", "opens here", "moving along the groups shows each one"),
                Step("tv_settings/setting/0", "→", "into the settings"),
                Step("tv_settings/setting/1", "↓", "OK flips a switch"),
                Step("tv_settings/info/1", "→", "ⓘ: the explanation"),
            ),
        ) { inShell(TvSection.SETTINGS) { TvSettingsScreen(groups, 2, {}, {}, {}) } }

        shoot(
            "settings-info", "A setting's ⓘ",
            "The explanation over the settings; Back or OK closes it.",
            emptyList(),
        ) {
            Box(Modifier.fillMaxSize()) {
                inShell(TvSection.SETTINGS) { TvSettingsScreen(groups, 2, {}, {}, {}, focusedSetting = 1) }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    TvInfoCard("Same loudness for every song", "Songs play at about the same loudness, so a quiet one doesn't make you reach for the remote.")
                }
            }
        }

        shoot(
            "welcome", "First run: welcome",
            "Prism opens in the TV's language. Signing in with the phone avoids typing a Google sign-in with a remote.",
            listOf(Step("tv_welcome/phone", "opens here", ""), Step("tv_welcome/skip", "→", "continue without an account")),
        ) { TvWelcomeScreen(noop, noop) }

        shoot(
            "pair", "Sign in with your phone",
            "The TV shows a code (and a QR code); the phone enters or scans it. The link is encrypted (X25519 + AES-GCM) and the phone asks before sending the account. Also used for \"Use your phone as a remote\".",
            listOf(Step("tv_pair/cancel", "opens here", "the screen moves on by itself once the phone confirms")),
        ) { TvPairScreen("Sign in with your phone", "482913", "prism://pair?tv=Living%20room%20TV&code=482913", "Living room TV", "Waiting for your phone…", noop) }

        shoot(
            "colors", "Settings → Colors",
            "The highlights' light: Spectrum (as Prism comes) or White light (monochrome). The phone's own Color setting, so both agree.",
            listOf(Step("tv_light/spectrum", "opens here", ""), Step("tv_light/white", "→", "white ring and lines")),
        ) {
            Box(Modifier.fillMaxSize()) {
                inShell(TvSection.SETTINGS) { TvSettingsScreen(groups, 0, {}, {}, {}) }
                TvLightChoice(com.prism.music.data.prefs.LightStyle.SPECTRUM) {}
            }
        }

        // Held part-way through the sung line (Reduce motion), so the picture is still.
        look = AppSettings(reduceMotion = true)
        shoot(
            "lyrics-style", "Settings → Lyrics style",
            "The phone's lyrics styles, picked as the phone's \"Start from\"; the focused one's example on the left, its middle line sung in that style.",
            listOf(Step("tv_lyrics_style/prism", "opens here", "the style in use"), Step("tv_lyrics_style/glow", "↓ ↓ ↓", "Glow's example")),
        ) {
            Box(Modifier.fillMaxSize()) {
                inShell(TvSection.SETTINGS) { TvSettingsScreen(groups, 0, {}, {}, {}) }
                TvLyricsStyleChoice(com.prism.music.data.prefs.LyricsStyle.PRISM) {}
            }
        }

        // White light: the same Home with white highlights (the phone's Color → White light).
        look = AppSettings(lightStyle = com.prism.music.data.prefs.LightStyle.WHITE)
        shoot(
            "home-white", "Home in White light",
            "Settings → Colors → White light: the focus ring, progress line and marks in white; covers keep their color.",
            listOf(Step("tv_home/player", "opens here", "")),
        ) { inShell(TvSection.HOME) { TvHomeScreen("Good evening", now, shelves, noop, noop, noop, { _, _ -> }, accent = Color(0xFF7C5CFF)) } }
        look = AppSettings()

        File(out, "design.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(shots)))
        assertTrue(File(out, "home.png").isFile)
    }
}

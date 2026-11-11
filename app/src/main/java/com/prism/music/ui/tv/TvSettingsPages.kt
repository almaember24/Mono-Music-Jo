package com.prism.music.ui.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.prism.music.AppLanguage
import com.prism.music.BuildConfig
import com.prism.music.R
import com.prism.music.data.prefs.AudioQuality
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.launch

/** One setting's action on the TV: a switch flips, a choice opens its list. */
private sealed interface TvSettingAction {
    data class Flip(val set: (Boolean) -> Unit, val now: Boolean) : TvSettingAction
    data class Choose(val options: List<Pair<String, () -> Unit>>) : TvSettingAction
    data class Run(val run: () -> Unit) : TvSettingAction
}

/** A setting with what OK does on it. */
private class TvSettingRow(val setting: TvSetting, val action: TvSettingAction)

/** Choices offered for the lyrics' size and Prism's text size (as on the phone's Text size). */
private val SIZES = listOf(0.85f, 1f, 1.15f, 1.3f)
private val LYRICS_SIZES = listOf(0.75f, 1f, 1.25f, 1.5f)
private val AMBIENT_MINUTES = listOf(1, 3, 5, 10)
/** The background's steps, lighter to darkest ([com.prism.music.data.prefs.TvPrefs.backdropDim]). */
internal val TV_BACKDROP_DIMS = (com.prism.music.data.prefs.TvPrefs.DIM_LIGHTER..com.prism.music.data.prefs.TvPrefs.DIM_DARKEST).toList()

internal fun tvBackdropLabel(dim: Int): String = tr(
    when (dim) {
        -1 -> R.string.tv_backdrop_lighter
        1 -> R.string.tv_backdrop_darker
        2 -> R.string.tv_backdrop_much_darker
        3 -> R.string.tv_backdrop_darkest
        else -> R.string.tv_backdrop_as_it_comes
    },
)

/**
 * Settings on the TV (1.8.0, plan §2): the short TV set, on the same settings as the phone (one Prism). Groups on the
 * left, a group's settings on the right; OK flips a switch or opens a choice's list (a panel at the right); a
 * setting's explanation is behind its ⓘ, never shown as a note.
 */
@Composable
fun TvSettingsPlace(nav: TvNav) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.flow.collectAsState()
    val sing by TvSing.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { TvSing.load(context) }
    var group by rememberSaveable { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf<Pair<String, List<Pair<String, () -> Unit>>>?>(null) }
    var info by remember { mutableStateOf<TvSetting?>(null) }
    var pickingLight by remember { mutableStateOf(false) }
    var pickingLyricsStyle by remember { mutableStateOf(false) }
    val say: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    // For an answer that may come after Settings has closed.
    val app = context.applicationContext
    val sayLater: (String) -> Unit = { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
    fun percent(v: Float) = tr(R.string.tv_percent, (v * 100).toInt())

    val groups: List<Pair<TvSettingGroup, List<TvSettingRow>>> = listOf(
        TvSettingGroup(tr(R.string.account), PrismIcons.Person, emptyList()) to buildList {
            if (settings.isLoggedIn) add(TvSettingRow(TvSetting(tr(R.string.tv_signed_in_as, settings.accountName.ifBlank { settings.accountEmail }), settings.accountEmail.takeIf { it.isNotBlank() }), TvSettingAction.Run {}))
            // Not signed in: OK signs in with the phone (it isn't a row that does nothing).
            else add(TvSettingRow(TvSetting(tr(R.string.tv_not_signed_in), tr(R.string.tv_sign_in_with_phone)), TvSettingAction.Run { nav.go(TvRoute.Pair(signIn = true)) }))
            add(TvSettingRow(TvSetting(tr(R.string.tv_sign_in_with_phone)), TvSettingAction.Run { nav.go(TvRoute.Pair(signIn = true)) }))
            add(TvSettingRow(TvSetting(tr(R.string.tv_phone_as_remote)), TvSettingAction.Run { nav.go(TvRoute.Pair(signIn = false)) }))
            if (settings.isLoggedIn) add(TvSettingRow(TvSetting(tr(R.string.sign_out)), TvSettingAction.Choose(listOf(tr(R.string.sign_out) to { tvSignOut(c) }))))
        },
        TvSettingGroup(tr(R.string.language), PrismIcons.Translate, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.language), AppLanguage.picked(context).takeIf { it.isNotBlank() }?.let(AppLanguage::nativeName) ?: tr(R.string.tv_language_tv)),
                TvSettingAction.Choose(listOf(tr(R.string.tv_language_tv) to { AppLanguage.pick(context, AppLanguage.PHONE) }) + AppLanguage.available.map { tag -> AppLanguage.nativeName(tag) to { AppLanguage.pick(context, tag) } }),
            ),
        ),
        TvSettingGroup(tr(R.string.sound), PrismIcons.Equalizer, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_sound_quality), settings.audioQuality.label),
                TvSettingAction.Choose(AudioQuality.entries.map { q -> q.label to { c.settings.setAudioQuality(q) } }),
            ),
            TvSettingRow(TvSetting(tr(R.string.tv_same_loudness), on = settings.normalize, info = tr(R.string.tv_same_loudness_info)), TvSettingAction.Flip({ c.settings.setNormalize(it) }, settings.normalize)),
        ),
        TvSettingGroup(tr(R.string.lyrics), PrismIcons.Lyrics, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_lyrics_size), percent(settings.tv.lyricsScale)),
                TvSettingAction.Choose(LYRICS_SIZES.map { v -> percent(v) to { c.settings.setTvLyricsScale(v) } }),
            ),
            // The phone's own lyrics style, with a live example of each (TvLyricsStyleChoice).
            TvSettingRow(TvSetting(tr(R.string.lyrics_style), settings.lyricsLook.style.label), TvSettingAction.Run { pickingLyricsStyle = true }),
            // How dark the background is behind the player and lyrics (1.8.1, Reddit); as it comes unless picked.
            TvSettingRow(
                TvSetting(tr(R.string.tv_backdrop), tvBackdropLabel(settings.tv.backdropDim), info = tr(R.string.tv_backdrop_info)),
                TvSettingAction.Choose(TV_BACKDROP_DIMS.map { d -> tvBackdropLabel(d) to { c.settings.setTvBackdropDim(d) } }),
            ),
            TvSettingRow(
                TvSetting(tr(R.string.lyrics_source), settings.lyricsSources.firstOrNull()?.label),
                TvSettingAction.Choose(settings.lyricsSources.map { s -> s.label to { c.settings.setLyricsOrder(listOf(s) + settings.lyricsOrder.filter { it != s }) } }),
            ),
        ),
        TvSettingGroup(tr(R.string.sing), PrismIcons.Mic, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_sing_on_tv), sing.label(), info = tr(R.string.tv_sing_info)),
                // On the app's scope: leaving Settings doesn't cut the check short (it switches Sing back off itself).
                TvSettingAction.Run { c.scope.launch(kotlinx.coroutines.Dispatchers.Main) { sayLater(TvSing.check(c)) } },
            ),
        ),
        TvSettingGroup(tr(R.string.tv_ambient_screen), PrismIcons.Moon, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.tv_ambient_screen), if (settings.tv.ambientMinutes == 0) tr(R.string.never) else tr(R.string.tv_ambient_after, settings.tv.ambientMinutes), info = tr(R.string.tv_ambient_info)),
                TvSettingAction.Choose(AMBIENT_MINUTES.map { m -> tr(R.string.tv_ambient_after, m) to { c.settings.setTvAmbientMinutes(m) } } + (tr(R.string.never) to { c.settings.setTvAmbientMinutes(0) })),
            ),
        ),
        // Home's rows: shown or hidden, in what order (also from the end of Home itself); the editor is TvHomeEditor.
        TvSettingGroup(tr(R.string.home), PrismIcons.Home, emptyList()) to listOf(
            TvSettingRow(
                TvSetting(tr(R.string.customize_home), TvHomeLayout.hiddenCount(settings.tv.homeLayout, settings.homeLayout).takeIf { it > 0 }?.let { tr(R.string.tv_home_hidden_count, it) }),
                TvSettingAction.Run { nav.go(TvRoute.HomeEditor) },
            ),
        ),
        TvSettingGroup(tr(R.string.text_size), PrismIcons.Visible, emptyList()) to listOf(
            TvSettingRow(TvSetting(tr(R.string.text_size), percent(settings.textScale)), TvSettingAction.Choose(SIZES.map { v -> percent(v) to { c.settings.setTextScale(v) } })),
        ),
        // The highlights' light: Spectrum or White light (monochrome), the phone's own Color setting.
        TvSettingGroup(tr(R.string.colors), PrismIcons.Palette, emptyList()) to listOf(
            TvSettingRow(TvSetting(tr(R.string.colors), settings.lightStyle.label), TvSettingAction.Run { pickingLight = true }),
        ),
        TvSettingGroup(tr(R.string.about_3), PrismIcons.Info, emptyList()) to listOf(
            TvSettingRow(TvSetting(tr(R.string.tv_version, BuildConfig.VERSION_NAME)), TvSettingAction.Run {}),
            TvSettingRow(TvSetting(tr(R.string.check_for_updates)), TvSettingAction.Run { scope.launch { say(runCatching { c.updates.checkNow()?.let { tr(R.string.tv_update_ready, it) } ?: tr(R.string.you_re_up_to_date) }.getOrElse { it.message ?: tr(R.string.you_re_up_to_date) }) } }),
        ),
    )
    val rows = groups.getOrNull(group)?.second.orEmpty()
    Box(Modifier.fillMaxSize()) {
        TvSettingsScreen(
            groups.map { (g, r) -> g.copy(settings = r.map { it.setting }) },
            group, { group = it },
            onSetting = { i ->
                when (val a = rows.getOrNull(i)?.action) {
                    is TvSettingAction.Flip -> a.set(!a.now)
                    is TvSettingAction.Choose -> choosing = rows[i].setting.title to a.options
                    is TvSettingAction.Run -> a.run()
                    null -> Unit
                }
            },
            onInfo = { i -> info = rows.getOrNull(i)?.setting },
        )
        choosing?.let { (title, options) ->
            BackHandler { choosing = null }
            TvPanelBox(LocalTvScreen.current + "/choice") {
                TvActionPanel(title, options.map { (label, pick) -> TvAction(label, PrismIcons.Check) { pick(); choosing = null } })
            }
        }
        if (pickingLight) {
            BackHandler { pickingLight = false }
            TvPanelBox(LocalTvScreen.current + "/light") { TvLightChoice(settings.lightStyle) { c.settings.setLightStyle(it); pickingLight = false } }
        }
        if (pickingLyricsStyle) {
            BackHandler { pickingLyricsStyle = false }
            // As the phone's "Start from": the style with its own parts (motion, glow, weight).
            TvPanelBox(LocalTvScreen.current + "/lyricsstyle") {
                TvLyricsStyleChoice(settings.lyricsLook.style) { c.settings.setLyricsStartFrom(it); pickingLyricsStyle = false }
            }
        }
        info?.let { s ->
            BackHandler { info = null }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                TvPanelBox(LocalTvScreen.current + "/info") {
                    TvInfoCard(s.title, s.info.orEmpty(), Modifier.align(Alignment.Center).tvItem("$TAG_SETTINGS/infoclose", entry = true).clickable { info = null })
                }
            }
        }
    }
}

/** A panel's own focus memory (a screen key of its own). */
@Composable
private fun TvPanelBox(screen: String, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val memory = LocalTvFocusMemory.current
    remember(screen) { memory.opening(screen); true }
    // Focus stays in the panel (Settings' rows behind it can't be reached and pressed unseen).
    androidx.compose.runtime.CompositionLocalProvider(LocalTvScreen provides screen) { Box(Modifier.fillMaxSize().tvHoldsFocus(), content = content) }
}


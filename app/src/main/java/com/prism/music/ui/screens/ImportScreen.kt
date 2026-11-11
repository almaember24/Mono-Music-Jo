package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.components.PrismButton
import com.prism.music.ui.components.PrismOutlinedButton
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.theme.PrismIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prism.music.data.SyncState
import com.prism.music.ui.LocalNavigator
import com.prism.music.ui.Routes
import com.prism.music.ui.components.EmptyState
import com.prism.music.ui.components.SubPage
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

private const val TMM = "https://www.tunemymusic.com/transfer"

/**
 * Library → Import playlist. TuneMyMusic (the service YouTube Music's own app uses) moves playlists
 * from Spotify, Apple Music and others straight into the YouTube Music account; Prism opens it in the
 * browser and syncs the library when the listener comes back. Prism never shares its sign-in with it.
 */
@Composable
fun ImportScreen(bottomPadding: Dp) {
    val c = LocalContainer.current
    val nav = LocalNavigator.current
    val settings = LocalAppSettings.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val sync by c.library.syncState.collectAsState()
    // Set once TuneMyMusic was opened, so coming back to Prism pulls in the new playlists.
    var opened by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && opened) { opened = false; scope.launch { c.library.sync() } }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(path: String) { opened = true; uri.openUri("$TMM$path") }

    SubPage(tr(R.string.import_playlist), bottomPadding, subtitle = tr(R.string.from_spotify_apple_music_and_more)) {
        if (!settings.isLoggedIn) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(PrismIcons.PlaylistAdd, tr(R.string.sign_in_to_import_playlists), tr(R.string.imported_playlists_go_to_your_youtube))
                    PrismButton(onClick = { nav.go(Routes.LOGIN) }) { Icon(PrismIcons.Login, null); Spacer(Modifier.width(8.dp)); Text(tr(R.string.sign_in)) }
                }
            }
            return@SubPage
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr(R.string.tunemymusic_copies_your_playlists_liked_songs_all),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                PrismButton(onClick = { open("/spotify-to-youtube-music") }, Modifier.fillMaxWidth()) { Text(tr(R.string.from_spotify)) }
                PrismButton(onClick = { open("/apple-music-to-youtube-music") }, Modifier.fillMaxWidth()) { Text(tr(R.string.from_apple_music)) }
                PrismOutlinedButton(onClick = { open("") }, Modifier.fillMaxWidth()) {
                    Text(tr(R.string.from_another_service_or_a_file)); Spacer(Modifier.width(8.dp)); Icon(PrismIcons.OpenInNew, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.size(4.dp))
                Text(tr(R.string.how_it_works), style = MaterialTheme.typography.titleSmall)
                Text(
                    tr(R.string.t_1_sign_in_to_spotify_or_all, settings.accountEmail.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()) + "\n" +
                        tr(R.string.t_3_when_the_transfer_finishes_come),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                PrismTextButton(onClick = { scope.launch { c.library.sync() } }, enabled = sync != SyncState.SYNCING) {
                    if (sync == SyncState.SYNCING) com.prism.music.ui.components.PrismSpinner(Modifier.size(16.dp), stroke = 2.dp)
                    else Icon(PrismIcons.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (sync == SyncState.SYNCING) tr(R.string.syncing) else tr(R.string.sync_library_now))
                }
                Text(
                    tr(R.string.tunemymusic_is_a_separate_service_not_all),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

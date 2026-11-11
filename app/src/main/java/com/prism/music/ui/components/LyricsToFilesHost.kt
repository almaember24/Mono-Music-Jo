package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import com.prism.music.trp
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.prism.music.data.local.LyricsToFiles
import com.prism.music.ui.theme.LocalContainer
import kotlinx.coroutines.launch

/**
 * Carries out requests to save lyrics into music files (#16; [LyricsToFiles.request]): finds the files and their lyrics,
 * asks Android's permission to change them, and writes them. At the app's root, so the permission dialog's answer
 * still lands after the menu that asked has closed.
 */
@Composable
fun LyricsToFilesHost() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<LyricsToFiles.Item>>(emptyList()) }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun write(items: List<LyricsToFiles.Item>) = scope.launch {
        val n = c.lyricsToFiles.write(items)
        toast(
            when {
                n == items.size -> trp(R.plurals.lyrics_saved_into_n_files, n)
                n == 0 -> tr(R.string.couldn_t_save_the_lyrics_into)
                else -> trp(R.plurals.lyrics_saved_n_of_files, items.size, n, items.size)
            }
        )
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) write(pending) else toast(tr(R.string.nothing_was_changed))
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) write(pending) else toast(tr(R.string.prism_needs_permission_to_change_your))
    }
    LaunchedEffect(Unit) {
        c.lyricsToFiles.requests.collect { songs ->
            val targets = c.lyricsToFiles.targets(songs)
            if (targets.isEmpty()) { toast(tr(R.string.no_flac_mp3_or_m4a_files)); return@collect }
            if (targets.size > 1) toast(trp(R.plurals.getting_lyrics_for_n_files, targets.size))
            val items = c.lyricsToFiles.prepare(targets).take(MAX_FILES)
            if (items.isEmpty()) { toast(if (targets.size == 1) tr(R.string.no_lyrics_found_for_this_song) else tr(R.string.no_lyrics_found_for_these_songs)); return@collect }
            pending = items
            when {
                Build.VERSION.SDK_INT >= 30 -> c.lyricsToFiles.writeRequest(items)?.let { consent.launch(IntentSenderRequest.Builder(it).build()) }
                Build.VERSION.SDK_INT <= 28 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ->
                    legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else -> write(items)
            }
        }
    }
}

/** At most this many files are changed at once (Android's permission request lists them all). */
private const val MAX_FILES = 1000

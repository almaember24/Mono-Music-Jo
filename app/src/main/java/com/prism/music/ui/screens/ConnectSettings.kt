package com.prism.music.ui.screens

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.prism.music.ui.components.tickWhileShown
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.PrismIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Settings → Prism Connect: speakers and TVs, the browser remote, and handoff between Prism devices. */
@Composable
fun ConnectSettings() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Group(tr(R.string.speakers_tvs)) {
            Item(
                tr(R.string.play_on_a_speaker_or_tv), tr(R.string.pick_it_from_the_device_line),
                info = tr(R.string.tap_the_line_under_the_player_all),
                onClick = {},
            )
        }

        TvInstallGroup()

        Group(tr(R.string.control_from_a_browser)) {
            Toggle(
                tr(R.string.control_from_a_browser), tr(R.string.from_a_computer_or_tablet_on), s.webRemote,
                info = tr(R.string.play_search_and_see_the_queue),
            ) { c.settings.setWebRemote(it) }
            if (s.webRemote) {
                val remote = c.webRemote
                val running by remote.running.collectAsState()
                // The address and code can change (another network, a pairing): looked at again every few seconds.
                var tick by remember { mutableIntStateOf(0) }
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(lifecycle) { lifecycle.tickWhileShown(2_000) { tick++ } }
                val address = remember(tick, running) { remote.address }
                val code = remember(tick) { remote.auth.code }
                val browsers = remember(tick) { remote.auth.browsers() }
                Item(
                    tr(R.string.address), address ?: if (running) tr(R.string.connect_to_wi_fi_to_use) else tr(R.string.couldn_t_start_try_switching_it),
                    onClick = {
                        address?.let {
                            clipboard.setText(AnnotatedString(it))
                            android.widget.Toast.makeText(context, tr(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { if (address != null) Icon(PrismIcons.Copy, tr(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Item(tr(R.string.pairing_code), tr(R.string.one_browser_per_code, code.take(3), code.drop(3)), onClick = { remote.auth.newCode(); tick++ }) {
                    Icon(PrismIcons.Refresh, tr(R.string.new_code), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                browsers.forEach { b ->
                    Item(b.name, tr(R.string.paired_last_used, day(b.pairedAt), day(b.lastSeen)), onClick = {}) {
                        androidx.compose.material3.IconButton(onClick = { remote.auth.forget(b.id); tick++ }) {
                            Icon(PrismIcons.Close, tr(R.string.unpair, b.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (browsers.size > 1) Item(tr(R.string.unpair_all_browsers), tr(R.string.they_ll_need_a_new_code), onClick = { remote.auth.forgetAll(); tick++ })

            }
        }

        Group(tr(R.string.handoff)) {
            val handoff = c.handoff
            Toggle(
                tr(R.string.available_for_handoff), tr(R.string.move_music_between_your_prism_devices), s.handoffAvailable,
                info = tr(R.string.other_devices_with_prism_on_this),
            ) { c.settings.setHandoffAvailable(it) }
            val shown by handoff.shownCode.collectAsState()
            shown?.let { Item(tr(R.string.pairing_code), tr(R.string.type_it_on, it.code.take(3), it.code.drop(3), it.from), onClick = {}) }
            val paired by handoff.pairedDevices.collectAsState()
            paired.forEach { (id, name) ->
                Item(name, tr(R.string.paired), onClick = {}) {
                    androidx.compose.material3.IconButton(onClick = { handoff.forget(id) }) {
                        Icon(PrismIcons.Close, tr(R.string.forget, name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

        }
    }
}

/**
 * Prism on a TV (1.8.0): "Share Prism with a TV" hands this phone's own copy to a TV on the Wi-Fi ([TvInstall]), and the
 * Downloader code gets the newest release from the internet. Off whenever the page opens, and sharing stops when it goes
 * (the switch isn't kept), so nothing listens on the network unless someone is putting Prism on a TV right now.
 */
@Composable
private fun TvInstallGroup() {
    val share = LocalContainer.current.tvInstall
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var on by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(share) { onDispose { share.stop() } }
    // Prism put away with the page still open (another app, the screen off): sharing stops a little later, not at once
    // (a TV may be part-way through taking it, the phone's screen gone dark meanwhile), and not if Prism comes back.
    val pageLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(pageLifecycle, share) {
        var later: kotlinx.coroutines.Job? = null
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (on) later = scope.launch {
                    kotlinx.coroutines.delay(SHARE_AWAY_MS)
                    share.stop()
                    on = false
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { later?.cancel(); later = null }
                else -> Unit
            }
        }
        pageLifecycle.addObserver(o)
        onDispose { later?.cancel(); pageLifecycle.removeObserver(o) }
    }
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(context, tr(R.string.copied), android.widget.Toast.LENGTH_SHORT).show()
    }
    Group(tr(R.string.prism_on_a_tv)) {
        Toggle(tr(R.string.share_prism_with_a_tv), tr(R.string.from_this_phone_over_your_wi_fi), on, info = tr(R.string.tv_share_info)) { want ->
            if (want) { failed = !share.start(); on = !failed } else { share.stop(); on = false }
        }
        if (failed) Note(tr(R.string.this_copy_can_t_be_shared))
        if (on) {
            // The address follows the network (another Wi-Fi), so it's read again every few seconds.
            var tick by remember { mutableIntStateOf(0) }
            val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) { lifecycle.tickWhileShown(2_000) { tick++ } }
            val address = remember(tick) { share.address }
            val sent by share.sent.collectAsState()
            Item(
                tr(R.string.address), address ?: tr(R.string.connect_to_wi_fi_to_use),
                onClick = { address?.let { copy(it) } },
            ) { if (address != null) Icon(PrismIcons.Copy, tr(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (sent > 0) Note(tr(R.string.sent_finish_installing_on_the_tv))
            Note(tr(R.string.tv_share_steps))
        }
        val code = com.prism.music.connect.TvInstall.DOWNLOADER_CODE
        if (code.isNotBlank()) Item(
            tr(R.string.downloader_code), tr(R.string.downloader_code_detail, code),
            info = tr(R.string.downloader_code_info), onClick = { copy(code) },
        ) { Icon(PrismIcons.Copy, tr(R.string.copy), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** How long "Share Prism with a TV" goes on with Prism put away (a TV part-way through taking it finishes). */
private const val SHARE_AWAY_MS = 2 * 60_000L

private fun day(ms: Long): String {
    if (ms <= 0) return "never"
    val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (d) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        else -> com.prism.music.localDates("dMMM").format(d)
    }
}

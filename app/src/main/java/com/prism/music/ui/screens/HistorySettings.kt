package com.prism.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prism.music.R
import com.prism.music.data.HistoryPause
import com.prism.music.data.PlayCount
import com.prism.music.tr
import com.prism.music.ui.components.PrismTextButton
import com.prism.music.ui.components.pane
import com.prism.music.ui.theme.LocalAppSettings
import com.prism.music.ui.theme.LocalContainer
import com.prism.music.ui.theme.LocalUi
import com.prism.music.ui.theme.PrismIcons

/** "until 3:05 PM", "until Tue 3:05 PM" or "until you turn it back on": how long history stays paused. */
internal fun pausedUntilText(until: Long, now: Long = System.currentTimeMillis()): String {
    if (until == HistoryPause.FOREVER) return tr(R.string.hist_paused_until_on)
    val zone = java.time.ZoneId.systemDefault()
    val sameDay = java.time.Instant.ofEpochMilli(until).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return tr(R.string.hist_paused_until, formatAt(until, com.prism.music.localDates(if (sameDay) "jmm" else "EEEjmm")))
}

/**
 * Settings → Recommendations & Replay → History (1.8.1): pause it (#296), and how long a song plays before it counts
 * (#263). Both start as Prism always was: recording, at ten seconds, YouTube Music told as a song starts.
 */
@Composable
internal fun ListeningHistoryGroup() {
    val c = LocalContainer.current
    val s = LocalAppSettings.current
    val prefs = c.settings
    val b = s.behavior
    Group(tr(R.string.hist_title), info = tr(R.string.hist_info)) {
        Label(tr(R.string.hist_pause), info = tr(R.string.hist_pause_info))
        val choice = HistoryPause.choiceOf(b.historyPausedUntil)
        Chips(HistoryPause.Choice.entries, choice, { it.label }) { prefs.setHistoryPausedUntil(HistoryPause.until(it, System.currentTimeMillis())) }
        if (choice != HistoryPause.Choice.OFF) Note(tr(R.string.hist_paused_line, pausedUntilText(b.historyPausedUntil)))
        Label(tr(R.string.hist_count_after), info = tr(R.string.hist_count_after_info))
        Chips(PlayCount.CHOICES, b.playCountSeconds.takeIf { it in PlayCount.CHOICES } ?: PlayCount.DEFAULT_SEC, { PlayCount.label(it) }) { prefs.setPlayCountSeconds(it) }
        // Only the account's history waits for it, and only when there's an account.
        if (s.isLoggedIn) Toggle(
            tr(R.string.hist_count_account), tr(R.string.hist_count_account_detail), b.playCountOnAccount, info = tr(R.string.hist_count_account_info),
        ) { prefs.setPlayCountOnAccount(it) }
    }
}

/** The note at the top of Replay while history is paused (#296): what's paused, until when, and the way back on. */
@Composable
internal fun HistoryPausedNote(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val ui = LocalUi.current
    val until = LocalAppSettings.current.behavior.historyPausedUntil
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().pane(ui.card).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PrismIcons.History, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tr(R.string.hist_paused_title), style = MaterialTheme.typography.titleSmall)
            Text(pausedUntilText(until), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PrismTextButton(onClick = { c.settings.setHistoryPausedUntil(0) }) { Text(tr(R.string.hist_turn_on)) }
    }
}

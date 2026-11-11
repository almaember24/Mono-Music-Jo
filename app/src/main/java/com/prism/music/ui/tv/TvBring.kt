package com.prism.music.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer

/**
 * A phone bringing its Prism to this TV (1.8.0, [com.prism.music.connect.BringTransfer]): a card in the corner, over
 * any screen, never taking focus. How far it is, then "Your listening, likes and playlists are here", or, when it
 * didn't finish, that nothing here changed and to try again on the phone. It goes by itself.
 */
@Composable
fun TvBringBanner(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val state by c.handoff.bringIn.collectAsState()
    val s = state ?: return
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.width(420.dp).tvGlass(RoundedCornerShape(18.dp), TvPanelFill).padding(20.dp).testTag(TAG_BRING)) {
            Text(
                when {
                    s.done -> tr(R.string.bring_received)
                    s.failed -> tr(R.string.bring_failed_tv)
                    else -> tr(R.string.bring_receiving, s.from)
                },
                style = MaterialTheme.typography.titleMedium, color = scheme.onSurface,
            )
            if (!s.failed) {
                Spacer(Modifier.height(12.dp))
                val size = { b: Long -> android.text.format.Formatter.formatShortFileSize(context, b) }
                TvProgress(if (s.done) 1f else s.fraction, size(s.got), size(s.total), Modifier.fillMaxWidth())
            }
        }
    }
}

const val TAG_BRING = "tv_bring"

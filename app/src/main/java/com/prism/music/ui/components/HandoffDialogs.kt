package com.prism.music.ui.components

import com.prism.music.R
import com.prism.music.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.ui.theme.LocalContainer

/**
 * Prism Connect's pairing, wherever Prism is: on the device that's moving its music, a box for the code the other one
 * shows; on the receiving device, that code, big.
 */
@Composable
fun HandoffDialogs() {
    val handoff = LocalContainer.current.handoff
    val asking by handoff.askingCodeFor.collectAsState()
    val shown by handoff.shownCode.collectAsState()
    // A TV paired by its QR code asked for this phone's sign-in (1.8.0): asked here first.
    val signInFor by handoff.signInAsk.collectAsState()
    signInFor?.let { tv -> TvSignInAsk(tv) { handoff.askSignIn(null) } }
    // Then (or after pairing with a TV without a sign-in): "Also bring your listening history, likes and Prism
    // playlists?" Nothing goes until the listener says yes.
    val bringFor by handoff.bringAsk.collectAsState()
    bringFor?.takeIf { signInFor == null }?.let { tv -> TvBringAsk(tv) { handoff.askBring(null) } }
    val bringing by handoff.bringing.collectAsState()
    bringing?.let { b -> TvBringProgress(b, onTryAgain = { handoff.dismissBringing(); handoff.askBring(b.device) }, onClose = { handoff.dismissBringing() }) }
    // A pairing link (a TV's QR code, or any page or app that opens one): nothing happens until the listener says yes.
    val linkPair by handoff.linkPair.collectAsState()
    linkPair?.let { ask ->
        AlertDialog(
            onDismissRequest = { handoff.answerLinkPair(false) },
            title = { Text(tr(R.string.pair_with_2, ask.name)) },
            text = { Text(tr(R.string.tv_link_pair_note, ask.name, ask.shownAddress)) },
            confirmButton = { PrismTextButton(onClick = { handoff.answerLinkPair(true) }, Modifier.testTag("linkPair/yes")) { Text(tr(R.string.pair)) } },
            dismissButton = { PrismTextButton(onClick = { handoff.answerLinkPair(false) }) { Text(tr(R.string.cancel)) } },
        )
    }
    asking?.let { them ->
        var code by remember(them) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { handoff.submitCode(null) },
            title = { Text(tr(R.string.pair_with, them)) },
            text = {
                Column {
                    Text(tr(R.string.is_showing_a_six_digit_code, them), style = MaterialTheme.typography.bodyMedium)
                    com.prism.music.ui.components.PrismTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                        singleLine = true,
                        placeholder = { Text("000000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = TextStyle(fontSize = 24.sp, letterSpacing = 6.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
            },
            confirmButton = { PrismTextButton(onClick = { handoff.submitCode(code) }, enabled = code.length == 6) { Text(tr(R.string.pair)) } },
            dismissButton = { PrismTextButton(onClick = { handoff.submitCode(null) }) { Text(tr(R.string.cancel)) } },
        )
    }
    shown?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr(R.string.pair_with_2, s.from)) },
            text = {
                Column {
                    Text(tr(R.string.wants_to_play_music_here_to, s.from, s.from), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${s.code.take(3)} ${s.code.drop(3)}",
                        style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    )
                    Text(tr(R.string.it_goes_away_once_they_re), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                }
            },
            confirmButton = { PrismTextButton(onClick = { handoff.hideCode() }) { Text(tr(R.string.not_now)) } },
        )
    }
}

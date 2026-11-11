package com.prism.music.ui.tv

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.prism.music.R
import com.prism.music.tr
import com.prism.music.ui.theme.LocalContainer

/** The first run's welcome (Back is "Continue without an account"). */
@Composable
fun TvWelcomePage(nav: TvNav) {
    androidx.activity.compose.BackHandler { nav.firstRunDone() }
    TvWelcomeScreen(onPhone = { nav.go(TvRoute.Pair(signIn = true, firstRun = true)) }, onSkip = { nav.firstRunDone() })
}

/**
 * Pairing a phone with this TV (1.8.0, plan §4): "Sign in with your phone" ([signIn]) or "Use your phone as a remote".
 * While this page is open the TV takes a pairing, with the code on screen (typed on the phone, or read from the QR
 * code by its camera); once paired, a remote is done, and a sign-in waits for the phone's yes and the account.
 */
@Composable
fun TvPairPage(signIn: Boolean, nav: TvNav, onDone: () -> Unit = { nav.back() }) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val handoff = c.handoff
    val first = remember { handoff.openPairing() }
    DisposableEffect(Unit) { onDispose { handoff.closePairing() } }
    // The ambient screen would take this page's place, and closing it closes the pairing.
    HoldsAmbient()
    // A new code after every try (on screen and in the QR code); once a phone has paired none is taken, and the last
    // one stays on screen.
    val inForce by handoff.tvCode.collectAsState()
    var code by remember { mutableStateOf(first) }
    LaunchedEffect(inForce) { inForce?.let { code = it } }
    var status by remember { mutableStateOf(tr(R.string.tv_waiting_phone)) }
    LaunchedEffect(Unit) {
        handoff.pairedWith.collect { phone ->
            if (!signIn) {
                Toast.makeText(context, tr(R.string.tv_paired_with, phone), Toast.LENGTH_LONG).show()
                onDone()
            } else status = tr(R.string.tv_confirm_on_phone, phone)
        }
    }
    LaunchedEffect(Unit) {
        handoff.signedIn.collect { account ->
            Toast.makeText(context, tr(R.string.tv_signed_in_as, account), Toast.LENGTH_LONG).show()
            onDone()
        }
    }
    TvPairScreen(
        title = tr(if (signIn) R.string.tv_sign_in_with_phone else R.string.tv_phone_as_remote),
        code = code,
        link = handoff.pairingLink(code, signIn) ?: "prism://pair",
        tvName = handoff.name,
        status = status,
        onCancel = { nav.back() },
        signIn = signIn,
    )
}
